# Image Build Reference

Complete reference for every `docker build` command. See [Docker Development Guide](DOCKER-DEVELOPMENT.md) for compose use cases, integration test setup, and configuration reference. For day-to-day development, builder
bases are built once; application images are built automatically by `docker compose up`.

## Builder bases (manual, one-time)

These images install heavyweight toolchains and are used as `FROM` targets inside the
application Dockerfiles. Build once after cloning; rebuild only when the toolchain version
changes.

```bash
# GraalVM builder base — GraalVM native-image + sbt (~10-20 min, once)
# Rebuild when: GraalVM version changes, sbt version changes, or vql-engine SNAPSHOT bumps.
# Context: parent directory — sibling repos vague-quantifier-logic/ and hdr-rng/ must be at ../
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 ..

# Irmin builder base — OCaml toolchain + opam packages (~15-40 min, once)
# Rebuild when: Irmin version changes or OCaml version changes.
docker build -f containers/builders/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11 \
  containers/builders/
```

## Application images (versioned)

These are built automatically by `docker compose up` in local development. The commands
below are needed when building outside compose — for example, to build immutable images for
CI push workflows or to pre-build outside a compose context.

`APP_VERSION` is read from the `.env` file in the project root. Set it first if `.env`
is absent or out of date:

```bash
sed -n 's/ThisBuild \/ version := "\(.*\)"/APP_VERSION=\1/p' build.sbt > .env
source .env    # makes APP_VERSION available to the commands below
```

```bash
# Irmin production image — slim Alpine runtime (~10s)
# Requires: local/irmin-builder:3.11
docker build -f containers/prod/Dockerfile.irmin-prod \
  -t local/irmin-prod:3.11 \
  containers/prod/

# Register server — GraalVM native binary on distroless (~5-10 min)
# Requires: local/graalvm-builder:21
docker build -f containers/prod/Dockerfile.register-prod \
  -t local/register-server:${APP_VERSION} .

# Frontend SPA — Scala.js + nginx (~10-15 min first run)
# Context: parent directory — sibling repos must be at ../
docker build -f containers/prod/Dockerfile.frontend-prod \
  -t local/frontend:${APP_VERSION} ..

# Verify all images
docker images | grep -E 'irmin|register|graalvm|frontend'
```

## Rebuild triggers

| Image | Rebuild when |
|-------|-------------|
| `local/graalvm-builder:21` | GraalVM/sbt version bump; vql-engine SNAPSHOT bump |
| `local/irmin-builder:3.11` | OCaml or Irmin version change |
| `local/register-server:*` | Server or common source changes — `docker compose up` handles this automatically |
| `local/frontend:*` | Frontend or common source changes — `docker compose up` handles this automatically |
| `local/irmin-prod:3.11` | Irmin version change (requires irmin-builder rebuild first) |

## After a vql-engine change (graalvm-builder rebuild required)

```bash
docker build -f containers/builders/Dockerfile.graalvm-builder -t local/graalvm-builder:21 .. \
  && docker compose up -d register-server
```

## CI usage

In CI, build application images explicitly first, then start compose with `--no-build` to
use those images without triggering another build:

```bash
# 1. Sync version from build.sbt
APP_VERSION=$(sed -n 's/ThisBuild \/ version := "\(.*\)"/\1/p' build.sbt)
echo "APP_VERSION=${APP_VERSION}" > .env

# 2. Build builder base (use CI cache)
docker build -f containers/builders/Dockerfile.graalvm-builder -t local/graalvm-builder:21 ..

# 3. Build application images (compose builds them with the version from .env)
docker compose build

# 4. Push to registry (re-tag to registry prefix)
docker tag local/register-server:${APP_VERSION} ghcr.io/risquanter/register-server:${APP_VERSION}
docker push ghcr.io/risquanter/register-server:${APP_VERSION}

# 5. Run integration tests using the images just built (--no-build prevents a second build)
docker compose --profile persistence up -d --no-build
sbt "serverIt/test"
docker compose --profile persistence down
```
