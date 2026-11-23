#!/usr/bin/env bash
# Times simulation on the server container and prints one table.
#
#   tests/perf/measure-simulation.sh                 default grid, table on stdout
#   tests/perf/measure-simulation.sh results.tsv     the same, also written to a file
#
# The server ships as a GraalVM native image, so the container is what is
# measured: timings taken from an sbt test run on the JVM do not carry over.
# The repository is the in-memory one, so the store takes no part in a timing.
#
# One configuration is a trial count and a trial parallelism. For each one the
# script starts a fresh server and, for every tree size, repeats four timed
# requests:
#
#   cold        the root's loss exceedance curve with nothing cached, which
#               simulates every leaf
#   cached      the same request again, which simulates nothing
#   edit-write  a tree update that changes one leaf
#   edit-read   the root's curve after that update, which simulates one leaf
#
# Each row gives the median, minimum and maximum over the repetitions, in
# seconds as curl measures the whole request. After the rows of a
# configuration come three figures for it: the mean time the server recorded
# per simulated leaf, the server's peak memory, and the processor time it used.
# The per-leaf time is elapsed time while other leaves of the same tree are
# being simulated alongside, so it is not what one leaf costs alone. A run with
# PERF_SIZES=1 gives that.
#
# The grid is set through the environment:
#
#   PERF_SIZES         leaves per tree            default "10 200 500"
#   PERF_TRIALS        trials per leaf            default "10000 100000 1000000"
#   PERF_PARALLELISMS  fibers per leaf            default "1 8"
#   PERF_REPS          repetitions per tree size  default 5
#   PERF_CPUS          processors the server may use, default every processor
#   PERF_MEMORY        the server's memory limit, default 4G
#
# Requires: docker with compose, curl, jq, and the local/register-server image
# of the current version. The image is never built here.
set -euo pipefail

cd "$(dirname "$0")/../.."

SIZES=${PERF_SIZES:-"10 200 500"}
TRIALS=${PERF_TRIALS:-"10000 100000 1000000"}
PARALLELISMS=${PERF_PARALLELISMS:-"1 8"}
REPS=${PERF_REPS:-5}
export PERF_CPUS=${PERF_CPUS:-$(nproc)}
export PERF_MEMORY=${PERF_MEMORY:-4G}
OUT=${1:-}

BASE=http://localhost:8090
METRICS=http://localhost:8889/metrics
# Leaves under one portfolio. Chosen here so that the tree has two levels of
# aggregation at every size in the default grid.
LEAVES_PER_PORTFOLIO=10
# Longest a single request may take before it counts as failed. Chosen here to
# sit above the slowest cell of the default grid.
REQUEST_TIMEOUT_S=900
# Longest wait for the server's metrics to reach the collector. The server
# exports every 60 seconds and the collector batches for 10 more.
METRICS_TIMEOUT_S=150

for cmd in docker curl jq; do
  command -v "$cmd" >/dev/null || { echo "required tool not found: $cmd" >&2; exit 1; }
done

compose() {
  docker compose -p register_perf -f docker-compose.yml -f tests/perf/compose.perf.yml \
    --profile observability "$@"
}

work=$(mktemp -d)
cleanup() {
  compose down -v >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

emit() {
  printf '%s\n' "$1"
  if [ -n "$OUT" ]; then printf '%s\n' "$1" >> "$OUT"; fi
}

# The request body that creates a tree of $1 leaves. Every leaf differs from
# every other leaf of every tree the script creates, through $2, so that no
# leaf is answered from a result cached for a leaf with the same content.
tree_body() {
  jq -n --argjson n "$1" --argjson salt "$2" --argjson per "$LEAVES_PER_PORTFOLIO" '
    (($n + $per - 1) / $per | floor) as $portfolios
    | { name: "perf-\($n)-\($salt)",
        portfolios: ([{name: "Root", parentName: null}]
                     + [range(1; $portfolios + 1) | {name: "P\(.)", parentName: "Root"}]),
        leaves: [range(0; $n) | {
          name: "L\(. + 1)",
          parentName: "P\((. / $per | floor) + 1)",
          probability: 0.1,
          distributionShape: {
            distributionType: "Lognormal",
            minLoss: (1000 + .),
            maxLoss: (1000000 + $salt * 100000 + .)
          }
        }],
        newMitigations: [] }'
}

# The update body for the stored tree on stdin: every node as stored, with the
# first leaf's lower bound moved by one.
update_body() {
  jq -c '
    (.nodes | map(.RiskPortfolio // empty) | map({key: .id, value: .name}) | from_entries) as $names
    | .rootId as $root
    | { name: .name,
        portfolios: [.nodes[] | .RiskPortfolio // empty
                     | {id, name, parentName: (if .id == $root then null else $names[$root] end)}],
        leaves: [.nodes[] | .RiskLeaf // empty
                 | {id, name, parentName: $names[.parentId], probability,
                    distributionShape: (if .name == "L1" then .shape | .minLoss += 1 else .shape end),
                    seedVarId: null}],
        newPortfolios: [],
        newLeaves: [],
        mitigations: [],
        newMitigations: [] }'
}

# Runs curl with the given arguments and prints the request's seconds. A
# status other than 200 fails, with the status and the body on stderr.
timed() {
  local result status seconds
  # curl leaves the output file untouched when no response arrives, so an
  # earlier response is removed before it can be reported as this one's.
  rm -f "$work/body"
  result=$(curl -s -o "$work/body" -w '%{http_code} %{time_total}' --max-time "$REQUEST_TIMEOUT_S" "$@") || result="000 0"
  status=${result% *}
  seconds=${result#* }
  if [ "$status" != 200 ]; then
    echo "request failed with status $status: $(head -c 300 "$work/body" 2>/dev/null)" >&2
    return 1
  fi
  printf '%s\n' "$seconds"
}

# Median, minimum and maximum of the numbers in file $1, tab-separated.
summary() {
  sort -n "$1" | awk '{ v[NR] = $1 }
    END { m = (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2
          printf "%.4f\t%.4f\t%.4f", m, v[1], v[NR] }'
}

# Sum over every series of the metric whose name ends in $1.
metric_total() {
  curl -s "$METRICS" | awk -v suffix="$1" '
    $1 ~ ("simulation_duration.*" suffix "[{ ]") || $1 ~ ("simulation_duration.*" suffix "$") { total += $NF }
    END { printf "%d", total }'
}

cgroup_file() {
  local id
  id=$(docker inspect -f '{{.Id}}' register-server)
  printf '/sys/fs/cgroup/system.slice/docker-%s.scope/%s' "$id" "$1"
}

app_version=$(sed -n 's/^APP_VERSION=//p' .env)
docker image inspect "local/register-server:$app_version" >/dev/null 2>&1 \
  || { echo "image local/register-server:$app_version is missing; build it first" >&2; exit 1; }
if curl -s -o /dev/null "$BASE/health"; then
  echo "something already answers on $BASE; stop it first" >&2
  exit 1
fi

if [ -n "$OUT" ]; then : > "$OUT"; fi
emit "# $(date -u +%Y-%m-%dT%H:%M:%SZ)  image local/register-server:$app_version  host $(nproc) processors"
emit "# server limits: $PERF_CPUS processors, $PERF_MEMORY memory; repetitions per cell: $REPS"
emit "$(printf 'trials\tparallelism\tleaves\tcase\tmedian_s\tmin_s\tmax_s')"

salt=0
for trials in $TRIALS; do
  for parallelism in $PARALLELISMS; do
    export PERF_NTRIALS=$trials PERF_PARALLELISM=$parallelism
    compose down -v >/dev/null 2>&1 || true
    compose up -d --no-build register-server otel-collector >/dev/null 2>&1
    until curl -s -o /dev/null "$BASE/health"; do sleep 1; done

    simulated=0
    for size in $SIZES; do
      rm -f "$work"/t.*
      completed=yes
      for _ in $(seq "$REPS"); do
        salt=$((salt + 1))
        tree_body "$size" "$salt" > "$work/create.json"
        if ! curl -s --max-time "$REQUEST_TIMEOUT_S" -X POST "$BASE/workspaces" \
               -H 'Content-Type: application/json' -d @"$work/create.json" > "$work/bootstrap.json" \
           || ! jq -e '.workspaceKey and .tree.id' "$work/bootstrap.json" >/dev/null; then
          echo "tree of $size leaves was not created: $(head -c 300 "$work/bootstrap.json")" >&2
          completed=no; break
        fi
        tree="$BASE/w/$(jq -r .workspaceKey "$work/bootstrap.json")/risk-trees/$(jq -r .tree.id "$work/bootstrap.json")"
        curl -s -H 'X-Branch: main' "$tree/structure" | jq '.tree' > "$work/structure.json"
        jq -c '{valuations: [{selection: {kind: "Inherent"}, nodeIds: [.rootId]}]}' "$work/structure.json" > "$work/root.json"
        update_body < "$work/structure.json" > "$work/update.json"

        curve=(-X POST "$tree/nodes/lec-multi" -H 'X-Branch: main' -H 'Content-Type: application/json' -d @"$work/root.json")
        update=(-X PUT "$tree" -H 'X-Branch: main' -H 'Content-Type: application/json' -d @"$work/update.json")

        timed "${curve[@]}"  >> "$work/t.cold"       || { completed=no; break; }
        timed "${curve[@]}"  >> "$work/t.cached"     || { completed=no; break; }
        timed "${update[@]}" >> "$work/t.edit-write" || { completed=no; break; }
        timed "${curve[@]}"  >> "$work/t.edit-read"  || { completed=no; break; }
        simulated=$((simulated + size + 1))
      done

      if [ "$completed" = yes ]; then
        for case in cold cached edit-write edit-read; do
          emit "$(printf '%s\t%s\t%s\t%s\t%s' "$trials" "$parallelism" "$size" "$case" "$(summary "$work/t.$case")")"
        done
      else
        emit "$(printf '%s\t%s\t%s\tfailed' "$trials" "$parallelism" "$size")"
        # docker-compose.yml restarts the server when it exits, which resets
        # the container's state and its cgroup counters. The restart count and
        # the log are what still show that the process ended and why.
        restarts=$(docker inspect -f '{{.RestartCount}}' register-server 2>/dev/null || echo unknown)
        emit "# the server process ended and was restarted $restarts time(s) during this configuration; the memory and processor figures below are the restarted process's"
        emit "# last server log lines before the failure was noticed:"
        docker logs register-server 2>&1 | grep -v 'opentelemetry\|cardinality\|Too many log messages' | tail -12 \
          | cut -c1-300 | while IFS= read -r line; do emit "#   $line"; done
        break
      fi
    done

    # The server's own per-leaf timing, once every simulation of this
    # configuration has reached the collector.
    waited=0
    until [ "$(metric_total _count)" -ge "$simulated" ] || [ "$waited" -ge "$METRICS_TIMEOUT_S" ]; do
      sleep 2; waited=$((waited + 2))
    done
    count=$(metric_total _count)
    if [ "$count" -gt 0 ] && [ "$count" -ge "$simulated" ]; then
      per_leaf=$(awk -v s="$(metric_total _sum)" -v c="$count" 'BEGIN { printf "%.2f ms over %d leaves", s / c, c }')
    else
      per_leaf="not available: $count of $simulated simulations reached the collector"
    fi
    peak=$(awk '{ printf "%.0f MiB", $1 / 1048576 }' "$(cgroup_file memory.peak)" 2>/dev/null || echo "not available")
    cpu=$(awk '$1 == "usage_usec" { printf "%.1f s", $2 / 1000000 }' "$(cgroup_file cpu.stat)" 2>/dev/null || echo "not available")
    emit "# trials=$trials parallelism=$parallelism: mean per simulated leaf $per_leaf; peak memory $peak; processor time $cpu"
  done
done
