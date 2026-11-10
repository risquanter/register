#!/usr/bin/env bash
# Builds the two application images one at a time, then runs the named BATS
# suites, each against a freshly cleared `register` compose stack.
#
#   tests/bats/run-suites.sh A          suite A only
#   tests/bats/run-suites.sh C A B      suites C, A and B, in that order
#   tests/bats/run-suites.sh --force A  run even while memory holders are up
#
# It stops at the first failure, including a failed image build, so a suite
# never starts with an image missing. A missing image would make the suite's
# `docker compose up` build it while the stack starts, which is the memory
# overlap this script exists to prevent.
#
# Before building, it refuses to start while another JVM or a container outside
# the `register` stack is running, and names each one. The server image's
# native-image step alone peaks near 11 GB of resident memory, and nothing caps
# a build's memory on this daemon, so what else is running decides whether the
# machine runs out.
set -euo pipefail

cd "$(dirname "$0")/../.."

force=false
suites=()
for arg in "$@"; do
  case "$arg" in
    --force) force=true ;;
    A) suites+=(tests/bats/suite-a-full-prod.bats) ;;
    B) suites+=(tests/bats/suite-b-irmin-prod.bats) ;;
    C) suites+=(tests/bats/suite-c-in-memory.bats) ;;
    *) echo "unknown argument: $arg (expected A, B, C or --force)" >&2; exit 2 ;;
  esac
done
if [ "${#suites[@]}" -eq 0 ]; then
  echo "name at least one suite: A, B or C" >&2
  exit 2
fi

jvms=$(pgrep -a java || true)
others=$(docker ps --format '{{.Names}} {{.Label "com.docker.compose.project"}}' | awk '$2 != "register" { print $1 }')
if [ -n "$jvms$others" ] && [ "$force" = false ]; then
  echo "Stop these before building, or pass --force:" >&2
  [ -n "$jvms" ] && echo "$jvms" | cut -c1-160 | sed 's/^/  JVM        /' >&2
  [ -n "$others" ] && echo "$others" | sed 's/^/  container  /' >&2
  exit 1
fi

# The build's status is read from the first element of the pipeline, since grep
# finding no line is not a failure.
set +e
docker compose build --progress=plain register-server 2>&1 | tee /tmp/register-server-build.log | grep -E "Peak RSS|error|Error"
build_status=${PIPESTATUS[0]}
set -e
if [ "$build_status" -ne 0 ]; then
  echo "register-server build failed; log in /tmp/register-server-build.log" >&2
  exit 1
fi
docker compose --profile frontend build frontend

for suite in "${suites[@]}"; do
  docker compose -p register down -v --remove-orphans 2>/dev/null || true
  docker run --rm --userns=host --network host \
    --group-add "$(stat -c '%g' /var/run/docker.sock)" -e HOME=/tmp \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$(dirname "$(pwd)")":"$(dirname "$(pwd)")" -w "$(pwd)" \
    local/bats-runner:1.11 "$suite"
done
