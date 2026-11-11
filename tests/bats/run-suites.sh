#!/usr/bin/env bash
# Builds the two application images one at a time, then runs the named BATS
# suites, each against a freshly cleared `register` compose stack.
#
#   tests/bats/run-suites.sh A          suite A only
#   tests/bats/run-suites.sh C A B      suites C, A and B, in that order
#   tests/bats/run-suites.sh all        every suite: C, then B, then A
#
# It first clears leaked serverIt stacks and the application images of earlier
# versions, since both accumulate on disk across runs.
#
# `all` runs the in-memory gate first because it is the quickest to fail, and
# the standalone Irmin image before the full stack that runs on it.
#
# It stops at the first failure, including a failed image build, so a suite
# never starts with an image missing. A missing image would make the suite's
# `docker compose up` build it while the stack starts, overlapping the build's
# memory with the stack's.
set -euo pipefail

cd "$(dirname "$0")/../.."

# Each serverIt spec starts its own `register_it_<id>` stack for Irmin or
# `register_it_authz_<id>` stack for SpiceDB. An interrupted run leaves the
# containers, networks and volumes behind. The name filter matches both, and
# images are kept, so nothing is rebuilt or pulled.
echo "==> clearing leaked register_it_ containers, networks and volumes"
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f
docker network ls --filter name=register_it_ -q | xargs -r docker network rm
docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm

# Every version bump tags a new pair of application images. Tags of other
# versions are removed. An image still used by a container is left in place
# and does not stop the run.
app_version=$(sed -n 's/^APP_VERSION=//p' .env)
: "${app_version:?APP_VERSION is missing from .env}"
echo "==> removing local/register-server and local/frontend tags other than $app_version"
docker images --format '{{.Repository}}:{{.Tag}}' \
  | { grep -E '^local/(register-server|frontend):' || true; } \
  | { grep -vxF -e "local/register-server:$app_version" -e "local/frontend:$app_version" || true; } \
  | xargs -r docker rmi \
  || echo "some earlier images are still used by a container and were left in place" >&2

SUITE_A=tests/bats/suite-a-full-prod.bats
SUITE_B=tests/bats/suite-b-irmin-prod.bats
SUITE_C=tests/bats/suite-c-in-memory.bats

suites=()
for arg in "$@"; do
  case "$arg" in
    A)   suites+=("$SUITE_A") ;;
    B)   suites+=("$SUITE_B") ;;
    C)   suites+=("$SUITE_C") ;;
    all) suites+=("$SUITE_C" "$SUITE_B" "$SUITE_A") ;;
    *)   echo "unknown argument: $arg (expected A, B, C or all)" >&2; exit 2 ;;
  esac
done
if [ "${#suites[@]}" -eq 0 ]; then
  echo "name at least one suite: A, B, C or all" >&2
  exit 2
fi

# The full log goes to the file. The screen shows each build step as it starts
# and finishes, native-image's `Peak RSS` line, and any error. The build's
# status is read from the first element of the pipeline, since grep finding no
# line is not a failure.
echo "==> building register-server (full log: /tmp/register-server-build.log)"
set +e
docker compose build --progress=plain register-server 2>&1 \
  | tee /tmp/register-server-build.log \
  | grep --line-buffered -E '^#[0-9]+ (\[|DONE)|Peak RSS|ERROR|^#[0-9]+ [0-9.]+ Error:'
build_status=${PIPESTATUS[0]}
set -e
if [ "$build_status" -ne 0 ]; then
  echo "register-server build failed; log in /tmp/register-server-build.log" >&2
  exit 1
fi
echo "==> building frontend"
docker compose --profile frontend build frontend

for suite in "${suites[@]}"; do
  echo "==> running $suite"
  docker compose -p register down -v --remove-orphans 2>/dev/null || true
  docker run --rm --userns=host --network host \
    --group-add "$(stat -c '%g' /var/run/docker.sock)" -e HOME=/tmp \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$(dirname "$(pwd)")":"$(dirname "$(pwd)")" -w "$(pwd)" \
    local/bats-runner:1.11 "$suite"
done
