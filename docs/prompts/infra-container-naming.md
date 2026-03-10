# Agent Prompt: Container Image Naming Convention Update for register-infra

## Task

The Risk Register project (`~/projects/register`) has reorganised its
Dockerfiles and image naming conventions per ADR-026. Your task: find and
update all references to the **old** Dockerfile paths and image names in
`~/projects/register-infra` (the Hetzner Cloud deployment infrastructure
project).

## What Changed

### Folder Structure

All Dockerfiles moved from the project root / `dev/` into a `containers/`
subtree:

```
containers/
├── builders/
│   ├── Dockerfile.graalvm-builder   # was: dev/Dockerfile.graalvm-builder
│   └── Dockerfile.irmin-builder     # NEW — opam + irmin packages base
├── dev/
│   ├── Dockerfile.register-dev      # was: Dockerfile (root)
│   └── Dockerfile.irmin-dev         # was: dev/Dockerfile.irmin
└── prod/
    ├── Dockerfile.register-prod     # was: Dockerfile.native
    └── Dockerfile.irmin-prod        # NEW — slim Alpine runtime (~87 MB)
```

### Image Names

| Old Name | New Name | Notes |
|----------|----------|-------|
| `local/graalvm-builder:21` | `local/graalvm-builder:21` | **Unchanged** |
| `local/irmin:3.11` | `local/irmin-dev:3.11` | Dev image (full toolchain) |
| _(none)_ | `local/irmin-builder:3.11` | NEW — opam packages base |
| _(none)_ | `local/irmin-prod:3.11` | NEW — slim Alpine runtime |
| `register-server:latest` | `register-server:prod` | Tag changed from `latest` to `prod` |

### Naming Convention

`Dockerfile.{service}-{tier}` where:
- **service** = `register` | `irmin` | `graalvm`
- **tier** = `builder` | `dev` | `prod`

Image tags encode the toolchain/runtime version (e.g. `:3.11` for OCaml,
`:21` for GraalVM).

## Search Patterns

Grep the infra project for these patterns to find all references that need
updating:

```bash
grep -rn 'Dockerfile\.native\|Dockerfile\.irmin\b\|dev/Dockerfile' .
grep -rn 'local/irmin:3\.11\|register-server:latest' .
grep -rn 'irmin:3\.11' .
```

## What to Update

For each match:

1. **Dockerfile paths** — update to the new `containers/…` path.
2. **Image names/tags** — update per the table above.
3. **Build commands** — if the infra project contains any `docker build`
   commands, update the `-f` flag and context directory. For example:
   - `docker build -f Dockerfile.native -t register-server:latest .`
     → `docker build -f containers/prod/Dockerfile.register-prod -t register-server:prod .`
   - `docker build -f dev/Dockerfile.irmin -t local/irmin:3.11 dev/`
     → For **dev**: `docker build -f containers/dev/Dockerfile.irmin-dev -t local/irmin-dev:3.11 .`
     → For **prod**: first build the builder if not already present, then:
       `docker build -f containers/prod/Dockerfile.irmin-prod -t local/irmin-prod:3.11 containers/prod/`
4. **k3d / k8s manifests** — if any Deployment, Pod, or Job specs reference
   old image names, update them.

## New Images to Consider

The infra project may also need to:

- Add a build step for `local/irmin-builder:3.11` (one-time builder base)
- Add a build step for `local/irmin-prod:3.11` (production Irmin image)
- Use `local/irmin-prod:3.11` instead of `local/irmin-dev:3.11` in any
  production/staging k3d cluster definitions
- The prod Irmin image runs with `readOnlyRootFilesystem: true` and UID 65532
  — ensure any PodSecurityPolicy or SecurityContext settings match

## Constraints

- Do NOT change the register project files — only update register-infra.
- Preserve existing conventions in the infra project (indentation, comment
  style, etc.).
- If you find references you're unsure about, add a `# TODO(ADR-026):` comment
  rather than guessing.

## Reference

- `~/projects/register/docs/ADR-026-container-image-strategy.md` — full
  decision record with rationale
- `~/projects/register/docker-compose.yml` — canonical service definitions
