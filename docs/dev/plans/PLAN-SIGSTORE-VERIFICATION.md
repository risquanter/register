# PLAN — CI Pipeline with Sigstore Signature Verification (TODO 39)

Status: design-level draft — awaiting review. **Scope widened (user
directive 2026-08-10): this plan now covers the repository's full CI
pipeline** — signature verification/signing (§1–§6) is one stage of it; the
pipeline stages, static-analysis tooling, and image publishing are §7. Open
decisions await the user's ruling at plan review (§5 and §7.6).
Implementation requires elevation to implementation-grade (exact file
inventory, scripts, workflow YAML) and plan approval. **User mandate
2026-08-10: the implementation undergoes a mandatory
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
  **certificate-identity policy** (the publishing repo's CI build workflow
  via GitHub's OIDC issuer — the exact strings are in §3.1). Rekor remains the transparency anchor: the
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
#
# The libraries sign artifacts in ci-build.yml on push to main (cosign
# sign-blob), NOT in a tagged release workflow. The Fulcio identity in the
# published bundles is therefore ci-build.yml@refs/heads/main. Pin that exact
# string (Decision 1A in hdr-rng PLAN-SIGSTORE-EXPECTATIONS.md). If a library
# ever moves signing into a tagged release.yml, that identity change must be
# coordinated with a bump here BEFORE it lands, or verification fails closed.
maven:
  com.risquanter:vql-engine:
    issuer: https://token.actions.githubusercontent.com
    identity: https://github.com/risquanter/vql-engine/.github/workflows/ci-build.yml@refs/heads/main
  com.risquanter:metalog-distribution:
    issuer: https://token.actions.githubusercontent.com
    identity: https://github.com/risquanter/metalog-distribution/.github/workflows/ci-build.yml@refs/heads/main
  com.risquanter:hdr-rng:
    issuer: https://token.actions.githubusercontent.com
    identity: https://github.com/risquanter/hdr-rng/.github/workflows/ci-build.yml@refs/heads/main
# Each maven subject also ships a SLSA build-provenance attestation next to its
# main jar on Central, as `<artifact>-<version>.jar.intoto.jsonl` (library-side
# Decision 7B). It is signed with the SAME ci-build.yml@refs/heads/main identity
# pinned above — no separate identity entry is needed; the verify component
# (§3.2) checks the provenance file against the subject's existing identity.
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
- Maven build provenance (SLSA): the publishers ship a build-provenance
  attestation next to each main jar as `<artifact>-<version>.jar.intoto.jsonl`
  (library-side Decision 7B). After the bundle check, fetch that file and run
  `cosign verify-blob-attestation --bundle
  <artifact>-<version>.jar.intoto.jsonl --new-bundle-format
  --certificate-identity <id> --certificate-oidc-issuer <issuer>
  --type slsaprovenance <jar>` against the SAME pinned identity. This is
  additive to the `.sigstore` check, not a replacement; a missing or
  non-verifying provenance file fails closed. One attestation covers every
  variant's jar, so the same file verifies each variant by its own digest.
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
artifact classifiers. Satisfied — verified against `repo1.maven.org`
2026-08-11: every consumed version (vql-engine 0.10.2 and 0.11.0,
metalog-distribution 0.9.0, hdr-rng 0.1.0), every classifier (main,
sources, javadoc, pom) and every cross-build (`_3`, `_sjs1_3`) carries a
spec-format v0.3 `.sigstore.json` bundle plus a `.asc` signature. The
library-side pipeline hardening (exact identity check, SHA-pinned actions,
completeness checks, real provenance, signed release tags) is governed by
`PLAN-SIGSTORE-EXPECTATIONS.md` in the hdr-rng repository — sibling-repo
work, coordinated but not part of register's inventory.

The same release pipelines also produce a SLSA build-provenance attestation
and ship it next to each main jar as `<artifact>-<version>.jar.intoto.jsonl`,
applied in the ci-build.yml workflows. Two coordination points govern how
register relies on it: (1) Maven Central rejects a bare `.intoto.jsonl` —
unlike `.sigstore.json`/`.asc`, which it tolerates unchecksummed, the Portal
requires `<file>.md5`, `<file>.sha1`, and a GPG `<file>.asc` for the provenance
file or the deployment fails validation. The library side generates all three
next to each provenance file; landed in `vql-engine` ci-build.yml, pending
backport to `metalog-distribution` and `hdr-rng`. (2) The `.intoto.jsonl`
filename and the `cosign verify-blob-attestation` recipe in §3.2 are a
first-party convention, not a Central standard; any change to either must be
coordinated between the publishers and this plan.

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
  missing or non-verifying SLSA provenance file (`.intoto.jsonl`), or one
  whose attested identity differs from the pinned one; unsigned image rejected
  by the admission controller in a k3d test cluster; local (unsigned) image
  rejected.
- Full register suite green (all sbt tiers + BATS C/A/B — image pipeline
  changes are release-validation scope).
- **Mandatory `/security-review-deep` on the implementation diff** (user
  ruling 2026-08-10) before landing.

## 7. CI pipeline (scope widening, user directive 2026-08-10)

No CI exists today (`.github/workflows/` is absent) — the pipeline is
greenfield. The signing/verification design in §1–§6 is unchanged and
becomes the pipeline's release stage. Platform: GitHub Actions; image
registry: GitHub Container Registry (`ghcr.io`), push by digest.

All tool adoption in this section follows ADR-020: exact pins (GitHub
Actions pinned by commit SHA, not tag), 14-day cooldown, publisher trust
policy, and signature verification where the ecosystem supports it.

### 7.1 Pipeline stages (design targets for elevation)

1. **Build + test** — every sbt tier (`commonJVM`/`server`/`app` unit,
   `serverIt` integration) on every push; BATS suites on release.
2. **Static analysis** — SAST + code style (tool baseline §7.3; final
   selection: open decision §7.6-3). Includes `scalafmtCheckAll` (config
   already in-repo) and the custom project rule pack (§7.4).
3. **Dependency + secrets scanning** — `scalacenter/sbt-dependency-submission`
   feeds the GitHub dependency graph so Dependabot alerts cover Maven
   dependencies (free on private repos); `npm audit` + `npm audit
   signatures` for `modules/app` (already the local procedure, CI-ified);
   gitleaks for committed secrets.
4. **Image build + container scanning** — build the four images; scan with
   Trivy (image CVEs, Dockerfile/compose misconfigurations, embedded
   secrets); SBOM generation (CycloneDX via Trivy) feeding the §3.3
   provenance attestation.
5. **Publish + sign** — push images to `ghcr.io` by digest, keyless
   `cosign sign` + SLSA provenance `cosign attest` (§3.3 row 3). Gated by
   the release authorization controls in §7.5.
6. **Verification gates** — the §3.3 verify script at dependency resolution
   (Maven subjects) and pre-deploy (image subjects).

Reporting constraint: GitHub's native code-scanning/security tab requires
GitHub Advanced Security on private repositories — findings therefore
surface as CI logs/artifacts (SARIF kept as a build artifact), not the
security tab. Dependabot alerts and the dependency graph are free and do
appear natively.

### 7.2 What belongs in static analysis here (threat-model fit)

This codebase's guarantees are mostly type-level (Iron refinements,
smart constructors, validate-at-the-boundary, sealed hierarchies with
exhaustivity promoted to errors). Generic taint-style SAST has limited
purchase — there is no SQL, string-built queries are already a review
red flag, and the injection surface is the FOL/GraphQL boundary already
governed by typed codecs. The highest-value static checks are therefore
**project-specific rules mechanizing the ADR negative constraints**
(adr-constraints skill ❌-list): banned constructs that today rely on
review memory. Candidate rule pack:

- `Revision.Head(BranchRef.Main)` literal in `modules/server/**/services/`
  (the §C1 branch-identity invariant guard, §7.4)
- `scala.util.Random` anywhere token/ID entropy is produced (ADR-021)
- `case class` credential types (ADR-022)
- `catch` of `scala.util.control.NonFatal` (ADR-033)
- `.now()` inside `modules/app` rendering pipelines (ADR-019)
- `asInstanceOf` / `Schema.any` outside sanctioned interop files (G2 #2)

### 7.3 Tool baseline (assessed 2026-08-10; selection = open decision §7.6-3)

Assessment of the candidate list (external agent output, corrected):

| Tool | Verdict | Basis |
|---|---|---|
| Compiler flags | **Adopt** (hardening scope: §7.6-4) | Cheapest, highest signal. Current set lacks `-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`; warnings-as-errors in CI. |
| scalafmt check | **Adopt** | `.scalafmt.conf` already in-repo; `scalafmtCheckAll` is a zero-design CI step. |
| Semgrep CE | **Adopt as custom-rule engine** | Engine LGPL; polyglot (Scala + Dockerfile + YAML + JS in one tool); no build coupling. Caveats vs. the agent claim: registry Scala rules are thin, cross-file taint analysis is the paid tier — the free value here is OUR rule pack (§7.2), not out-of-box vulnerability detection. Registry rules carry the Semgrep Rules License (internal CI use permitted). |
| Scalafix | **Evaluate** (rides §7.5-3) | Scala 3 support good; semantic (symbol-accurate) custom rules; sbt-integrated so devs get it locally. Cost: SemanticDB compilation overhead + a rules sub-project to maintain. |
| WartRemover | **Skip initially** | Agent claim "limited Scala 3 support" is outdated (Scala 3 plugin exists; some warts unported). Skipped because its useful warts overlap the hardened compiler flags + §7.2 rule pack; a compiler plugin taxes every compile. Revisit if a wart with no equivalent emerges. |
| Scalastyle | **Reject** | Dormant project, no Scala 3 dialect — non-starter on 3.7.4. |
| SonarQube CE | **Reject for now** | Requires a persistent server+DB; SonarSource's Scala analyzer (SLang) is shallow; the deeper community plugin (sonar-scala) is archived, no Scala 3; Scala taint analysis is not in CE. Revisit only if multi-repo governance dashboards become a need. |
| Trivy | **Adopt** (fills the agent output's container gap) | Apache-2.0; image CVE + misconfig + secret scanning + CycloneDX SBOM in one tool; standard in ghcr pipelines. Grype is the fallback if Trivy disappoints. |
| gitleaks | **Adopt** (fills the secrets gap) | MIT; CI + optional pre-commit; replaces the GHAS-gated native secret scanning. |
| sbt-dependency-submission | **Adopt** (fills the sbt dependency-alert gap) | Scala Center, Apache-2.0; the only maintained route to Dependabot alerts for sbt (OWASP dependency-check's sbt plugin is stale). |

The agent output's "~60–70% of commercial SAST coverage" figure is
unverifiable and not load-bearing here; the selection above stands on the
per-tool facts.

### 7.4 Custom-rule obligation: branch-identity invariant guard

The §C1 fix (DONE-PLAN-PHASE-E-HISTORY.md, Continuation §C1) removes the last
hardcoded `Revision.Head(BranchRef.Main)` read from the service write path.
The guard that keeps the bug class out — no constant branch coordinate in
per-branch service operations — is implemented as a custom rule in the
§7.6-3 mechanism (Semgrep pattern or Scalafix semantic rule), scoped to
`modules/server/**/services/` with an explicit allow-list for the services
whose business IS main (e.g. `ScenarioMergeService`). A unit test asserting
the same by text search was considered and rejected: the rule engine gives
the same regression protection without a brittle self-grepping spec.

### 7.5 Release authorization gate (ruled 2026-08-12)

The publish + sign stage (§7.1 stage 5) requires three independent
human-authorization controls, layered so each covers the others' bypass
path:

1. **TOTP possession proof.** The release workflow is `workflow_dispatch`
   with a `totp_code` input, verified against the org-level secret
   `TOTP_ORG` (oathtool, 60-second period) before any publish step runs —
   the same gate the three library repos use. The matching credential is a
   YubiKey OATH slot, so triggering a release proves hardware possession,
   independent of GitHub account credentials.
2. **GitHub environment protection.** The publish job runs in a protected
   environment (required reviewer), so a workflow edit that removes the
   TOTP check cannot reach the publish credentials without a second
   account-level approval.
3. **Signed release tag precondition** (library Decision 9 pattern,
   hdr-rng `PLAN-SIGSTORE-EXPECTATIONS.md`). The maintainer creates the
   release tag locally, signed with the hardware-backed SSH key
   (`git tag -s vX.Y.Z <commit>`), and pushes it. The release workflow
   does not create tags; it asserts the pushed tag exists, points at the
   exact commit being released, and verifies the tag signature with
   `git tag -v` against a committed `allowed_signers` file — failing
   closed before any image is pushed. A full CI + org-secret compromise
   therefore still cannot publish without the human-signed tag.

Coherence note for elevation: the §3.1 image identity pins
`release-images.yml@refs/tags/*`, so the release workflow must run on the
tag ref (dispatch with `--ref vX.Y.Z`) — the signed tag is then both the
workflow's ref (giving the pinned identity) and the verified
human-approval artifact. The TOTP gate authorizes *runs*; it is not a
substitute for artifact verification (§3) or the admission policy (§3.4),
and per-push CI stages run unattended without it.

### 7.6 Open decisions (additions to §5)

3. **Custom-rule mechanism** for §7.2/§7.4: (A) Semgrep CE only —
   polyglot, no build coupling, textual matching (an aliased import could
   evade a pattern; realistic risk low in reviewed code). (B) Scalafix
   only — symbol-accurate, runs locally via sbt, Scala-only (Dockerfile/
   YAML rules need a second tool anyway). (C) Both — Semgrep for breadth,
   Scalafix for the rules needing semantic resolution; two rule sets to
   maintain. Recommendation: **A** to start; add Scalafix only when a rule
   demonstrably needs symbol accuracy.
4. **Compiler-flag hardening scope**: adopt `-Wunused:all`,
   `-Wvalue-discard`, `-Wnonunit-statement` + CI-only warnings-as-errors —
   surfacing warnings in the existing codebase means a one-time cleanup
   pass whose size is unknown until the flags are tried. Decide: flags now
   with the cleanup as part of the CI plan's landing, or flags behind a
   non-failing CI report first.
5. **Pipeline trigger granularity**: which stages run per-push vs.
   per-release (serverIt needs the Irmin image in CI; BATS needs the full
   image set — both are cost/latency trade-offs). Shape at elevation.

## File inventory

(To be completed at implementation-grade elevation — expected: policy file,
verify script, GitHub Actions workflow(s), admission-controller manifests,
Semgrep/Scalafix rule pack under `security/` or `.semgrep/`,
ADR-020 §12 update, supply-chain skill update, VERSION-UPGRADE-PROTOCOL.md
update, docs/dev/TODO.md item-39 closure.)
