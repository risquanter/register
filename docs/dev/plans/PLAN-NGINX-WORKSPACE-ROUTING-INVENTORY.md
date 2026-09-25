# File inventory — PLAN-NGINX-WORKSPACE-ROUTING.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


| Path | Change |
|---|---|
| `containers/prod/Dockerfile.frontend-prod` | the nginx template: the two `map` blocks, the `split_clients` placeholder, the `masked` log format, `access_log` using it; the entrypoint script generating the `split_clients` block from `BACKEND_INSTANCES` |
| `docker-compose.scale.yml` | new — a multi-instance override defining two or more server instances without `container_name` or fixed host ports, and setting `BACKEND_INSTANCES` on the frontend |
| `docs/user/DOCKER-DEVELOPMENT.md` | the multi-instance stack and how to run it; the note that the default stack is unchanged |
| `docs/dev/decision-records/ADR-021-capability-urls.md` | §4 amendment adding access logs to the enumerated leakage channels, with the masking as its closure |
| `docs/dev/decision-records/ADR-027-frontend-nginx-serving.md` | the routing rationale: why requests are workspace-affine and why `split_clients` rather than an upstream hash |
| `docs/dev/plans/PLAN-SSE-EVENT-ENUMS.md` | a note that Server-Sent Events require workspace affinity when more than one instance runs, so the constraint is recorded where the events work is |

No file under `modules/` is touched, so the source-edit approval gate does not
apply to this plan.

The ADR-021 amendment is delivered as a copy-pasteable block plus a verbatim
anchor line. An accepted decision record is amended, never rewritten.
