# File inventory — PLAN-SIGSTORE-VERIFICATION.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


(To be completed at implementation-grade elevation — expected: policy file,
verify script, GitHub Actions workflow(s), admission-controller manifests,
Semgrep/Scalafix rule pack under `security/` or `.semgrep/`,
ADR-020 §12 update, supply-chain skill update, VERSION-UPGRADE-PROTOCOL.md
update, docs/dev/TODO.md item-39 closure.)
