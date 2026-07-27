#!/usr/bin/env bash
# =============================================================================
# stage-history-slider-curl.sh — Analyze "History / Time Travel" staging (curl)
#
# Stages a workspace whose tree has a deliberately rich commit history so the
# Analyze history slider (Phase E, Slice E-A) has real range to browse: several
# edits, a leaf that only appears partway through, and one revert commit. A
# forked scenario carries its own extra edits for cross-branch and
# past-vs-current comparison. Prints the workspace URL and a click-path per
# test at the end.
#
# History needs the Irmin backend (the in-memory repository keeps no commit
# history). Bring the persistence stack up first.
#
# Requires: curl, jq
#
# Usage:  ./examples/stage-history-slider-curl.sh [base_url]
#         default base_url: http://localhost:18080 (nginx proxies API to register-server)
#         Full manual-review stack:
#           docker compose --profile persistence --profile frontend --env-file .env.irmin up -d --build
#
# Commit history built on tree "Cyber Risk Register" (branch main), oldest first:
#   C1 Create  Root + Ransomware 10% $50K-$500K, Data Breach 8% $80K-$1.2M
#   C2 Update  Ransomware probability 10% -> 18%
#   C3 Update  Data Breach max loss $1.2M -> $2.5M
#   C4 Update  add leaf Insider Threat 5% $30K-$400K      <- node appears here
#   C5 Update  Insider Threat probability 5% -> 12%
#   C6 Revert  back to C4  (undoes C5; Insider Threat -> 5% again)
#
#   Scenario "mitigated" (forked from main head), its own edits:
#     M1 Update  Ransomware 18% -> 6%
#     M2 Update  Data Breach probability 8% -> 3%
#   Scenario "pre-insider-audit" — forked from commit C2 (fork-from-history, E5),
#     a branch that starts before Insider Threat ever existed.
# =============================================================================
set -euo pipefail

BASE=${1:-http://localhost:18080}

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

# distributionShape JSON for a lognormal leaf: $1 min, $2 max
dist() { printf '{ "distributionType": "lognormal", "minLoss": %s, "maxLoss": %s, "percentiles": null, "quantiles": null, "terms": null }' "$1" "$2"; }

# Newest commit hash on a branch (history is oldest-first, so last entry = head).
# $1 branch
head_hash() {
  curl -s "$BASE/w/$WS_KEY/risk-trees/$TREE/history?n=50" -H "X-Branch: $1" \
    | jq -r '.entries[-1].commitHash // empty'
}

# Read a node id by name from the current structure on a branch.
# $1 branch, $2 node name
node_id() {
  curl -s "$BASE/w/$WS_KEY/risk-trees/$TREE/structure" -H "X-Branch: $1" \
    | jq -r --arg n "$2" '.nodes[] | (.RiskLeaf // .RiskPortfolio // .) | select(.name==$n) | .id' | head -1
}

header "Demo: Analyze History / Time Travel (curl)"
info "Server: $BASE"

# ── Step 1: Bootstrap workspace + tree (commit C1) ───────────────────────────
header "Step 1 — Bootstrap workspace + tree (C1 Create)"

BOOTSTRAP=$(curl -s -X POST "$BASE/workspaces" \
  -H 'Content-Type: application/json' \
  -d "{
    \"name\": \"Cyber Risk Register\",
    \"portfolios\": [ { \"name\": \"Root\", \"parentName\": null } ],
    \"leaves\": [
      { \"name\": \"Ransomware\",  \"parentName\": \"Root\", \"probability\": 0.10, \"distributionShape\": $(dist 50000 500000) },
      { \"name\": \"Data Breach\", \"parentName\": \"Root\", \"probability\": 0.08, \"distributionShape\": $(dist 80000 1200000) }
    ]
  }")

WS_KEY=$(echo "$BOOTSTRAP" | jq -r '.workspaceKey // empty')
TREE=$(echo "$BOOTSTRAP" | jq -r '.tree.id // empty')
EXPIRES=$(echo "$BOOTSTRAP" | jq -r '.expiresAt // empty')

if [[ -z "$WS_KEY" || -z "$TREE" ]]; then
  fail "Bootstrap failed:"; echo "$BOOTSTRAP" | jq . 2>/dev/null || echo "$BOOTSTRAP"; exit 1
fi
ok "Workspace key : $WS_KEY"
ok "Tree ID       : $TREE"
ok "Expires at    : $EXPIRES"

ROOT_ID=$(node_id main "Root")
RANSOM_ID=$(node_id main "Ransomware")
BREACH_ID=$(node_id main "Data Breach")
if [[ -z "$ROOT_ID" || -z "$RANSOM_ID" || -z "$BREACH_ID" ]]; then
  fail "Could not read node ids from C1 structure — aborting."; exit 1
fi

# Full-replacement PUT on a branch. Every existing node is re-sent; anything in
# $EXTRA_NEW_LEAVES is created. $1 branch, $2 label, then env vars below set the
# per-commit values.
put_tree() {
  local branch="$1" label="$2"
  local body
  body=$(cat <<JSON
{
  "name": "Cyber Risk Register",
  "portfolios": [ { "id": "$ROOT_ID", "name": "Root", "parentName": null } ],
  "leaves": [
    { "id": "$RANSOM_ID", "name": "Ransomware",  "parentName": "Root", "probability": $RANSOM_P, "distributionShape": $(dist "$RANSOM_MIN" "$RANSOM_MAX") },
    { "id": "$BREACH_ID", "name": "Data Breach", "parentName": "Root", "probability": $BREACH_P, "distributionShape": $(dist "$BREACH_MIN" "$BREACH_MAX") }${INSIDER_EXISTING:-}
  ],
  "newPortfolios": [],
  "newLeaves": [ ${NEW_LEAVES:-} ]
}
JSON
)
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/w/$WS_KEY/risk-trees/$TREE" \
    -H 'Content-Type: application/json' -H "X-Branch: $branch" -d "$body")
  if [[ "$code" == "200" ]]; then ok "$label"; else fail "$label — HTTP $code"; echo "$body"; exit 1; fi
}

# Baseline values (C1 state); each commit overrides only what it changes.
RANSOM_P=0.10 RANSOM_MIN=50000 RANSOM_MAX=500000
BREACH_P=0.08 BREACH_MIN=80000 BREACH_MAX=1200000
INSIDER_EXISTING=""; NEW_LEAVES=""

# ── Step 2: C2 — bump Ransomware probability ─────────────────────────────────
header "Step 2 — C2 Update (Ransomware 10% -> 18%)"
RANSOM_P=0.18
put_tree main "Ransomware probability 0.10 -> 0.18"
C2_HASH=$(head_hash main)   # pre-Insider commit — fork-from-history source below

# ── Step 3: C3 — widen Data Breach loss ──────────────────────────────────────
header "Step 3 — C3 Update (Data Breach max \$1.2M -> \$2.5M)"
BREACH_MAX=2500000
put_tree main "Data Breach max loss 1.2M -> 2.5M"

# ── Step 4: C4 — add Insider Threat leaf ─────────────────────────────────────
header "Step 4 — C4 Update (add leaf Insider Threat)"
NEW_LEAVES="{ \"name\": \"Insider Threat\", \"parentName\": \"Root\", \"probability\": 0.05, \"distributionShape\": $(dist 30000 400000) }"
put_tree main "add leaf Insider Threat 5% \$30K-\$400K"
NEW_LEAVES=""
INSIDER_ID=$(node_id main "Insider Threat")
if [[ -z "$INSIDER_ID" ]]; then fail "Insider Threat id not found after C4 — aborting."; exit 1; fi
REVERT_TARGET=$(head_hash main)   # C4 head — the revert target for C6
ok "C4 commit (revert target) : $REVERT_TARGET"

# ── Step 5: C5 — bump Insider Threat probability ─────────────────────────────
header "Step 5 — C5 Update (Insider Threat 5% -> 12%)"
INSIDER_EXISTING=",
    { \"id\": \"$INSIDER_ID\", \"name\": \"Insider Threat\", \"parentName\": \"Root\", \"probability\": 0.12, \"distributionShape\": $(dist 30000 400000) }"
put_tree main "Insider Threat probability 0.05 -> 0.12"

# ── Step 6: C6 — revert to C4 ────────────────────────────────────────────────
header "Step 6 — C6 Revert (back to C4; Insider Threat -> 5% again)"
REVERT_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/w/$WS_KEY/risk-trees/$TREE/revert" \
  -H 'Content-Type: application/json' -H "X-Branch: main" \
  -d "{ \"toCommit\": \"$REVERT_TARGET\" }")
if [[ "$REVERT_CODE" == "200" ]]; then ok "Reverted to C4 as a forward commit"; else fail "Revert returned HTTP $REVERT_CODE"; fi

# ── Step 7: Scenario "mitigated" + its own edits ─────────────────────────────
header "Step 7 — Scenario \"mitigated\" (forked from main) + edits M1, M2"
SC=$(curl -s -X POST "$BASE/w/$WS_KEY/scenarios" \
  -H 'Content-Type: application/json' \
  -d '{"name": "mitigated", "source": {"type": "branch", "name": "main"}}')
if echo "$SC" | jq -e '.name // .head // .branch' >/dev/null 2>&1; then
  ok "Scenario created : mitigated (forked from main head)"
else
  fail "Scenario 'mitigated' failed:"; echo "$SC" | jq . 2>/dev/null || echo "$SC"; exit 1
fi

# Fork-from-history (E5): a scenario rooted at C2, before Insider Threat existed.
SCH=$(curl -s -X POST "$BASE/w/$WS_KEY/scenarios" \
  -H 'Content-Type: application/json' \
  -d "{\"name\": \"pre-insider-audit\", \"source\": {\"type\": \"commit\", \"hash\": \"$C2_HASH\"}}")
if echo "$SCH" | jq -e '.name // .head // .branch' >/dev/null 2>&1; then
  ok "Scenario created : pre-insider-audit (forked from commit C2)"
else
  fail "Scenario 'pre-insider-audit' failed:"; echo "$SCH" | jq . 2>/dev/null || echo "$SCH"; exit 1
fi

# Node ids on the mitigated branch (same ids as main after fork, re-read to be safe).
ROOT_ID=$(node_id mitigated "Root")
RANSOM_ID=$(node_id mitigated "Ransomware")
BREACH_ID=$(node_id mitigated "Data Breach")
INSIDER_ID=$(node_id mitigated "Insider Threat")
# head state after C6 = C4 state: Insider Threat present at 5%.
INSIDER_EXISTING=",
    { \"id\": \"$INSIDER_ID\", \"name\": \"Insider Threat\", \"parentName\": \"Root\", \"probability\": 0.05, \"distributionShape\": $(dist 30000 400000) }"

RANSOM_P=0.06
put_tree mitigated "M1  Ransomware 18% -> 6% (mitigated)"
BREACH_P=0.03
put_tree mitigated "M2  Data Breach 8% -> 3% (mitigated)"

# ── Done ──────────────────────────────────────────────────────────────────────
header "Done — workspace info"
ok "Workspace key : $WS_KEY"
ok "Tree ID       : $TREE   (Cyber Risk Register)"
ok "Branch main   : 6 commits (C1 create .. C6 revert)"
ok "Branch mitig. : + M1, M2 on top of the fork"
ok "pre-insider-audit : forked from commit C2 (before Insider Threat)"
ok "Expires at    : $EXPIRES"
ok "Open in app   : $BASE/w/$WS_KEY"
info "Re-run anytime — the workspace key remains valid until expiry."

cat <<EOF

TEST LIST — Analyze tab, tree "Cyber Risk Register". Each slot card (the
baseline row and each "+ Compare tree" comparand) carries a history slider
under its branch picker: one stop per commit, oldest at the left, live head at
the right. Rewinding a slot reads it at that commit (read-only, pinned banner).

1  Slider range: baseline on (main, Tree) -> the slider shows 6 stops. Hover a
   stop -> tooltip = timestamp + short hash. Rightmost stop = live head.
2  Rewind reads the past: drag the baseline to C1 -> tree shows only Ransomware
   + Data Breach (no Insider Threat), curves match that older state, card locks
   read-only with a "Viewing <timestamp - hash>" banner. Slide back to head ->
   lock clears, Insider Threat returns.
3  Dropped selection notice (H3): at head, Ctrl+click "Insider Threat" -> its
   curve charts. Rewind the baseline to C1/C2/C3 (before it existed) -> the
   "not present at this point in time" notice appears and the curve drops.
   Slide forward past C4 -> it charts again.
4  Revert stop: the newest stop is a Revert commit (C6). Rewinding one stop
   back (C5) shows Insider Threat at 12%; head (C6, the revert of C5) shows it
   back at 5%.
5  Past-vs-current, same branch: baseline (main) at head; add a comparand row,
   point it at (main, Tree), rewind that comparand to C2 -> the baseline's tree
   shows the changed-node markers for what differs between C2 and head (Data
   Breach widened, Insider Threat added), and both curves overlay.
6  Cross-branch history: add a comparand -> (mitigated, Tree). Its own slider
   includes M1, M2 on top of the shared main history. Compare main head vs
   mitigated head -> Ransomware and Data Breach differ.
7  Independent sliders: rewind the baseline and a comparand to different
   commits at once -> each card reads its own pinned point; the charts overlay
   the two pinned states.
8  Fork-from-history: comparand -> (pre-insider-audit, Tree). This branch was
   forked from commit C2, so its head has no Insider Threat; compare it against
   main head -> Insider Threat and the widened Data Breach show as differences.
EOF
