#!/usr/bin/env bash
# ============================================================================
# Irmin Content Hash Behaviour Test
# ============================================================================
# Validates whether Irmin's content hashes are usable as cache keys for
# the content-addressed caching strategy (DD-3, State B).
#
# Prerequisites: Irmin container running on localhost:9080
# ============================================================================

set -euo pipefail

IRMIN="http://localhost:9080/graphql"
P="ht$(date +%s)"
PASS=0; FAIL=0

gql() { curl -sf "$IRMIN" -H 'Content-Type: application/json' -d "$1"; }

jq_extract() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

assert_eq() {
  if [[ "$2" == "$3" ]]; then echo "  PASS: $1"; PASS=$((PASS+1))
  else echo "  FAIL: $1 (expected=$2, actual=$3)"; FAIL=$((FAIL+1)); fi
}
assert_ne() {
  if [[ "$2" != "$3" ]]; then echo "  PASS: $1"; PASS=$((PASS+1))
  else echo "  FAIL: $1 (both=$2)"; FAIL=$((FAIL+1)); fi
}

# Cleanup on exit
cleanup() {
  echo ""; echo "--- Cleanup ---"
  gql '{"query":"mutation { remove(path: \"'$P'\", info: {message: \"clean\"}) { hash } }"}' > /dev/null 2>&1 || true
  gql '{"query":"mutation { remove(path: \"'$P'\", branch: \"'$P'-br\", info: {message: \"clean\"}) { hash } }"}' > /dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=== Irmin Content Hash Behaviour Tests ==="
echo "    prefix: $P"

# ---- Write test data -------------------------------------------------------
echo ""
echo "--- Setup: Writing test data ---"

# Same value at two paths on main
gql '{"query":"mutation { set(path: \"'$P'/a\", value: \"{\\\"prob\\\":0.3,\\\"type\\\":\\\"lognormal\\\"}\", info: {message: \"t1\"}) { hash } }"}' > /dev/null
gql '{"query":"mutation { set(path: \"'$P'/b\", value: \"{\\\"prob\\\":0.3,\\\"type\\\":\\\"lognormal\\\"}\", info: {message: \"t2\"}) { hash } }"}' > /dev/null

# Same value on a branch
gql '{"query":"mutation { set(path: \"'$P'/a\", value: \"{\\\"prob\\\":0.3,\\\"type\\\":\\\"lognormal\\\"}\", branch: \"'$P'-br\", info: {message: \"t3\"}) { hash } }"}' > /dev/null

# Different value
gql '{"query":"mutation { set(path: \"'$P'/c\", value: \"{\\\"prob\\\":0.6,\\\"type\\\":\\\"lognormal\\\"}\", info: {message: \"t4\"}) { hash } }"}' > /dev/null

# Timestamp-embedded values (simulating metadata in node JSON)
gql '{"query":"mutation { set(path: \"'$P'/ts1\", value: \"{\\\"prob\\\":0.3,\\\"updatedAt\\\":\\\"2026-01-01T00:00:00Z\\\"}\", info: {message: \"t5\"}) { hash } }"}' > /dev/null
gql '{"query":"mutation { set(path: \"'$P'/ts2\", value: \"{\\\"prob\\\":0.3,\\\"updatedAt\\\":\\\"2026-03-13T12:00:00Z\\\"}\", info: {message: \"t6\"}) { hash } }"}' > /dev/null

# Separated storage: params and meta at separate paths
gql '{"query":"mutation { set(path: \"'$P'/sep/n1/params\", value: \"{\\\"prob\\\":0.3,\\\"type\\\":\\\"lognormal\\\"}\", info: {message: \"t7\"}) { hash } }"}' > /dev/null
gql '{"query":"mutation { set(path: \"'$P'/sep/n1/meta\", value: \"{\\\"updatedAt\\\":\\\"2026-01-01\\\"}\", info: {message: \"t8\"}) { hash } }"}' > /dev/null
gql '{"query":"mutation { set(path: \"'$P'/sep/n2/params\", value: \"{\\\"prob\\\":0.3,\\\"type\\\":\\\"lognormal\\\"}\", info: {message: \"t9\"}) { hash } }"}' > /dev/null
gql '{"query":"mutation { set(path: \"'$P'/sep/n2/meta\", value: \"{\\\"updatedAt\\\":\\\"2026-03-13\\\"}\", info: {message: \"t10\"}) { hash } }"}' > /dev/null

# Float precision test
gql '{"query":"mutation { set(path: \"'$P'/fp\", value: \"{\\\"val\\\":0.1}\", info: {message: \"t11\"}) { hash } }"}' > /dev/null

echo "  Done."

# ---- Read hashes -----------------------------------------------------------
echo ""
echo "--- Reading hashes ---"

H_A=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/a\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_B=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/b\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_BR=$(gql '{"query":"{ branch(name: \"'$P'-br\") { tree { get_contents(path: \"'$P'/a\") { hash } } } }"}' | jq_extract "d['data']['branch']['tree']['get_contents']['hash']")
H_C=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/c\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_TS1=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/ts1\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_TS2=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/ts2\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_P1=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/sep/n1/params\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_P2=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/sep/n2/params\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_M1=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/sep/n1/meta\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_M2=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/sep/n2/meta\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_CQ=$(gql '{"query":"{ contents_hash(value: \"{\\\"prob\\\":0.3,\\\"type\\\":\\\"lognormal\\\"}\") }"}' | jq_extract "d['data']['contents_hash']")

# Tree-level hashes
H_T1=$(gql '{"query":"{ main { tree { get_tree(path: \"'$P'/sep/n1\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_tree']['hash']")
H_T2=$(gql '{"query":"{ main { tree { get_tree(path: \"'$P'/sep/n2\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_tree']['hash']")

# FP roundtrip
FP_VAL=$(gql '{"query":"{ main { tree { get(path: \"'$P'/fp\") } } }"}' | jq_extract "d['data']['main']['tree']['get']")
H_FP=$(gql '{"query":"{ main { tree { get_contents(path: \"'$P'/fp\") { hash } } } }"}' | jq_extract "d['data']['main']['tree']['get_contents']['hash']")
H_FPQ=$(gql '{"query":"{ contents_hash(value: \"{\\\"val\\\":0.1}\") }"}' | jq_extract "d['data']['contents_hash']")

# ---- Tests -----------------------------------------------------------------
echo ""
echo "=== Test 1: Same value at different paths -> same hash ==="
assert_eq "path-a hash == path-b hash" "$H_A" "$H_B"
echo "    hash: $H_A"

echo ""
echo "=== Test 2: Same value on different branches -> same hash ==="
assert_eq "main hash == branch hash" "$H_A" "$H_BR"
echo "    main:   $H_A"
echo "    branch: $H_BR"

echo ""
echo "=== Test 3: Different value -> different hash ==="
assert_ne "prob=0.3 hash != prob=0.6 hash" "$H_A" "$H_C"
echo "    0.3: $H_A"
echo "    0.6: $H_C"

echo ""
echo "=== Test 4: Embedded timestamp busts hash ==="
assert_ne "ts1 hash != ts2 hash" "$H_TS1" "$H_TS2"
echo "    ts=2026-01: $H_TS1"
echo "    ts=2026-03: $H_TS2"
echo "    CONCLUSION: metadata inside the value changes the hash"

echo ""
echo "=== Test 5: Separated storage -- params hash stable across nodes ==="
assert_eq "node1/params hash == node2/params hash" "$H_P1" "$H_P2"
assert_ne "node1/meta hash != node2/meta hash" "$H_M1" "$H_M2"
echo "    params hash: $H_P1 (shared!)"
echo "    meta1 hash:  $H_M1"
echo "    meta2 hash:  $H_M2"
echo "    CONCLUSION: separating params from meta gives stable cache keys"

echo ""
echo "=== Test 6: contents_hash query -- offline hash computation ==="
assert_eq "contents_hash(value) == stored content hash" "$H_A" "$H_CQ"
echo "    stored:        $H_A"
echo "    contents_hash: $H_CQ"
echo "    CONCLUSION: can compute hash without writing to Irmin"

echo ""
echo "=== Test 7: Tree-level hash -- subtree identity ==="
assert_ne "node1 subtree != node2 subtree (different meta)" "$H_T1" "$H_T2"
echo "    node1 tree: $H_T1"
echo "    node2 tree: $H_T2"
echo "    CONCLUSION: Tree.hash covers full subtree including meta"

echo ""
echo "=== Test 8: Float precision roundtrip ==="
echo "    sent:     {\"val\":0.1}"
echo "    received: $FP_VAL"
assert_eq "stored hash matches computed hash for 0.1" "$H_FP" "$H_FPQ"
echo "    CONCLUSION: Irmin stores values as opaque strings, no float rewrite"

# ---- Summary ---------------------------------------------------------------
echo ""
echo "================================================================"
echo "  Passed: $PASS / $((PASS + FAIL))"
echo "  Failed: $FAIL"
echo "================================================================"
echo ""

if [[ $FAIL -gt 0 ]]; then
  echo "SOME TESTS FAILED"
  exit 1
else
  echo "ALL TESTS PASSED"
  echo ""
  echo "Key findings:"
  echo "  1. Content hashes are path-independent (same value = same hash regardless of path)"
  echo "  2. Content hashes are branch-independent (cross-branch sharing works!)"
  echo "  3. contents_hash query allows hash computation without writing"
  echo "  4. Metadata embedded in values DOES change the hash"
  echo "  5. Separated params/meta storage gives stable cache-key hashes"
  echo "  6. Tree.hash covers full subtree (not usable as node-level cache key)"
  echo "  7. Float values are stored as-is (opaque strings, no rewrite)"
fi
