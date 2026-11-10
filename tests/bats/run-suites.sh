#!/usr/bin/env bash
# Builds the two application images one at a time, then runs the named BATS
# suites, each against a freshly cleared `register` compose stack.
#
#   tests/bats/run-suites.sh A          suite A only
#   tests/bats/run-suites.sh C A B      suites C, A and B, in that order
#   tests/bats/run-suites.sh all        every suite: C, then B, then A
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
