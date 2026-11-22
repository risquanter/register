#!/usr/bin/env bash
# =============================================================================
# demo-mitigations-curl.sh — Mitigations walkthrough (curl)
#
# Builds a three-level risk tree, gives it a short commit history, forks a
# scenario, adds four mitigations there, and prints the tree's figures with and
# without them. docs/user/MITIGATION-WALKTHROUGH.md explains each step.
#
# Commit history needs the Irmin backend (the in-memory repository keeps none).
# Bring the persistence stack up first.
#
# Requires: curl, jq
#
# Usage:  ./examples/demo-mitigations-curl.sh [base_url]
#         default base_url: http://localhost:18080 (nginx proxies API to register-server)
#         Stack:
#           docker compose --profile persistence --profile frontend --env-file .env.irmin up -d --wait
#
# Tree "Regional Bank Risk Register" (branch main), as created:
#   Enterprise (portfolio)
#   ├── Cyber (portfolio)
#   │   ├── Ransomware            lognormal  15%  $400K–$9M
#   │   └── Data Breach           metalog    10%  P25=$500K P50=$2M P75=$6M P95=$20M
#   ├── Operations (portfolio)
#   │   ├── Cloud Outage          lognormal  30%  $100K–$2M
#   │   ├── Payment Fraud         lognormal  25%  $50K–$1.5M
#   │   └── Key Supplier Failure  lognormal  12%  $300K–$5M
#   └── Compliance (portfolio)
#       └── Regulatory Fine       lognormal   8%  $500K–$12M
#
# Commit history on main, oldest first:
#   C1 Create
#   C2 Update  Ransomware probability 15% -> 25%
#   C3 Update  add leaf Phishing 35% $20K–$400K under Cyber
#
# Scenario "with-controls" (forked from main head), its own commits:
#   S1 Update  add three mitigations
#        MFA and awareness training   halves the likelihood of every leaf under Cyber
#        Cyber insurance              insurance policy on Cyber's total: $250K deductible, $5M limit
#        Fraud monitoring             scales every Payment Fraud loss to 60%
#   S2 Update  add an absolute statement
#        Regulatory Fine after remediation   the leaf's likelihood is 3%
#   S3 Update  Regulatory Fine max loss $12M -> $20M   <- the statement goes stale
#   S4 Update  re-affirm the statement
# =============================================================================
set -euo pipefail

BASE=${1:-http://localhost:18080}
CLIENT=curl

RED='\033[0;31m'; GRN='\033[0;32m'; CYN='\033[0;36m'; YEL='\033[1;33m'; RST='\033[0m'
header() { echo -e "\n${CYN}══════════════════════════════════════════════════${RST}"; echo -e "${CYN}  $*${RST}"; echo -e "${CYN}══════════════════════════════════════════════════${RST}"; }
ok()     { echo -e "  ${GRN}✔${RST}  $*"; }
info()   { echo -e "  ${YEL}→${RST}  $*"; }
fail()   { echo -e "  ${RED}✘${RST}  $*"; }

# ── Preflight ────────────────────────────────────────────────────────────────
for cmd in curl jq; do
  if ! command -v "$cmd" &>/dev/null; then
    fail "Required tool not found: $cmd"; exit 1
  fi
done

# ── Requests ─────────────────────────────────────────────────────────────────
# Every request the walkthrough sends is one function here. This section is the
# only part that differs between the curl and the HTTPie variant.

# Creates the workspace and its first tree.
bootstrap() {
  curl -s -X POST "$BASE/workspaces" \
    -H 'Content-Type: application/json' \
    -d '{
      "name": "Regional Bank Risk Register",
      "portfolios": [
        { "name": "Enterprise", "parentName": null },
        { "name": "Cyber",      "parentName": "Enterprise" },
        { "name": "Operations", "parentName": "Enterprise" },
        { "name": "Compliance", "parentName": "Enterprise" }
      ],
      "leaves": [
        {
          "name": "Ransomware",
          "parentName": "Cyber",
          "probability": 0.15,
          "distributionShape": { "distributionType": "Lognormal", "minLoss": 400000, "maxLoss": 9000000 }
        },
        {
          "name": "Data Breach",
          "parentName": "Cyber",
          "probability": 0.1,
          "distributionShape": {
            "distributionType": "Metalog",
            "percentiles": [0.25, 0.5, 0.75, 0.95],
            "quantiles": [500000, 2000000, 6000000, 20000000],
            "terms": null
          }
        },
        {
          "name": "Cloud Outage",
          "parentName": "Operations",
          "probability": 0.3,
          "distributionShape": { "distributionType": "Lognormal", "minLoss": 100000, "maxLoss": 2000000 }
        },
        {
          "name": "Payment Fraud",
          "parentName": "Operations",
          "probability": 0.25,
          "distributionShape": { "distributionType": "Lognormal", "minLoss": 50000, "maxLoss": 1500000 }
        },
        {
          "name": "Key Supplier Failure",
          "parentName": "Operations",
          "probability": 0.12,
          "distributionShape": { "distributionType": "Lognormal", "minLoss": 300000, "maxLoss": 5000000 }
        },
        {
          "name": "Regulatory Fine",
          "parentName": "Compliance",
          "probability": 0.08,
          "distributionShape": { "distributionType": "Lognormal", "minLoss": 500000, "maxLoss": 12000000 }
        }
      ],
      "newMitigations": []
    }'
}

# The tree's structure on a branch. $1 branch, $2 commit hash to read at (optional).
structure() {
  curl -s "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/structure${2:+?at=$2}" -H "X-Branch: $1"
}

# The tree's commits on a branch, oldest first. $1 branch
tree_history() {
  curl -s "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/history?n=50" -H "X-Branch: $1"
}

# Which nodes differ between a commit on main and the head of main. $1 commit hash
changed_nodes() {
  curl -s "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/changed-nodes?a=main&aAt=$1&b=main"
}

# Curves and summary figures of several nodes under one selection.
# $1 branch, $2 selection (JSON), $3 node ids (JSON list)
curves() {
  curl -s -X POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/nodes/lec-multi" \
    -H 'Content-Type: application/json' -H "X-Branch: $1" \
    -d "{\"nodeIds\": $3, \"selection\": $2}"
}

# The chance that a node's loss meets or exceeds a threshold, under one selection.
# $1 branch, $2 node id, $3 threshold, $4 selection (JSON), $5 commit hash to read at (optional)
exceedance() {
  curl -s -X POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/nodes/$2/prob-of-exceedance?threshold=$3${5:+&at=$5}" \
    -H 'Content-Type: application/json' -H "X-Branch: $1" \
    -d "{\"selection\": $4}" \
    | jq -r '.probability'
}

# Replaces the tree on a branch, and fails when the server refuses the request.
# $1 branch, $2 request body (JSON)
send_tree() {
  curl -s --fail-with-body -X PUT "$BASE/w/$WS_KEY/risk-trees/$TREE_ID" \
    -H 'Content-Type: application/json' -H "X-Branch: $1" -d "$2"
}

# Forks a scenario from the head of main. $1 scenario name
create_scenario() {
  curl -s -X POST "$BASE/w/$WS_KEY/scenarios" \
    -H 'Content-Type: application/json' \
    -d "{\"name\": \"$1\", \"source\": {\"type\": \"Branch\", \"name\": \"main\"}}"
}

# Runs a screening query. $1 branch, $2 query text
send_query() {
  curl -s -X POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/query" \
    -H 'Content-Type: application/json' -H "X-Branch: $1" \
    -d "{\"query\": $(echo "$2" | jq -Rs .)}"
}

# ── Shared helpers ───────────────────────────────────────────────────────────
# From this line to the end the curl and the HTTPie variant are the same text.

# Any command that fails stops the script (set -e). This says where it stopped.
trap 'fail "Stopped at line $LINENO: $BASH_COMMAND"' ERR

# Turns a structure read into the request that replaces the tree with itself:
# every stored node and every stored mitigation is kept, by its id. An absolute
# statement is sent back without the target and the stamp the server wrote, and
# asks to keep its stamp.
KEEP_ALL='
  (.tree.nodes | map(.RiskPortfolio // .RiskLeaf) | map({(.id): .name}) | add) as $names
  | {
      name: .tree.name,
      portfolios: [ .tree.nodes[] | .RiskPortfolio // empty
                    | {id, name, parentName: (if .parentId then $names[.parentId] else null end)} ],
      leaves: [ .tree.nodes[] | .RiskLeaf // empty
                | {id, name, parentName: $names[.parentId], probability, distributionShape: .shape} ],
      newPortfolios: [],
      newLeaves: [],
      mitigations: [ (.tree.mitigations // [])[]
                     | if .spec.stage == "LeafAbsolute"
                       then del(.target) | .spec |= (del(.baseStamp) + {stamp: {kind: "KeepIfUnchanged"}})
                       else . end ],
      newMitigations: []
    }'

# Reads the tree on a branch, changes the request with a jq edit, and saves it,
# which makes one commit. $1 branch, $2 label, then any jq arguments the edit
# names, and the jq edit last.
put_tree() {
  local branch="$1" label="$2"; shift 2
  local body answer
  body=$(structure "$branch" | jq "$KEEP_ALL" | jq "$@")
  if answer=$(send_tree "$branch" "$body"); then
    ok "$label"
  else
    fail "$label — refused by the server:"; echo "$answer" | jq . 2>/dev/null || echo "$answer"; exit 1
  fi
}

# The id of the node with a name. $1 branch, $2 node name
node_id() {
  structure "$1" | jq -r --arg n "$2" '.tree.nodes[] | (.RiskPortfolio // .RiskLeaf) | select(.name == $n) | .id'
}

# The id of the mitigation with a name. $1 branch, $2 mitigation name
mitigation_id() {
  structure "$1" | jq -r --arg n "$2" '(.tree.mitigations // [])[] | select(.name == $n) | .id'
}

# The name of every node on a branch, keyed by its id (JSON). $1 branch
node_names() {
  structure "$1" | jq '.tree.nodes | map(.RiskPortfolio // .RiskLeaf) | map({(.id): .name}) | add'
}

# Prints one line per reading in a curves answer. A node has one reading where
# the selection does not reach it, and two where it does.
print_figures() {
  jq -r '
    def money: if . >= 1000000 then "$\(. / 10000 | round / 100)M" elif . >= 1000 then "$\(. / 1000 | round)K" else "$\(. | round)" end;
    .curves[][]
    | [ .curve.name,
        (if (.withMitigations | length) == 0 then "without mitigations" else "with \(.withMitigations | length) mitigation(s)" end),
        (.curve.averageAnnualLoss | money),
        (.curve.quantiles.P95 | money),
        (.curve.quantiles.P99 | money) ]
    | @tsv' \
    | awk -F'\t' 'BEGIN { printf "  %-18s %-22s %10s %10s %10s\n", "node", "reading", "avg/year", "P95", "P99" }
                        { printf "  %-18s %-22s %10s %10s %10s\n", $1, $2, $3, $4, $5 }'
}

# Runs a screening query and prints its verdict. $1 branch, $2 label, $3 query text
run_query() {
  local branch="$1" label="$2" q="$3"
  echo -e "\n  ${YEL}Query:${RST} $label"
  echo -e "  ${YEL}Expression:${RST} $q"
  local result satisfied proportion range_size satisfying_count
  result=$(send_query "$branch" "$q")
  satisfied=$(echo "$result" | jq -r '.satisfied')
  proportion=$(echo "$result" | jq -r '.proportion')
  range_size=$(echo "$result" | jq -r '.rangeSize')
  satisfying_count=$(echo "$result" | jq -r '.satisfyingCount')
  # A rejected query carries no verdict. Report it as an error rather than
  # printing it as an unsatisfied result, which would read as a real answer.
  if [[ "$satisfied" != "true" && "$satisfied" != "false" ]]; then
    fail "Query rejected by the server:"
    echo "$result" | jq . 2>/dev/null || echo "  $result"
    exit 1
  fi
  if [[ "$satisfied" == "true" ]]; then
    echo -e "  ${GRN}✔ SATISFIED${RST}  proportion=$proportion  ($satisfying_count / $range_size nodes)"
  else
    echo -e "  ${RED}✘ NOT SATISFIED${RST}  proportion=$proportion  ($satisfying_count / $range_size nodes)"
  fi
}

header "Demo: Mitigations walkthrough ($CLIENT)"
info "Server: $BASE"

# ── Step 1: Bootstrap workspace + tree (commit C1) ───────────────────────────
header "Step 1 — Bootstrap workspace + tree (C1 Create)"

BOOTSTRAP=$(bootstrap) || {
  fail "No answer from $BASE. Start the stack: docs/user/MITIGATION-WALKTHROUGH.md, What you need."
  exit 1
}
WS_KEY=$(echo "$BOOTSTRAP" | jq -r '.workspaceKey // empty')
TREE_ID=$(echo "$BOOTSTRAP" | jq -r '.tree.id // empty')
EXPIRES=$(echo "$BOOTSTRAP" | jq -r '.expiresAt // empty')

if [[ -z "$WS_KEY" || -z "$TREE_ID" ]]; then
  fail "Bootstrap failed:"; echo "$BOOTSTRAP" | jq . 2>/dev/null || echo "$BOOTSTRAP"; exit 1
fi
ok "Workspace key : $WS_KEY"
ok "Tree ID       : $TREE_ID"
ok "Expires at    : $EXPIRES"

# ── Step 2: Read the structure ───────────────────────────────────────────────
header "Step 2 — Read the tree's structure"

structure main | jq -r '
  (.tree.nodes | map(.RiskPortfolio // .RiskLeaf) | map({(.id): .name}) | add) as $names
  | .tree.nodes[]
  | if .RiskPortfolio then .RiskPortfolio | "  portfolio  \(.name)  \(.id)"
    else .RiskLeaf | "  leaf       \(.name)  (under \($names[.parentId]), likelihood \(.probability))  \(.id)" end'

ROOT_ID=$(node_id main "Enterprise")
CYBER_ID=$(node_id main "Cyber")
OPS_ID=$(node_id main "Operations")
COMPLIANCE_ID=$(node_id main "Compliance")
RANSOM_ID=$(node_id main "Ransomware")
FRAUD_ID=$(node_id main "Payment Fraud")
FINE_ID=$(node_id main "Regulatory Fine")
if [[ -z "$ROOT_ID" || -z "$CYBER_ID" || -z "$OPS_ID" || -z "$COMPLIANCE_ID" || -z "$RANSOM_ID" || -z "$FRAUD_ID" || -z "$FINE_ID" ]]; then
  fail "Could not read node ids from the structure — aborting."; exit 1
fi
PORTFOLIO_IDS="[\"$ROOT_ID\", \"$CYBER_ID\", \"$OPS_ID\", \"$COMPLIANCE_ID\"]"

# ── Step 3: Read the inherent figures ────────────────────────────────────────
header "Step 3 — Inherent figures (no mitigation selected)"

curves main '{}' "$PORTFOLIO_IDS" | print_figures
THRESHOLD=1000000
ok "Chance Enterprise loses \$1M or more in a year : $(exceedance main "$ROOT_ID" "$THRESHOLD" '{}')"

# ── Step 4: Two edits, then the history ──────────────────────────────────────
header "Step 4 — Edit the tree twice (C2, C3) and read its history"

put_tree main "C2  Ransomware probability 0.15 -> 0.25" \
  '.leaves |= map(if .name == "Ransomware" then .probability = 0.25 else . end)'

put_tree main "C3  add leaf Phishing 35% \$20K-\$400K under Cyber" \
  '.newLeaves = [ { name: "Phishing", parentName: "Cyber", probability: 0.35,
                    distributionShape: { distributionType: "Lognormal", minLoss: 20000, maxLoss: 400000 } } ]'

HISTORY=$(tree_history main)
MAIN_COMMITS=$(echo "$HISTORY" | jq '(.entries // []) | length' 2>/dev/null || echo 0)
if [[ "$MAIN_COMMITS" -lt 3 ]]; then
  fail "The history holds $MAIN_COMMITS commit(s), and 3 were made. Commit history needs the Irmin backend:"
  info "docker compose --profile persistence --profile frontend --env-file .env.irmin up -d --wait"
  exit 1
fi
echo "$HISTORY" | jq -r '.entries[] | "  \(.commitHash[0:8])  \(.operation.write.kind // .operation.kind)  \(.at)"'
C1_HASH=$(echo "$HISTORY" | jq -r '.entries[0].commitHash')

LEAVES_C1=$(structure main "$C1_HASH" | jq '[.tree.nodes[] | .RiskLeaf // empty] | length')
LEAVES_HEAD=$(structure main | jq '[.tree.nodes[] | .RiskLeaf // empty] | length')
ok "Leaves at C1 : $LEAVES_C1, at the head of main : $LEAVES_HEAD"

info "What changed from C1 to the head of main:"
changed_nodes "$C1_HASH" \
  | jq -r --argjson names "$(node_names main)" \
      '.status.entries[] | select(.status != "Identical") | "     \($names[.nodeId] // .nodeId) : \(.status)"'

P_AT_C1=$(exceedance main "$ROOT_ID" "$THRESHOLD" '{}' "$C1_HASH")
P_AT_HEAD=$(exceedance main "$ROOT_ID" "$THRESHOLD" '{}')
ok "Chance Enterprise loses \$1M or more — at C1 : $P_AT_C1, at the head of main : $P_AT_HEAD"

# ── Step 5: Fork a scenario ──────────────────────────────────────────────────
header "Step 5 — Fork the scenario \"with-controls\" from main"

SCENARIO=$(create_scenario "with-controls")
if [[ "$(echo "$SCENARIO" | jq -r '.name // empty')" == "with-controls" ]]; then
  ok "Scenario created : with-controls (forked from the head of main)"
else
  fail "Scenario 'with-controls' failed:"; echo "$SCENARIO" | jq . 2>/dev/null || echo "$SCENARIO"; exit 1
fi

# ── Step 6: Add three mitigations on the scenario ────────────────────────────
header "Step 6 — Add three mitigations on with-controls (S1)"

# A targeting predicate names nodes by id. These read as they are written in
# the query language, and jq puts them into the request as JSON strings.
LEAVES_UNDER_CYBER="leaf(x) /\\ descendant_of(x, node_id(\"$CYBER_ID\"))"
CYBER_ITSELF="eq(x, node_id(\"$CYBER_ID\"))"
FRAUD_ITSELF="eq(x, node_id(\"$FRAUD_ID\"))"

put_tree with-controls "S1  add MFA and awareness training, Cyber insurance, Fraud monitoring" \
  --arg leavesUnderCyber "$LEAVES_UNDER_CYBER" --arg cyber "$CYBER_ITSELF" --arg fraud "$FRAUD_ITSELF" \
  '.newMitigations = [
     { name: "MFA and awareness training",
       target: $leavesUnderCyber,
       spec: { stage: "LeafRelative", likelihood: { op: "Scale", factor: 0.5 }, severity: { op: "Keep" } },
       precedence: 0 },
     { name: "Cyber insurance",
       target: $cyber,
       spec: { stage: "ResultStage", pipeline: [ { op: "InsurancePolicy", deductible: 250000, limit: 5000000 } ] },
       precedence: 0 },
     { name: "Fraud monitoring",
       target: $fraud,
       spec: { stage: "LeafRelative", likelihood: { op: "Keep" }, severity: { op: "ScaleSeverity", factor: 0.6 } },
       precedence: 0 }
   ]'

MFA_ID=$(mitigation_id with-controls "MFA and awareness training")
INSURANCE_ID=$(mitigation_id with-controls "Cyber insurance")
FRAUD_MON_ID=$(mitigation_id with-controls "Fraud monitoring")

# ── Step 7: Add an absolute statement ────────────────────────────────────────
header "Step 7 — Add an absolute statement on with-controls (S2)"

put_tree with-controls "S2  add Regulatory Fine after remediation (likelihood is 3%)" \
  --arg fine "$FINE_ID" \
  '.newMitigations = [
     { name: "Regulatory Fine after remediation",
       spec: { stage: "LeafAbsolute",
               replacement: { replaces: "Likelihood", probability: 0.03 },
               anchor: $fine,
               stamp: { kind: "KeepIfUnchanged" } },
       precedence: 1000 }
   ]'

REMEDIATION_ID=$(mitigation_id with-controls "Regulatory Fine after remediation")

info "Each mitigation and the nodes it reaches:"
structure with-controls | jq -r --argjson names "$(node_names with-controls)" '
  (.tree.mitigations | map({(.id): .name}) | add) as $mitigations
  | .mitigationScopes[]
  | "     \($mitigations[.mitigationId]) [\(.outcome.kind)] : \([.outcome.scope[]? | $names[.]] | join(", "))"'

MITIGATIONS_SCENARIO=$(structure with-controls | jq '(.tree.mitigations // []) | length')
MITIGATIONS_MAIN=$(structure main | jq '(.tree.mitigations // []) | length')
ok "Mitigations on with-controls : $MITIGATIONS_SCENARIO, on main : $MITIGATIONS_MAIN"

# ── Step 8: Compare the figures with and without the mitigations ─────────────
header "Step 8 — Figures with and without the mitigations"

ALL="{\"fullScope\": [\"$MFA_ID\", \"$INSURANCE_ID\", \"$FRAUD_MON_ID\", \"$REMEDIATION_ID\"]}"
curves with-controls "$ALL" "$PORTFOLIO_IDS" | print_figures

echo
info "Chance Enterprise loses \$1M or more in a year, one selection at a time:"
show() { printf '     %-50s %s\n' "$1" "$(exceedance with-controls "$ROOT_ID" "$THRESHOLD" "$2")"; }
show "no mitigation (inherent)"                       '{}'
show "MFA and awareness training alone"               "{\"fullScope\": [\"$MFA_ID\"]}"
show "Cyber insurance alone"                          "{\"fullScope\": [\"$INSURANCE_ID\"]}"
show "Fraud monitoring alone"                         "{\"fullScope\": [\"$FRAUD_MON_ID\"]}"
show "Regulatory Fine after remediation alone"        "{\"fullScope\": [\"$REMEDIATION_ID\"]}"
show "MFA and awareness training at Ransomware only"  "{\"nodesOnly\": [{\"node\": \"$RANSOM_ID\", \"mitigation\": \"$MFA_ID\"}]}"
show "all four (residual)"                            "$ALL"

P_INHERENT=$(exceedance with-controls "$ROOT_ID" "$THRESHOLD" '{}')
P_RESIDUAL=$(exceedance with-controls "$ROOT_ID" "$THRESHOLD" "$ALL")

# ── Step 9: Screening queries, inherent against residual ─────────────────────
header "Step 9 — The same screening query, inherent and residual"

run_query with-controls \
  "Do at least two thirds of the leaves have a 5% or higher chance of losing \$500K or more — with no mitigation?" \
  'Q[>=]^{2/3} x (leaf(x), gt_prob(lec(x, 500000, "inherent"), 0.05))'

run_query with-controls \
  "The same question with every mitigation applied" \
  'Q[>=]^{2/3} x (leaf(x), gt_prob(lec(x, 500000, "residual"), 0.05))'

# ── Step 10: A stale absolute statement, and re-affirming it ─────────────────
header "Step 10 — Re-estimate Regulatory Fine (S3), then re-affirm the statement (S4)"

put_tree with-controls "S3  Regulatory Fine max loss 12M -> 20M, statement sent back unchanged" \
  '.leaves |= map(if .name == "Regulatory Fine" then .distributionShape.maxLoss = 20000000 else . end)'
STALE_AFTER_EDIT=$(structure with-controls | jq '.staleMitigationIds | length')
ok "Stale statements after the re-estimate : $STALE_AFTER_EDIT"

put_tree with-controls "S4  re-affirm Regulatory Fine after remediation" \
  '.mitigations |= map(if .name == "Regulatory Fine after remediation" then .spec.stamp = { kind: "Restamp" } else . end)'
STALE_AFTER_RESTAMP=$(structure with-controls | jq '.staleMitigationIds | length')
ok "Stale statements after re-affirming : $STALE_AFTER_RESTAMP"

SCENARIO_COMMITS=$(tree_history with-controls | jq '.entries | length')

# ── Done (summary prints LAST) ────────────────────────────────────────────────
header "Done — workspace info"
ok "Workspace key : $WS_KEY"
ok "Tree ID       : $TREE_ID   (Regional Bank Risk Register)"
ok "Branch main   : $MAIN_COMMITS commits, $MITIGATIONS_MAIN mitigations"
ok "with-controls : $SCENARIO_COMMITS commits, $MITIGATIONS_SCENARIO mitigations"
ok "Stale         : $STALE_AFTER_EDIT after the re-estimate, $STALE_AFTER_RESTAMP after re-affirming"
ok "Enterprise, chance of \$1M or more : inherent $P_INHERENT, residual $P_RESIDUAL"
ok "Expires at    : $EXPIRES"
ok "Open in app   : $BASE/w/$WS_KEY"
info "Re-run anytime — the workspace key remains valid until expiry."
