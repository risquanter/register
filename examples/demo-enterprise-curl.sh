#!/usr/bin/env bash
# =============================================================================
# demo-enterprise-curl.sh — Financial Services Enterprise Risk demo (curl)
#
# Bootstraps a realistic 4-domain enterprise risk tree (20 leaves across
# 10 portfolios) and runs 7 vague-quantifier queries against it.
#
# Requires: curl, jq
#
# Usage:  ./examples/demo-enterprise-curl.sh [base_url]
#         default base_url: http://localhost:8090
#
# Tree structure:
#   Enterprise Risk  (root)
#   ├── Operational Risk
#   │   ├── Technology & Cyber
#   │   │   ├── Ransomware Attack             expert  15%  P25=$500K P50=$2M P75=$8M P95=$25M
#   │   │   ├── Cloud Provider Outage         lognorm 30%  $200K–$4M
#   │   │   ├── Data Breach (PII)             lognorm 10%  $1M–$15M
#   │   │   └── Insider Threat                lognorm  5%  $2M–$20M
#   │   ├── Process & People
#   │   │   ├── Key Person Departure          lognorm 20%  $100K–$800K
#   │   │   ├── Internal Fraud                expert   8%  P25=$200K P50=$1M P75=$4M P95=$18M
#   │   │   └── Process Failure               lognorm 25%  $50K–$500K
#   │   └── Third-Party & Supply Chain
#   │       ├── Critical Vendor Failure       lognorm 12%  $500K–$5M
#   │       ├── Outsourcing SLA Breach        lognorm 20%  $100K–$1.5M
#   │       └── Concentration Risk            expert   8%  P25=$1M P50=$4M P95=$18M
#   ├── Financial Risk
#   │   ├── Market Risk
#   │   │   ├── Equity Portfolio Drawdown     expert  35%  P25=$1M P50=$4M P75=$12M P95=$28M
#   │   │   └── FX Adverse Move               lognorm 40%  $500K–$8M
#   │   ├── Credit Risk
#   │   │   ├── Counterparty Default          lognorm  5%  $3M–$30M
#   │   │   └── Credit Downgrade Wave         expert  15%  P25=$800K P50=$3M P95=$20M
#   │   └── Liquidity Risk
#   │       └── Funding Squeeze               lognorm  8%  $2M–$25M
#   ├── Compliance & Legal Risk
#   │   ├── Regulatory Action                 lognorm 12%  $2M–$50M
#   │   ├── Litigation                        expert   8%  P25=$300K P50=$2M P75=$8M P95=$40M
#   │   └── GDPR / Data Protection Fine       lognorm 15%  $500K–$10M
#   └── Strategic & Reputational Risk
#       ├── ESG Controversy                   lognorm 10%  $1M–$12M
#       ├── M&A Integration Failure           lognorm  5%  $5M–$40M
#       └── Product Recall / Liability        expert   6%  P25=$1M P50=$5M P95=$35M
# =============================================================================
set -euo pipefail

BASE=${1:-http://localhost:8090}

RED='\033[0;31m'; GRN='\033[0;32m'; CYN='\033[0;36m'; YEL='\033[1;33m'; RST='\033[0m'
header() { echo -e "\n${CYN}══════════════════════════════════════════════════${RST}"; echo -e "${CYN}  $*${RST}"; echo -e "${CYN}══════════════════════════════════════════════════${RST}"; }
ok()     { echo -e "  ${GRN}✔${RST}  $*"; }
info()   { echo -e "  ${YEL}→${RST}  $*"; }
fail()   { echo -e "  ${RED}✘${RST}  $*"; }

# ── Preflight ──────────────────────────────────────────────────────────────────
for cmd in curl jq; do
  if ! command -v "$cmd" &>/dev/null; then
    fail "Required tool not found: $cmd"; exit 1
  fi
done

header "Demo: Financial Services Enterprise Risk (curl)"
info "Server: $BASE"

# ── Step 1: Bootstrap workspace + tree ────────────────────────────────────────
header "Step 1 — Bootstrap workspace (20 leaves, 10 portfolios)"

BOOTSTRAP=$(curl -s -X POST "$BASE/workspaces" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Financial Services Enterprise Risk",
    "portfolios": [
      {"name": "Enterprise Risk",               "parentName": null},
      {"name": "Operational Risk",              "parentName": "Enterprise Risk"},
      {"name": "Technology & Cyber",            "parentName": "Operational Risk"},
      {"name": "Process & People",              "parentName": "Operational Risk"},
      {"name": "Third-Party & Supply Chain",    "parentName": "Operational Risk"},
      {"name": "Financial Risk",                "parentName": "Enterprise Risk"},
      {"name": "Market Risk",                   "parentName": "Financial Risk"},
      {"name": "Credit Risk",                   "parentName": "Financial Risk"},
      {"name": "Liquidity Risk",                "parentName": "Financial Risk"},
      {"name": "Compliance & Legal Risk",       "parentName": "Enterprise Risk"},
      {"name": "Strategic & Reputational Risk", "parentName": "Enterprise Risk"}
    ],
    "leaves": [
      {
        "name": "Ransomware Attack",
        "parentName": "Technology & Cyber",
        "distributionType": "expert",
        "probability": 0.15,
        "minLoss": null, "maxLoss": null,
        "percentiles": [0.25, 0.50, 0.75, 0.95],
        "quantiles":   [500000, 2000000, 8000000, 25000000]
      },
      {
        "name": "Cloud Provider Outage",
        "parentName": "Technology & Cyber",
        "distributionType": "lognormal",
        "probability": 0.30,
        "minLoss": 200000, "maxLoss": 4000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Data Breach (PII)",
        "parentName": "Technology & Cyber",
        "distributionType": "lognormal",
        "probability": 0.10,
        "minLoss": 1000000, "maxLoss": 15000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Insider Threat",
        "parentName": "Technology & Cyber",
        "distributionType": "lognormal",
        "probability": 0.05,
        "minLoss": 2000000, "maxLoss": 20000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Key Person Departure",
        "parentName": "Process & People",
        "distributionType": "lognormal",
        "probability": 0.20,
        "minLoss": 100000, "maxLoss": 800000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Internal Fraud",
        "parentName": "Process & People",
        "distributionType": "expert",
        "probability": 0.08,
        "minLoss": null, "maxLoss": null,
        "percentiles": [0.25, 0.50, 0.75, 0.95],
        "quantiles":   [200000, 1000000, 4000000, 18000000]
      },
      {
        "name": "Process Failure",
        "parentName": "Process & People",
        "distributionType": "lognormal",
        "probability": 0.25,
        "minLoss": 50000, "maxLoss": 500000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Critical Vendor Failure",
        "parentName": "Third-Party & Supply Chain",
        "distributionType": "lognormal",
        "probability": 0.12,
        "minLoss": 500000, "maxLoss": 5000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Outsourcing SLA Breach",
        "parentName": "Third-Party & Supply Chain",
        "distributionType": "lognormal",
        "probability": 0.20,
        "minLoss": 100000, "maxLoss": 1500000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Concentration Risk",
        "parentName": "Third-Party & Supply Chain",
        "distributionType": "expert",
        "probability": 0.08,
        "minLoss": null, "maxLoss": null,
        "percentiles": [0.25, 0.50, 0.95],
        "quantiles":   [1000000, 4000000, 18000000]
      },
      {
        "name": "Equity Portfolio Drawdown",
        "parentName": "Market Risk",
        "distributionType": "expert",
        "probability": 0.35,
        "minLoss": null, "maxLoss": null,
        "percentiles": [0.25, 0.50, 0.75, 0.95],
        "quantiles":   [1000000, 4000000, 12000000, 28000000]
      },
      {
        "name": "FX Adverse Move",
        "parentName": "Market Risk",
        "distributionType": "lognormal",
        "probability": 0.40,
        "minLoss": 500000, "maxLoss": 8000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Counterparty Default",
        "parentName": "Credit Risk",
        "distributionType": "lognormal",
        "probability": 0.05,
        "minLoss": 3000000, "maxLoss": 30000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Credit Downgrade Wave",
        "parentName": "Credit Risk",
        "distributionType": "expert",
        "probability": 0.15,
        "minLoss": null, "maxLoss": null,
        "percentiles": [0.25, 0.50, 0.95],
        "quantiles":   [800000, 3000000, 20000000]
      },
      {
        "name": "Funding Squeeze",
        "parentName": "Liquidity Risk",
        "distributionType": "lognormal",
        "probability": 0.08,
        "minLoss": 2000000, "maxLoss": 25000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Regulatory Action",
        "parentName": "Compliance & Legal Risk",
        "distributionType": "lognormal",
        "probability": 0.12,
        "minLoss": 2000000, "maxLoss": 50000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Litigation",
        "parentName": "Compliance & Legal Risk",
        "distributionType": "expert",
        "probability": 0.08,
        "minLoss": null, "maxLoss": null,
        "percentiles": [0.25, 0.50, 0.75, 0.95],
        "quantiles":   [300000, 2000000, 8000000, 40000000]
      },
      {
        "name": "GDPR / Data Protection Fine",
        "parentName": "Compliance & Legal Risk",
        "distributionType": "lognormal",
        "probability": 0.15,
        "minLoss": 500000, "maxLoss": 10000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "ESG Controversy",
        "parentName": "Strategic & Reputational Risk",
        "distributionType": "lognormal",
        "probability": 0.10,
        "minLoss": 1000000, "maxLoss": 12000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "M&A Integration Failure",
        "parentName": "Strategic & Reputational Risk",
        "distributionType": "lognormal",
        "probability": 0.05,
        "minLoss": 5000000, "maxLoss": 40000000,
        "percentiles": null, "quantiles": null
      },
      {
        "name": "Product Recall / Liability",
        "parentName": "Strategic & Reputational Risk",
        "distributionType": "expert",
        "probability": 0.06,
        "minLoss": null, "maxLoss": null,
        "percentiles": [0.25, 0.50, 0.95],
        "quantiles":   [1000000, 5000000, 35000000]
      }
    ]
  }')

WS_KEY=$(echo "$BOOTSTRAP" | jq -r '.workspaceKey')
TREE_ID=$(echo "$BOOTSTRAP" | jq -r '.tree.id')
EXPIRES=$(echo "$BOOTSTRAP" | jq -r '.expiresAt')

ok "Workspace key : $WS_KEY"
ok "Tree ID       : $TREE_ID"
ok "Expires at    : $EXPIRES"

# ── Step 2: Fetch tree summary ─────────────────────────────────────────────────
header "Step 2 — Fetch tree summary"

curl -s "$BASE/w/$WS_KEY/risk-trees/$TREE_ID" | jq .

# ── Step 3: Vague quantifier queries ──────────────────────────────────────────
# NOTE: queries are intentionally written without quoted node-name literals
# (e.g. "Technology & Cyber") to avoid the three known parser bugs tracked
# in docs/PLAN-QUERY-NODE-NAME-LITERALS.md. Once those land, sub-portfolio
# scoping queries like leaf_descendant_of(x, "Technology & Cyber") will be
# re-enabled. The remaining queries exercise the full query surface
# (gt_loss, gt_prob, p95, p99, lec, leaf, portfolio, all four quantifier
# shapes >=, <=, ~, with thresholds) without referencing node names.
header "Step 3 — Vague quantifier queries"

run_query() {
  local label="$1"
  local q="$2"
  echo -e "\n  ${YEL}Query:${RST} $label"
  echo -e "  ${YEL}Expression:${RST} $q"
  local result
  result=$(curl -s -X POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/query" \
    -H 'Content-Type: application/json' \
    -d "{\"query\": $(echo "$q" | jq -Rs .)}")
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

# Q1 — Enterprise tail severity: how many risks carry catastrophic potential?
run_query \
  "Q1: Do at least 1/4 of all leaves have P99 above \$20M?" \
  'Q[>=]^{1/4} x (leaf(x), gt_loss(p99(x), 20000000))'

# Q2 — High-frequency materiality: risks combining likelihood AND size
run_query \
  "Q2: Do fewer than half of all leaves have a >10% chance of exceeding \$1M?" \
  'Q[<=]^{1/2} x (leaf(x), gt_prob(lec(x, 1000000), 0.10))'

# Q3 — Tail breadth at the P95 level (was Q7, scoped to Operational Risk)
run_query \
  "Q3: Do at least 3/4 of all leaves have P95 above \$1M?" \
  'Q[>=]^{3/4} x (leaf(x), gt_loss(p95(x), 1000000))'

# Q4 — Mid-band materiality (~ vague quantifier)
run_query \
  "Q4: Do about half of all leaves have P95 above \$5M?" \
  'Q[~]^{1/2} x (leaf(x), gt_loss(p95(x), 5000000))'

# Q5 — Portfolio aggregation view (board-level filter)
run_query \
  "Q5: Do at most 1/3 of portfolio nodes have P99 above \$50M?" \
  'Q[<=]^{1/3} x (portfolio(x), gt_loss(p99(x), 50000000))'

# Q6 — Catastrophic exceedance breadth
run_query \
  "Q6: Do at most 1/4 of all leaves have a >5% chance of exceeding \$10M?" \
  'Q[<=]^{1/4} x (leaf(x), gt_prob(lec(x, 10000000), 0.05))'

header "Done"
info "Re-run anytime — the workspace key above remains valid until expiry."
