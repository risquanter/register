#!/usr/bin/env bash
# =============================================================================
# demo-enterprise-httpie.sh — Financial Services Enterprise Risk demo (httpie)
#
# Bootstraps a realistic 4-domain enterprise risk tree (21 leaves across
# 11 portfolios) and runs 20 vague-quantifier queries against it.
# Queries Q1–Q8 cover the full surface; Q-A–Q-D demonstrate sub-portfolio
# scoping; Q-Ab/Q-Bb/Q-C1b/Q-C2b/Q-Db are satisfied contrasts that show
# how adjusting a threshold, scope, or quantifier direction flips the result.
#
# Requires: httpie (https://httpie.io/), jq
#
# Usage:  ./examples/demo-enterprise-httpie.sh [base_url]
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
for cmd in http jq; do
  if ! command -v "$cmd" &>/dev/null; then
    fail "Required tool not found: $cmd"; exit 1
  fi
done

header "Demo: Financial Services Enterprise Risk (httpie)"
info "Server: $BASE"

# ── Step 1: Bootstrap workspace + tree ────────────────────────────────────────
header "Step 1 — Bootstrap workspace (21 leaves, 11 portfolios)"

BOOTSTRAP=$(http --ignore-stdin POST "$BASE/workspaces" \
  name="Financial Services Enterprise Risk" \
  portfolios:='[
    {"name": "Enterprise Risk",                  "parentName": null},
    {"name": "Operational Risk",                 "parentName": "Enterprise Risk"},
    {"name": "Technology & Cyber",               "parentName": "Operational Risk"},
    {"name": "Process & People",                 "parentName": "Operational Risk"},
    {"name": "Third-Party & Supply Chain",       "parentName": "Operational Risk"},
    {"name": "Financial Risk",                   "parentName": "Enterprise Risk"},
    {"name": "Market Risk",                      "parentName": "Financial Risk"},
    {"name": "Credit Risk",                      "parentName": "Financial Risk"},
    {"name": "Liquidity Risk",                   "parentName": "Financial Risk"},
    {"name": "Compliance & Legal Risk",          "parentName": "Enterprise Risk"},
    {"name": "Strategic & Reputational Risk",    "parentName": "Enterprise Risk"}
  ]' \
  leaves:='[
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
# Q1–Q8 cover the full query surface without sub-portfolio scoping (gt_loss,
# gt_prob, p95, p99, lec, leaf, portfolio, all four quantifier shapes).
# Q-A–Q-D below demonstrate sub-portfolio scoping with quoted node-name
# literals (leaf_descendant_of, child_of, descendant_of, with negation).
# Quoted node-name literal support: PLAN-QUERY-NODE-NAME-LITERALS.md §F1–F3.
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

# Q1 — Enterprise tail severity: how many risks carry catastrophic potential?
run_query \
  "Q1: Do at least 1/4 of all leaves have P99 above \$10M?" \
  'Q[>=]^{1/4} x (leaf(x), gt_loss(p99(x), 10000000))'

# Q2 — High-frequency materiality: risks combining likelihood AND size
run_query \
  "Q2: Do fewer than half of all leaves have a >10% chance of exceeding \$1M?" \
  'Q[<=]^{1/2} x (leaf(x), gt_prob(lec(x, 1000000), 0.10))'

# Q3 — Tail breadth at the P95 level (was Q7, scoped to Operational Risk)
run_query \
  "Q3: Do at least 3/4 of all leaves have P95 above \$1M?" \
  'Q[>=]^{3/4} x (leaf(x), gt_loss(p95(x), 1000000))'

# Q4 — Mid-band materiality (~ vague quantifier)
# NOT SATISFIED by design: p95(x) is the *unconditional* P95 — computed over
# every Monte Carlo trial, including those where the risk event did not fire
# (loss = $0). For a leaf with 15% annual probability, the unconditional P95
# corresponds only to the conditional ~67th percentile ((0.95−0.85)/0.15).
# Any leaf with probability ≤ 5% has unconditional P95 = $0 by definition.
# At a $5M bar, only 5/21 leaves qualify (24%) — far from "about half".
# Q4b keeps the same threshold and restates the proportion to match reality.
run_query \
  "Q4: Do about half of all leaves have unconditional P95 above \$5M?" \
  'Q[~]^{1/2} x (leaf(x), gt_loss(p95(x), 5000000))'

# Q4b — same $5M threshold: ~1/5 of leaves qualify; Q[~]^{1/5} showcases vague "about" tolerance
run_query \
  "Q4b: Do about 1/5 of all leaves have unconditional P95 above \$5M? (around-quantifier contrast)" \
  'Q[~]^{1/5} x (leaf(x), gt_loss(p95(x), 5000000))'

# Q5 — Portfolio aggregation view (board-level filter)
run_query \
  "Q5: Do at most 1/3 of portfolio nodes have P95 above \$50M?" \
  'Q[<=]^{1/3} x (portfolio(x), gt_loss(p95(x), 50000000))'

# Q6 — Catastrophic exceedance breadth
run_query \
  "Q6: Do at most 1/4 of all leaves have a >5% chance of exceeding \$10M?" \
  'Q[<=]^{1/4} x (leaf(x), gt_prob(lec(x, 10000000), 0.05))'

# Q7 — existential quantifier in scope: which portfolios harbour at least one severe child?
# NOT SATISFIED by design: same unconditional-P95 effect applies to direct
# children. 7/11 portfolios (64%) have at least one child clearing the $5M
# bar — just short of the 3/4 threshold. Q7b uses the ~ quantifier to state
# the same question at the proportion the data actually supports.
run_query \
  "Q7 (exists): Do at least 3/4 of portfolio nodes have at least one direct child with unconditional P95 above \$5M?" \
  'Q[>=]^{3/4} x (portfolio(x), exists y . (child_of(y, x) /\ gt_loss(p95(y), 5000000)))'

# Q7b — same $5M threshold, proportion expressed as "about 2/3" (satisfied)
run_query \
  "Q7b (exists): Do about 2/3 of portfolio nodes have at least one direct child with unconditional P95 above \$5M?" \
  'Q[~]^{2/3} x (portfolio(x), exists y . (child_of(y, x) /\ gt_loss(p95(y), 5000000)))'

# Q8 — universal quantifier in scope: are all direct children of most portfolios above a material floor?
run_query \
  "Q8 (forall): Do at least half of portfolio nodes have ALL their direct children with P99 above \$1M?" \
  'Q[>=]^{1/2} x (portfolio(x), forall y . (child_of(y, x) ==> gt_loss(p99(y), 1000000)))'

# ── Sub-portfolio scoped queries (Q-A – Q-D, Phase 6) ────────────────────────
# Scope the quantifier range to a named sub-portfolio via quoted node-name
# literals. Requires PLAN-QUERY-NODE-NAME-LITERALS.md §F1–F3 (live since
# vql-engine 0.10.1-SNAPSHOT + Phase 4 catalog.constants population).

# Q-A — descendant scoping + P95: tail severity within the Cyber sub-portfolio
# NOT SATISFIED: no Cyber leaf has unconditional P95 > $5M (prob ≤ 30% means most are zero at P95)
run_query \
  "Q-A: Do at least 2/3 of Technology & Cyber leaf risks have P95 above \$5M?" \
  'Q[>=]^{2/3} x (leaf_descendant_of(x, "Technology & Cyber"), gt_loss(p95(x), 5000000))'

# Q-Ab — same scope, P99 > $1M: all 4 Cyber leaves clear it (Insider Threat P99 well above $1M even at 5% prob)
run_query \
  "Q-Ab: Do at least 2/3 of Technology & Cyber leaf risks have P99 above \$1M? (p99 contrast)" \
  'Q[>=]^{2/3} x (leaf_descendant_of(x, "Technology & Cyber"), gt_loss(p99(x), 1000000))'

# Q-B — direct-child scoping + LEC + Probability: Operational Risk immediate sub-units
# NOT SATISFIED: only 1 of 3 direct children (Technology & Cyber) has high enough aggregate tail
run_query \
  "Q-B: Do at least half of direct children of Operational Risk have >5% chance of exceeding \$10M?" \
  'Q[>=]^{1/2} x (child_of(x, "Operational Risk"), gt_prob(lec(x, 10000000), 0.05))'

# Q-Bb — scope swap to Enterprise Risk: all 4 top-level domains have high aggregate tails
run_query \
  "Q-Bb: Do at least half of direct children of Enterprise Risk have >5% chance of exceeding \$10M? (scope swap contrast)" \
  'Q[>=]^{1/2} x (child_of(x, "Enterprise Risk"), gt_prob(lec(x, 10000000), 0.05))'

# Q-C1 / Q-C2 — cross-branch comparison: same P99 bar across two domains
# Q-C1 NOT SATISFIED: only 1/5 Financial Risk leaves (Counterparty Default) clears $20M P99
run_query \
  "Q-C1: Do at least 2/3 of Financial Risk leaf descendants have P99 above \$20M?" \
  'Q[>=]^{2/3} x (leaf_descendant_of(x, "Financial Risk"), gt_loss(p99(x), 20000000))'

# Q-C1b — quantifier flip: same data satisfies a <=1/3 cap
run_query \
  "Q-C1b: Do at most 1/3 of Financial Risk leaf descendants have P99 above \$20M? (quantifier flip contrast)" \
  'Q[<=]^{1/3} x (leaf_descendant_of(x, "Financial Risk"), gt_loss(p99(x), 20000000))'

# Q-C2 NOT SATISFIED: only 1/10 Operational Risk leaves clear $20M P99 (even fewer than Financial Risk)
run_query \
  "Q-C2: Do at least 2/3 of Operational Risk leaf descendants have P99 above \$20M?" \
  'Q[>=]^{2/3} x (leaf_descendant_of(x, "Operational Risk"), gt_loss(p99(x), 20000000))'

# Q-C2b — scope swap to Compliance & Legal Risk + lower threshold: all 3 leaves clear $5M P99
run_query \
  "Q-C2b: Do at least 2/3 of Compliance & Legal Risk leaf descendants have P99 above \$5M? (scope+threshold swap contrast)" \
  'Q[>=]^{2/3} x (leaf_descendant_of(x, "Compliance & Legal Risk"), gt_loss(p99(x), 5000000))'

# Q-D — exclusion via negation: leaves outside the Cyber cluster
# NOT SATISFIED: ~9-11/21 non-Cyber leaves have P95 > $1M (~43-52%) — far from "about 1/3"
run_query \
  "Q-D: Do about 1/3 of non-Cyber leaves have P95 above \$1M?" \
  'Q[~]^{1/3} x (leaf(x), ~descendant_of(x, "Technology & Cyber") /\ gt_loss(p95(x), 1000000))'

# Q-Db — same proportion IS "about 1/2"; Q[~]^{1/2} showcases around tolerance vs strict Q[<=]
run_query \
  "Q-Db: Do about half of non-Cyber leaves have P95 above \$1M? (around-quantifier contrast)" \
  'Q[~]^{1/2} x (leaf(x), ~descendant_of(x, "Technology & Cyber") /\ gt_loss(p95(x), 1000000))'

header "Done"
info "Re-run anytime — the workspace key above remains valid until expiry."
