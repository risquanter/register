---
name: supply-chain
description: "Supply-chain and versioning rules for the register project. Load before: adding or updating any dependency (npm, sbt, opam, apk, Docker base image, fetched binary), bumping the project version, or preparing a release. Covers: version pinning, 14-day cooldown, trust policy, signature verification, bump scheme and ownership."
---

# Supply Chain & Versioning — Register

Policy source: `docs/dev/ADR-020-supply-chain-security.md` (§1–§12).
Per-ecosystem map (what is pinned where, what to rebuild):
`docs/dev/VERSION-UPGRADE-PROTOCOL.md`. Commands: register-dev skill.

## Hard rules — every dependency change

1. **Pin exactly.** Every version in every ecosystem is exact — no ranges,
   no `latest`, no unpinned installs (ADR-020 §1).
2. **14-day cooldown.** Do not adopt a version published less than 14 days
   ago; take the newest version older than that instead. **Waiver:** a
   version fixing a disclosed vulnerability that affects this project is
   adopted immediately (ADR-020 §10). Publish-date checks:
   - npm: `npm view <pkg>@<version> time`
   - Maven Central: artifact page on central.sonatype.com
   - opam: version's commit date in `ocaml/opam-repository`
   - Docker/GitHub: registry tag or release date
3. **Trust policy.** Prefer packages from well-known organisations (the
   stack's existing vendors and comparable established publishers). A
   dependency from an individual or unestablished publisher needs
   **user approval first**, and the approval is recorded as a comment at the
   pin site in the build file — date, approved-by-user, reason
   (ADR-020 §11). No comment = not approved.
4. **Verify signatures where the ecosystem supports it**; prefer
   Sigstore/cosign over PGP (ADR-020 §12):
   - npm: `npm audit signatures` after every install (workflow: ADR-020 §8)
   - Docker base images: `cosign verify` where the publisher signs; digest
     pinning for release builds; record the check result at the pin site
   - sbt/Maven and opam have **no** usable verification — known gaps,
     already flagged to the user; do not silently accept a new ecosystem
     without checking its verification story, and notify the user when an
     ecosystem lacks one (a Sigstore-based fallback plan is the expected
     follow-up).
5. **npm is ask-first, always.** Never run `npm install`/`npm update`
   without explicit prior user authorization; once authorized, follow
   resolve → audit → install → audit → `npm audit signatures`
   (register-dev skill, ADR-020 §8).
6. **Checksums travel with versions.** Any `ARG <TOOL>_VERSION` bump in a
   Dockerfile updates its `ARG <TOOL>_SHA256` in the same change
   (ADR-020 §2).

## Version bump scheme (our own source)

`build.sbt` `ThisBuild / version` is the source of truth; mirror
`APP_VERSION` into **both** `.env` and `.env.irmin` on every bump.

| Change | Bump |
|---|---|
| Task/step in an approved plan; bug fix; security fix — shipped code changed | PATCH |
| Plan closed (feature level); external API change while < 1.0.0 | MINOR |
| External API change once ≥ 1.0.0; user-declared case or pre-declared milestone | MAJOR |
| Docs-only, test-only, skill/tooling-only (no shipped code) | No bump |

**Ownership:** PATCH and MINOR are autonomous — apply them when landing the
qualifying work. MAJOR is user-owned: never bump it autonomously; the user
performs or pre-declares it (e.g., a note at the end of a plan document —
milestone-2b completion is a pre-declared major).

## Procedure map

- Which file pins what, and what to rebuild after a change:
  `docs/dev/VERSION-UPGRADE-PROTOCOL.md` (ecosystem maps + rebuild matrix).
- Bump/sync/build/test commands: register-dev skill
  ("Versioning", "Container Image Builds", "BATS Smoke Tests").
- `build.sbt` is hook-gated: edits require the user-refreshed approval
  token (working-protocol skill).

## opam / Irmin — source compilation is mandatory

Irmin is compiled from source in the builder image so the project can carry a
local patch (`opam pin`) to upstream. This is not removable while a patch is
carried: **never switch Irmin to a prebuilt distribution**, and **every Irmin
version bump must re-apply and re-validate the patch** against the new source
(re-run the merge-conflict integration spec, not just a smoke build). The pin
is a documented trust exception (ADR-020 §11) — comment at the pin site.
Detail: `docs/dev/VERSION-UPGRADE-PROTOCOL.md`, opam section.
