#!/usr/bin/env bash
# =============================================================================
# check-example-variants.sh — the curl and httpie variants of each demo script
# must send the same tree and issue the same queries.
#
# Each demo in examples/ exists twice, once per HTTP client. Only the transport
# may differ between the two. This script compares, for each demo:
#   - the bootstrap body: name, portfolios and leaves, as canonical JSON;
#   - everything after the run_query function: every query, label and comment.
# It prints the difference and exits non-zero when a pair has drifted.
#
# Requires: jq
#
# Usage:  tests/bats/check-example-variants.sh
# =============================================================================
set -euo pipefail

EXAMPLES="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../examples" && pwd)"
DEMOS=(simple enterprise)

# Everything after the closing brace of the run_query function.
query_section() {
  awk '/^run_query\(\) \{/ {inside=1} inside && /^}/ {after=1; next} after' "$1"
}

# The bootstrap body of a curl variant: the JSON document passed to -d.
curl_tree() {
  awk '/-d \047\{$/ {on=1; print "{"; next}
       on && /^  \}\047\)$/ {print "}"; exit}
       on' "$1" | jq -S '{name, portfolios, leaves}'
}

# One httpie JSON field (portfolios or leaves): the array between := and its
# closing bracket.
httpie_array() {
  awk -v field="$2" '$0 == "  " field ":=\047[" {on=1; print "["; next}
       on && /^  \]\047/ {print "]"; exit}
       on' "$1"
}

# The bootstrap body of an httpie variant, assembled from its three fields.
httpie_tree() {
  local name
  name=$(sed -n 's/^  name="\(.*\)" \\$/\1/p' "$1" | head -1)
  jq -n -S \
    --arg name "$name" \
    --argjson portfolios "$(httpie_array "$1" portfolios)" \
    --argjson leaves "$(httpie_array "$1" leaves)" \
    '{name: $name, portfolios: $portfolios, leaves: $leaves}'
}

status=0
for demo in "${DEMOS[@]}"; do
  curl_script="$EXAMPLES/demo-$demo-curl.sh"
  httpie_script="$EXAMPLES/demo-$demo-httpie.sh"

  if ! diff <(curl_tree "$curl_script") <(httpie_tree "$httpie_script") >/dev/null; then
    echo "demo-$demo: the two variants send different trees (curl < > httpie):"
    diff <(curl_tree "$curl_script") <(httpie_tree "$httpie_script") || true
    status=1
  fi

  if ! diff <(query_section "$curl_script") <(query_section "$httpie_script") >/dev/null; then
    echo "demo-$demo: the two variants issue different queries (curl < > httpie):"
    diff <(query_section "$curl_script") <(query_section "$httpie_script") || true
    status=1
  fi

  # An empty section would compare equal, so a marker that stopped matching
  # must fail the check instead of passing it.
  if [[ -z "$(query_section "$curl_script")" ]]; then
    echo "demo-$demo: no query section found after run_query"
    status=1
  fi
done

[[ "$status" -eq 0 ]] && echo "curl and httpie variants match: ${DEMOS[*]}"
exit "$status"
