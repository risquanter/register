# PLAN — Sigstore Signature Verification (TODO 39)

Status: design-level draft — awaiting review. Two open decisions await the
user's ruling at plan review (§5: admission controller; Rekor identity
monitoring). Implementation requires elevation to implementation-grade
(exact file inventory, scripts, workflow YAML) and plan approval. **User
mandate 2026-08-10: the implementation undergoes a mandatory
`/security-review-deep` before landing, no extra instruction needed.**

## 1. Goal and ruled decisions

Close the sbt/Maven signature-verification gap (ADR-020 §12) for first-party
artifacts and extend the same mechanism to container images, so that every
consumption point — dev machine, CI, image registry, k3d deployment — can
verify *who built* an artifact, not merely that its bytes are intact.

Rulings (2026-08-10):

- **Trust model: bundle-based verification.** The `.sigstore` bundle
  co-located with the artifact on Maven Central is the verification input —
  it contains the same data a Rekor lookup would return (signature, Fulcio
  certificate, Rekor entry + inclusion proof), obtained deterministically
  and offline-capable. Authenticity = the bundle verifies against a pinned
  **certificate-identity policy** (the publishing repo's release workflow
  via GitHub's OIDC issuer). Rekor remains the transparency anchor: the
  bundle's inclusion proof is checked, and log monitoring (§6) watches for
  signatures claiming our identities that our CI never produced.
- **One verification component, reused everywhere** (user requirement:
  standard tooling, no bespoke mechanisms): a thin wrapper around the
  `cosign` CLI plus a single declarative policy file. The same policy data
  drives dev-machine checks, CI checks, and the cluster admission policy.
- **Only CI-built deployables are trusted.** Keyless signing in GitHub
  Actions makes the signature itself the proof of CI origin: the Fulcio
  certificate names the exact repository + workflow + ref that built the
  artifact. Verifying that identity IS the "only CI-verified deployables"
  rule — local builds are unsigned and fail verification by construction
  (the local `docker compose` dev loop stays outside the verified path on
  purpose).

## 2. Scope boundary — what Sigstore does NOT cover here

**Third-party / transitive dependencies are out of Sigstore scope.**
Sigstore signing is not mandatory on Maven Central; most third-party
artifacts (zio, tapir, …) ship at best PGP signatures that sbt never
checks. No verification mandate can be imposed on upstreams. Their coverage
remains the existing ADR-020 controls: exact pinning (§1), the 14-day
cooldown (§10), the publisher trust policy (§11), coursier checksum
verification (integrity), and on the npm side `npm audit signatures`
(registry signatures + Sigstore provenance where published — already
adopted, §9). Optional future hardening (not in this plan's scope): an
opportunistic mode that verifies bundles for any third-party artifact that
happens to publish them.

## 3. Architecture

### 3.1 The policy file (single source of truth)

One checked-in file, e.g. `security/signing-policy.yaml`, listing every
verified subject with its expected identity:

```yaml
# subject → who may sign it (certificate identity + OIDC issuer)
maven:
  com.risquanter:vql-engine:
    issuer: https://token.actions.githubusercontent.com
    identity: https://github.com/risquanter/vql-engine/.github/workflows/release.yml@refs/tags/*
  com.risquanter:metalog-distribution: { ... }
  com.risquanter:hdr-rng: { ... }
images:
  ghcr.io/risquanter/register-server:
    issuer: https://token.actions.githubusercontent.com
    identity: https://github.com/risquanter/register/.github/workflows/release-images.yml@refs/tags/*
  ghcr.io/risquanter/frontend: { ... }
  ghcr.io/risquanter/irmin-prod: { ... }
```

### 3.2 The verify component

`scripts/verify-signatures.sh` (name at elevation) wrapping standard cosign
invocations — no custom cryptography anywhere:

- Maven artifacts: fetch jar + `.sigstore` bundle from
  `repo1.maven.org`, then `cosign verify-blob --bundle <bundle>
  --certificate-identity <id> --certificate-oidc-issuer <issuer> <jar>`;
  compare the verified jar's SHA-256 against what coursier resolved into
  the local cache (ties the verified bytes to the build input).
- Images: `cosign verify --certificate-identity <id>
  --certificate-oidc-issuer <issuer> <ref@digest>`.
- Reads its subjects and identities from the policy file; exits non-zero on
  any failure (fail closed).

### 3.3 Consumption points (same component, four places)

| Point | When | What runs |
|---|---|---|
| Dev machine | **Pin-bump time** — the moment a first-party version in `build.sbt` is edited (a supply-chain-skill procedure step) | verify script, Maven subjects |
| CI — dependency gate | Every register CI build | verify script, Maven subjects |
| CI — image publishing | Release workflow: build the four images → sign keyless (`cosign sign`) → attach SLSA provenance attestation → push **by digest** to the image registry (ghcr) | `cosign sign` / `cosign attest` |
| Deployment (k3d) | Before/at admission into the cluster | verify script (pre-deploy check) **plus** a standard admission controller enforcing the same policy in-cluster (§3.4) |

Docker builder-stage verification (running the script inside
`Dockerfile.register-prod`/`frontend-prod` before `sbt update`) is included
at elevation if the cosign binary cost in the builder is acceptable —
otherwise the CI dependency gate covers image builds, since release images
are CI-built (ruling above).

### 3.4 Cluster-side enforcement (standard tooling, ruled requirement)

Admission-time image verification in k3d uses an off-the-shelf Sigstore
admission controller — the policy expressed once in §3.1 is projected into
the controller's CR format (generation script or committed manifests kept
in sync at elevation). Open decision §5-1 picks the controller.

## 4. Library-side prerequisite

The publishing repos (`vql-engine`, `metalog-distribution`, `hdr-rng`)
release via GitHub Actions with Sigstore bundle publication for **all**
artifact classifiers. Partially true already: Central hosts `.sigstore`
bundles for vql-engine 0.10.2's sources/javadoc jars; the main jar bundle
must be confirmed/added in the release workflows. This is sibling-repo
work, coordinated but not part of register's inventory.

## 5. Open decisions

1. **Admission controller.** (A) Sigstore **policy-controller**
   (`ClusterImagePolicy` CRs — purpose-built, exact Sigstore semantics,
   small footprint). (B) **Kyverno** with `verifyImages` rules (CNCF,
   broader adoption, one engine reusable for non-signing policies later,
   heavier). Both are standard, neither is a hack. Recommendation: **A**
   while image verification is the only policy need; revisit toward B if
   general cluster policy needs appear.
2. **Rekor monitoring** (§1): adopt a scheduled identity-monitor (e.g. a
   GitHub Actions cron running rekor-cli/rekor-monitor searches for our
   identities) now, or defer to a follow-up. Recommendation: include —
   it is the piece that turns the transparency log into an actual alarm.

## 6. Verification plan (of this plan's implementation)

- Positive path: pinned versions verify at all four points.
- Negative paths (each must fail closed): tampered jar bytes; bundle for a
  different artifact; certificate identity from a different repo/workflow;
  unsigned image rejected by the admission controller in a k3d test
  cluster; local (unsigned) image rejected.
- Full register suite green (all sbt tiers + BATS C/A/B — image pipeline
  changes are release-validation scope).
- **Mandatory `/security-review-deep` on the implementation diff** (user
  ruling 2026-08-10) before landing.

## File inventory

(To be completed at implementation-grade elevation — expected: policy file,
verify script, GitHub Actions workflow(s), admission-controller manifests,
ADR-020 §12 update, supply-chain skill update, VERSION-UPGRADE-PROTOCOL.md
update, docs/dev/TODO.md item-39 closure.)
