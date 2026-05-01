#!/usr/bin/env bash
# =============================================================================
# demo-simple-httpie.sh — Operational Risk Model demo (httpie)
#
# Bootstraps a small two-tier operational risk tree, then runs a set of
# vague-quantifier queries to demonstrate the query language.
#
# Requires: httpie (https://httpie.io/), jq
#
# Usage:  ./examples/demo-simple-httpie.sh [base_url]
#         default base_url: http://localhost:8090
#
# Tree structure:
#   Operations (portfolio)
#   ├── IT Risk (portfolio)
#   │   ├── Cyber Breach             lognormal  20%  $500K–$8M CI
#   │   └── Ransomware               expert     10%  P25=$200K P50=$1M P75=$4M P95=$15M
#   └── Third Party Risk (portfolio)
#       ├── Supply Chain Disruption  lognormal  15%  $300K–$3M CI
#       └── Regulatory Fine          lognormal   8%  $100K–$2M CI
# =============================================================================
set -euo pipefail

BASE=${1:-http://localhost:8090}

RED='\033[0;31m'; GRN='\033[0;32m'; CYN='\033[0;36m'; YEL='\033[1;33m'; RST='\033[0m'
header() { echo -e "\n${CYN}══════════════════════════════════════════════════${RST}"; echo -e "${CYN}  $*${RST}"; echo -e "${CYN}══════════════════════════════════════════════════${RST}"; }
ok()     { echo -e "  ${GRN}✔${RST}  $*"; }
info()   { echo -e "  ${YEL}→${RST}  $*"; }
fail()   { echo -e "  ${RED}✘${RST}  $*"; }

# ── Preflight ──────────────────────────────────────────────────────────────────
for cmd in http jq; do
  if ! command -v "$cmd" &>/dev/null; then
    fail "Required tool not found: $cmd"; exit 1
  fi
done

header "Demo: Operational Risk Model (httpie)"
info "Server: $BASE"

# ── Step 1: Bootstrap workspace + tree ────────────────────────────────────────
header "Step 1 — Bootstrap workspace"

BOOTSTRAP=$(http --ignore-stdin POST "$BASE/workspaces" \
  name="Operational Risk Model" \
  portfolios:='[
    {"name": "Operations",       "parentName": null},
    {"name": "IT Risk",          "parentName": "Operations"},
    {"name": "Third Party Risk", "parentName": "Operations"}
  ]' \
  leaves:='[
    {
      "name": "Cyber Breach",
      "parentName": "IT Risk",
      "distributionType": "lognormal",
      "probability": 0.20,
      "minLoss": 500000,
      "maxLoss": 8000000,
      "percentiles": null,
      "quantiles": null
    },
    {
      "name": "Ransomware",
      "parentName": "IT Risk",
      "distributionType": "expert",
      "probability": 0.10,
      "minLoss": null,
      "maxLoss": null,
      "percentiles": [0.25, 0.50, 0.75, 0.95],
      "quantiles":   [200000, 1000000, 4000000, 15000000]
    },
    {
      "name": "Supply Chain Disruption",
      "parentName": "Third Party Risk",
      "distributionType": "lognormal",
      "probability": 0.15,
      "minLoss": 300000,
      "maxLoss": 3000000,
      "percentiles": null,
      "quantiles": null
    },
    {
      "name": "Regulatory Fine",
      "parentName": "Third Party Risk",
      "distributionType": "lognormal",
      "probability": 0.08,
      "minLoss": 100000,
      "maxLoss": 2000000,
      "percentiles": null,
      "quantiles": null
    }
  ]')

WS_KEY=$(echo "$BOOTSTRAP" | jq -r '.workspaceKey')
TREE_ID=$(echo "$BOOTSTRAP" | jq -r '.tree.id')
EXPIRES=$(echo "$BOOTSTRAP" | jq -r '.expiresAt')

ok "Workspace key : $WS_KEY"
ok "Tree ID       : $TREE_ID"
ok "Expires at    : $EXPIRES"

# ── Step 2: Fetch tree summary ─────────────────────────────────────────────────
header "Step 2 — Fetch tree summary"

http --ignore-stdin GET "$BASE/w/$WS_KEY/risk-trees/$TREE_ID"

# ── Step 3: Vague quantifier queries ──────────────────────────────────────────
# NOTE: queries are intentionally written without quoted node-name literals
# (e.g. "IT Risk") to avoid the three known parser bugs tracked in
# docs/PLAN-QUERY-NODE-NAME-LITERALS.md. Once those land, sub-portfolio
# scoping queries like leaf_descendant_of(x, "IT Risk") will be re-enabled.
header "Step 3 — Vague quantifier queries"

run_query() {
  local label="$1"
  local q="$2"
  echo -e "\n  ${YEL}Query:${RST} $label"
  echo -e "  ${YEL}Expression:${RST} $q"
  local result
  result=$(http --ignore-stdin POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/query" \
    query="$q")
  local satisfied proportion range_size satisfying_count
  satisfied=$(echo "$result" | jq -r '.satisfied')
  proportion=$(echo "$result" | jq -r '.proportion')
  range_size=$(echo "$result" | jq -r '.rangeSize')
  satisfying_count=$(echo "$result" | jq -r '.satisfyingCount')
  if [[ "$satisfied" == "true" ]]; then
    echo -e "  ${GRN}✔ SATISFIED${RST}  proportion=$proportion  ($satisfying_count / $range_size nodes)"
  else
    echo -e "  ${RED}✘ NOT SATISFIED${RST}  proportion=$proportion  ($satisfying_count / $range_size nodes)"
  fi
}

run_query \
  "Do at least half of all leaves have P95 loss above \$2M?" \
  'Q[>=]^{1/2} x (leaf(x), gt_loss(p95(x), 2000000))'

run_query \
  "Do at least 1/3 of all leaves have P99 loss above \$5M?" \
  'Q[>=]^{1/3} x (leaf(x), gt_loss(p99(x), 5000000))'

run_query \
  "Do at most half of all leaves have a >5% chance of exceeding \$2M?" \
  'Q[<=]^{1/2} x (leaf(x), gt_prob(lec(x, 2000000), 0.05))'

# Existential quantifier in scope: does each portfolio harbour at least one high-severity child?
run_query \
  "Existential (exists): Do at least 2/3 of portfolio nodes have at least one direct child with P95 above \$1M?" \
  'Q[>=]^{2/3} x (portfolio(x), exists y . (child_of(y, x) /\ gt_loss(p95(y), 1000000)))'

# Universal quantifier in scope: are all direct children of most portfolios above a severity floor?
run_query \
  "Universal (forall): Do at least half of portfolio nodes have ALL direct children with P95 above \$1M?" \
  'Q[>=]^{1/2} x (portfolio(x), forall y . (child_of(y, x) ==> gt_loss(p95(y), 1000000)))'

header "Done"
info "Re-run anytime — the workspace key above remains valid until expiry."
