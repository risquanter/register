#!/usr/bin/env bash
# ==========================================================================
# test-workspace-errors.sh — End-to-end workspace error handling tests
#
# Tests the full request chain through nginx → backend for workspace URLs.
# Logs actual HTTP status, content-type, and body for each scenario.
#
# Usage:  ./test-workspace-errors.sh [base_url]
#         default base_url: http://localhost:18080
# ==========================================================================
set -euo pipefail

BASE=${1:-http://localhost:18080}
PASS=0
FAIL=0
TOTAL=0

RED='\033[0;31m'; GRN='\033[0;32m'; YEL='\033[1;33m'; CYN='\033[0;36m'; RST='\033[0m'

# ── Helpers ──────────────────────────────────────────────────────────────

# Sets globals: REQ_STATUS REQ_CT REQ_BODY
do_request() {
  local url="$1" accept="${2:-}"
  local tmpbody tmpheaders
  tmpbody=$(mktemp); tmpheaders=$(mktemp)
  local curl_args=(-s -D "$tmpheaders" -o "$tmpbody")
  [ -n "$accept" ] && curl_args+=(-H "Accept: $accept")
  curl "${curl_args[@]}" "$url"
  REQ_STATUS=$(head -1 "$tmpheaders" | grep -oP '\d{3}')
  REQ_CT=$(grep -i '^content-type:' "$tmpheaders" | head -1 | sed 's/^[^:]*: *//;s/\r$//' || echo "")
  REQ_BODY=$(cat "$tmpbody")
  rm -f "$tmpbody" "$tmpheaders"
}

check() {
  local label="$1" expected="$2" actual="$3"
  TOTAL=$((TOTAL + 1))
  if [ "$expected" = "$actual" ]; then
    echo -e "  ${GRN}✓${RST} $label  ($actual)"; PASS=$((PASS + 1))
  else
    echo -e "  ${RED}✗${RST} $label  expected='$expected' got='$actual'"; FAIL=$((FAIL + 1))
  fi
}

check_contains() {
  local label="$1" needle="$2" haystack="$3"
  TOTAL=$((TOTAL + 1))
  if echo "$haystack" | grep -q "$needle"; then
    echo -e "  ${GRN}✓${RST} $label  contains '$needle'"; PASS=$((PASS + 1))
  else
    echo -e "  ${RED}✗${RST} $label  missing '$needle'"; echo "       got: ${haystack:0:200}"; FAIL=$((FAIL + 1))
  fi
}

check_not_contains() {
  local label="$1" needle="$2" haystack="$3"
  TOTAL=$((TOTAL + 1))
  if echo "$haystack" | grep -q "$needle"; then
    echo -e "  ${RED}✗${RST} $label  should NOT contain '$needle'"; FAIL=$((FAIL + 1))
  else
    echo -e "  ${GRN}✓${RST} $label  no '$needle'"; PASS=$((PASS + 1))
  fi
}

BROWSER_ACCEPT="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

# ── Step 0: Health ──────────────────────────────────────────────────────
echo -e "\n${CYN}=== Step 0: Health check ===${RST}"
do_request "$BASE/health"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT  Body: $REQ_BODY"
[ "$REQ_STATUS" != "200" ] && echo -e "${RED}Backend not healthy — aborting${RST}" && exit 1

# ── Step 1: Bootstrap workspace ─────────────────────────────────────────
echo -e "\n${CYN}=== Step 1: Bootstrap workspace ===${RST}"
BOOT_BODY='{"name":"test-tree","portfolios":[{"name":"Root","parentName":null}],"leaves":[{"name":"Leaf","parentName":"Root","distributionType":"expert","probability":0.05,"percentiles":[0.05,0.5,0.95],"quantiles":[100000,500000,2000000]}]}'
tmpb=$(mktemp); tmph=$(mktemp)
curl -s -D "$tmph" -o "$tmpb" -X POST -H "Content-Type: application/json" -H "Accept: application/json" "$BASE/workspaces" -d "$BOOT_BODY"
BOOT_STATUS=$(head -1 "$tmph" | grep -oP '\d{3}')
BOOT_RESP=$(cat "$tmpb"); rm -f "$tmpb" "$tmph"
echo "  HTTP $BOOT_STATUS"
echo "  Response: ${BOOT_RESP:0:300}"
WS_KEY=$(echo "$BOOT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['workspaceKey'])" 2>/dev/null || echo "")
TREE_ID=$(echo "$BOOT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['tree']['id'])" 2>/dev/null || echo "")
[ -z "$WS_KEY" ] && echo -e "${RED}Bootstrap failed — aborting${RST}" && exit 1

EXPIRED="AAAAAAAAAAAAAAAAAAAAAA"
UKEY1="qg2E6mpcmrIpYeEY_4yawA"
UKEY2="RDmIrObAq9yxj6JvNRpqxg"

echo -e "  ${YEL}VALID key:   $WS_KEY${RST}"
echo -e "  ${YEL}EXPIRED key: $EXPIRED${RST}"
echo -e "  ${YEL}USER key 1:  $UKEY1${RST}"
echo -e "  ${YEL}USER key 2:  $UKEY2${RST}"

# ── Step 2: Valid key — Fetch-style ─────────────────────────────────────
echo -e "\n${CYN}=== Step 2: Valid key — Fetch list trees ===${RST}"
do_request "$BASE/w/$WS_KEY/risk-trees"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: ${REQ_BODY:0:200}"
check "status" "200" "$REQ_STATUS"
check_contains "json CT" "application/json" "$REQ_CT"

# ── Step 3: Valid key — browser nav ─────────────────────────────────────
echo -e "\n${CYN}=== Step 3: Valid key — browser navigation ===${RST}"
do_request "$BASE/w/$WS_KEY/risk-trees" "$BROWSER_ACCEPT"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: ${REQ_BODY:0:120}"
check "status" "200" "$REQ_STATUS"
check_contains "html CT" "text/html" "$REQ_CT"
check_contains "SPA shell" "index-" "$REQ_BODY"

# ── Step 4: Expired key — Fetch-style ──────────────────────────────────
echo -e "\n${CYN}=== Step 4: Expired key ($EXPIRED) — Fetch list trees ===${RST}"
do_request "$BASE/w/$EXPIRED/risk-trees"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: $REQ_BODY"
check "status" "404" "$REQ_STATUS"
check_contains "json CT" "application/json" "$REQ_CT"
check_contains "workspace domain" '"domain":"workspaces"' "$REQ_BODY"
check_not_contains "no key leak" "$EXPIRED" "$REQ_BODY"

# ── Step 5: Expired key — browser nav ──────────────────────────────────
echo -e "\n${CYN}=== Step 5: Expired key — browser navigation ===${RST}"
do_request "$BASE/w/$EXPIRED/risk-trees" "$BROWSER_ACCEPT"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: ${REQ_BODY:0:120}"
check "status" "200" "$REQ_STATUS"
check_contains "html CT" "text/html" "$REQ_CT"
check_contains "SPA shell" "index-" "$REQ_BODY"
check_not_contains "no RepositoryFailure" "RepositoryFailure" "$REQ_BODY"

# ── Step 6: User key 1 — Fetch + browser ──────────────────────────────
echo -e "\n${CYN}=== Step 6a: User key 1 ($UKEY1) — Fetch ===${RST}"
do_request "$BASE/w/$UKEY1/risk-trees"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: $REQ_BODY"
check "status" "404" "$REQ_STATUS"
check_contains "json CT" "application/json" "$REQ_CT"
check_not_contains "no HTML" "<!DOCTYPE" "$REQ_BODY"

echo -e "\n${CYN}=== Step 6b: User key 1 — browser ===${RST}"
do_request "$BASE/w/$UKEY1/risk-trees" "$BROWSER_ACCEPT"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: ${REQ_BODY:0:120}"
check "status" "200" "$REQ_STATUS"
check_contains "html CT" "text/html" "$REQ_CT"

# ── Step 7: User key 2 — key-only URL ─────────────────────────────────
echo -e "\n${CYN}=== Step 7a: User key 2 ($UKEY2) — key-only, Fetch ===${RST}"
echo "  NOTE: /w/{key} with no sub-path — no Tapir endpoint matches"
do_request "$BASE/w/$UKEY2"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: $REQ_BODY"

echo -e "\n${CYN}=== Step 7b: User key 2 — with /risk-trees ===${RST}"
do_request "$BASE/w/$UKEY2/risk-trees"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: $REQ_BODY"
check "status" "404" "$REQ_STATUS"
check_contains "json CT" "application/json" "$REQ_CT"

echo -e "\n${CYN}=== Step 7c: User key 2 — browser ===${RST}"
do_request "$BASE/w/$UKEY2" "$BROWSER_ACCEPT"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: ${REQ_BODY:0:120}"
check "status" "200" "$REQ_STATUS"
check_contains "html CT" "text/html" "$REQ_CT"

# ── Step 8: Valid key-only URL — browser ───────────────────────────────
echo -e "\n${CYN}=== Step 8: Valid key-only URL — browser ===${RST}"
do_request "$BASE/w/$WS_KEY" "$BROWSER_ACCEPT"
echo "  HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: ${REQ_BODY:0:120}"
check "status" "200" "$REQ_STATUS"
check_contains "html CT" "text/html" "$REQ_CT"
check_contains "SPA shell" "index-" "$REQ_BODY"

# ── Step 9: Delete → retry (true expiry) ──────────────────────────────
echo -e "\n${CYN}=== Step 9: Delete workspace then retry ===${RST}"
DEL_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/w/$WS_KEY")
echo "  DELETE → HTTP $DEL_STATUS"

do_request "$BASE/w/$WS_KEY/risk-trees"
echo "  After delete — Fetch: HTTP $REQ_STATUS  CT: $REQ_CT"
echo "  Body: $REQ_BODY"
check "status" "404" "$REQ_STATUS"
check_contains "json CT" "application/json" "$REQ_CT"
check_contains "workspace domain" '"domain":"workspaces"' "$REQ_BODY"

# ── Step 10: JS bundle sanity ──────────────────────────────────────────
# The JS bundle is ~4MB — too large for shell variable capture.
# Use docker exec to grep inside the container directly.
echo -e "\n${CYN}=== Step 10: JS bundle sanity ===${RST}"
do_request "$BASE/" "text/html"
JS_PATH=$(echo "$REQ_BODY" | grep -oP 'src="/assets/index-[^"]+\.js"' | head -1 | sed 's/src="//;s/"//')
echo "  JS bundle: $JS_PATH"
if [ -n "$JS_PATH" ]; then
  do_request "$BASE$JS_PATH"
  check "JS loads" "200" "$REQ_STATUS"
  # grep inside container since curl body is too large for shell variables
  SENTINEL_COUNT=$(docker exec frontend grep -c 'workspace:not-found' "/srv/app$JS_PATH" 2>/dev/null || echo "0")
  CLASSIFIER_COUNT=$(docker exec frontend grep -c 'WorkspaceExpired' "/srv/app$JS_PATH" 2>/dev/null || echo "0")
  TOTAL=$((TOTAL + 1))
  if [ "$SENTINEL_COUNT" -gt 0 ]; then
    echo -e "  ${GRN}✓${RST} sentinel 'workspace:not-found' in bundle ($SENTINEL_COUNT occurrences)"; PASS=$((PASS + 1))
  else
    echo -e "  ${RED}✗${RST} sentinel 'workspace:not-found' MISSING from bundle"; FAIL=$((FAIL + 1))
  fi
  TOTAL=$((TOTAL + 1))
  if [ "$CLASSIFIER_COUNT" -gt 0 ]; then
    echo -e "  ${GRN}✓${RST} classifier 'WorkspaceExpired' in bundle ($CLASSIFIER_COUNT occurrences)"; PASS=$((PASS + 1))
  else
    echo -e "  ${RED}✗${RST} classifier 'WorkspaceExpired' MISSING from bundle"; FAIL=$((FAIL + 1))
  fi
else
  echo -e "  ${RED}✗ Could not find JS bundle path in index.html${RST}"
  FAIL=$((FAIL + 1)); TOTAL=$((TOTAL + 1))
fi

# ── Summary ─────────────────────────────────────────────────────────────
echo -e "\n${CYN}========================================${RST}"
echo -e "  Tests: $TOTAL   ${GRN}Passed: $PASS${RST}   ${RED}Failed: $FAIL${RST}"
echo -e "${CYN}========================================${RST}"
[ "$FAIL" -gt 0 ] && exit 1 || exit 0
