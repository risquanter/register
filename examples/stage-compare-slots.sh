#!/usr/bin/env bash
# =============================================================================
# stage-compare-slots.sh — Analyze comparison-slot feature staging (curl)
#
# Stages a workspace with everything the Analyze "Compare" feature needs to be
# reviewed by hand: two trees (for cross-tree slots) and two scenarios, one with
# an edited leaf (for cross-branch overlays and the ✎ diff markers). Prints the
# workspace URL and a click-path per test at the end.
#
# Requires: curl, jq
#
# Usage:  ./examples/stage-compare-slots.sh [base_url]
#         default base_url: http://localhost:18080 (docker-compose --profile frontend; nginx proxies API calls through to register-server)
#         Full manual-review stack:
#           docker compose --profile persistence --profile frontend --env-file .env.irmin up -d --build
#
# Staged data:
#   Tree A "Compare Demo Tree A"
#     Root (portfolio)
#     ├── Leaf One   lognormal  10%  $40K-$320K   (bumped to 30% on scenario alpha)
#     └── Leaf Two   lognormal  10%  $20K-$150K
#   Tree B "Compare Demo Tree B"  — deliberately ~10× heavier than Tree A, so a
#   cross-tree overlay shows clearly separated curves, not two near-identical ones
#     Root (portfolio)
#     ├── Leaf B1    lognormal  30%  $400K-$3M
#     └── Leaf B2    lognormal  20%  $150K-$1.2M
#   Scenarios (forked from main): alpha (Leaf One 10% -> 30% -> visibly higher
#   curve + ✎ diff vs main), beta (unchanged -> overlays exactly on main)
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

header "Demo: Analyze Comparison Slots (curl)"
info "Server: $BASE"

# ── Step 1: Bootstrap workspace + Tree A ──────────────────────────────────────
header "Step 1 — Bootstrap workspace + Tree A"

BOOTSTRAP=$(curl -s -X POST "$BASE/workspaces" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Compare Demo Tree A",
    "portfolios": [
      { "name": "Root", "parentName": null }
    ],
    "leaves": [
      {
        "name": "Leaf One",
        "parentName": "Root",
        "probability": 0.1,
        "distributionShape": { "distributionType": "lognormal", "minLoss": 40000, "maxLoss": 320000, "percentiles": null, "quantiles": null, "terms": null }
      },
      {
        "name": "Leaf Two",
        "parentName": "Root",
        "probability": 0.1,
        "distributionShape": { "distributionType": "lognormal", "minLoss": 20000, "maxLoss": 150000, "percentiles": null, "quantiles": null, "terms": null }
      }
    ]
  }')

WS_KEY=$(echo "$BOOTSTRAP" | jq -r '.workspaceKey // empty')
TREE_A=$(echo "$BOOTSTRAP" | jq -r '.tree.id // empty')
EXPIRES=$(echo "$BOOTSTRAP" | jq -r '.expiresAt // empty')

if [[ -z "$WS_KEY" || -z "$TREE_A" ]]; then
  fail "Bootstrap failed:"; echo "$BOOTSTRAP" | jq . 2>/dev/null || echo "$BOOTSTRAP"; exit 1
fi
ok "Workspace key : $WS_KEY"
ok "Tree A ID     : $TREE_A"
ok "Expires at    : $EXPIRES"

# ── Step 2: Create a second tree (Tree B) on main ─────────────────────────────
header "Step 2 — Create Tree B"

TREE_B_RESP=$(curl -s -X POST "$BASE/w/$WS_KEY/risk-trees" \
  -H 'Content-Type: application/json' -H "X-Branch: main" \
  -d '{
    "name": "Compare Demo Tree B",
    "portfolios": [
      { "name": "Root", "parentName": null }
    ],
    "leaves": [
      {
        "name": "Leaf B1",
        "parentName": "Root",
        "probability": 0.3,
        "distributionShape": { "distributionType": "lognormal", "minLoss": 400000, "maxLoss": 3000000, "percentiles": null, "quantiles": null, "terms": null }
      },
      {
        "name": "Leaf B2",
        "parentName": "Root",
        "probability": 0.2,
        "distributionShape": { "distributionType": "lognormal", "minLoss": 150000, "maxLoss": 1200000, "percentiles": null, "quantiles": null, "terms": null }
      }
    ]
  }')

TREE_B=$(echo "$TREE_B_RESP" | jq -r '.id // .tree.id // empty')
if [[ -z "$TREE_B" ]]; then
  fail "Tree B creation failed:"; echo "$TREE_B_RESP" | jq . 2>/dev/null || echo "$TREE_B_RESP"; exit 1
fi
ok "Tree B ID     : $TREE_B"

# ── Step 3: Fork two scenarios from main ──────────────────────────────────────
header "Step 3 — Create scenarios (forked from main)"

mk_scenario() {
  local name="$1"
  local resp
  resp=$(curl -s -X POST "$BASE/w/$WS_KEY/scenarios" \
    -H 'Content-Type: application/json' \
    -d "{\"name\": \"$name\", \"source\": {\"type\": \"branch\", \"name\": \"main\"}}")
  if echo "$resp" | jq -e '.name // .head // .branch' >/dev/null 2>&1; then
    ok "Scenario created : $name"
  else
    fail "Scenario '$name' failed:"; echo "$resp" | jq . 2>/dev/null || echo "$resp"; exit 1
  fi
}
mk_scenario "alpha"
mk_scenario "beta"

# ── Step 4: Bump Leaf One's probability on scenario alpha ─────────────────────
# Full-replacement update (every existing node re-sent): Leaf One's probability
# changes, Leaf Two and Root are re-sent unchanged. Node IDs come from the tree
# structure on the alpha branch; .rootId gives the portfolio id directly.
header "Step 4 — Edit Leaf One on scenario alpha (creates the diff)"

STRUCT=$(curl -s "$BASE/w/$WS_KEY/risk-trees/$TREE_A/structure" -H "X-Branch: alpha")
ROOT_ID=$(echo "$STRUCT" | jq -r '.rootId // empty')
# node JSON may wrap a sealed subtype as {"RiskLeaf":{…}} or be flat; tolerate both.
leaf_id() { echo "$STRUCT" | jq -r --arg n "$1" '.nodes[] | (.RiskLeaf // .) | select(.name==$n and (.probability!=null)) | .id' | head -1; }
LEAF1_ID=$(leaf_id "Leaf One")
LEAF2_ID=$(leaf_id "Leaf Two")

if [[ -z "$ROOT_ID" || -z "$LEAF1_ID" || -z "$LEAF2_ID" ]]; then
  fail "Could not read node ids from the tree structure — skipping the automatic edit."
  info "Do it by hand: Design tab -> switch branch to 'alpha' -> open 'Leaf One' -> set probability to 0.30 -> save."
  echo "$STRUCT" | jq . 2>/dev/null | head -40 || echo "$STRUCT"
else
  UPDATE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/w/$WS_KEY/risk-trees/$TREE_A" \
    -H 'Content-Type: application/json' -H "X-Branch: alpha" \
    -d "{
      \"name\": \"Compare Demo Tree A\",
      \"portfolios\": [ { \"id\": \"$ROOT_ID\", \"name\": \"Root\", \"parentName\": null } ],
      \"leaves\": [
        { \"id\": \"$LEAF1_ID\", \"name\": \"Leaf One\", \"parentName\": \"Root\", \"probability\": 0.3,
          \"distributionShape\": { \"distributionType\": \"lognormal\", \"minLoss\": 40000, \"maxLoss\": 320000, \"percentiles\": null, \"quantiles\": null, \"terms\": null } },
        { \"id\": \"$LEAF2_ID\", \"name\": \"Leaf Two\", \"parentName\": \"Root\", \"probability\": 0.1,
          \"distributionShape\": { \"distributionType\": \"lognormal\", \"minLoss\": 20000, \"maxLoss\": 150000, \"percentiles\": null, \"quantiles\": null, \"terms\": null } }
      ],
      \"newPortfolios\": [],
      \"newLeaves\": []
    }")
  if [[ "$UPDATE" == "200" ]]; then
    ok "Leaf One probability 0.10 -> 0.30 on scenario alpha (diff vs main)"
  else
    fail "Leaf edit returned HTTP $UPDATE — bump 'Leaf One' by hand on branch 'alpha' if ✎ markers don't show."
  fi
fi

cat <<EOF

TEST LIST — Analyze tab. The right "Load Trees" panel holds the baseline row
(row 0) plus comparand rows added with "+ Compare tree"; each comparand row
has an eye (hide) and a "-" (remove); the baseline row has only an eye. A
binary Overlay / Side-by-side toggle sits in the panel. Comparison is
implicit — it exists when 2+ rows have loaded trees with charted nodes.

1  Regression (no comparand rows): baseline row, pick Tree A -> single-branch
   chart/tree exactly as before; no ✎.
2  Overlay cross-branch: baseline (main, Tree A), Ctrl+click a node; add a row
   -> (alpha, Tree A) -> card expands, ✎ on Leaf One + Root, counterpart
   charted -> two curves. Layout toggle -> Side-by-side -> two panels.
3  Cross-tree same branch: add a row -> (main, Tree B) -> main-on-A vs
   main-on-B appears, no ✎.
4  Duplicate-pair greying: with a row = (alpha, Tree A), add another row and
   try (alpha, Tree A) -> the duplicating branch/tree value is greyed
   (unselectable); pickers never blank.
5  Earlier-wins + reversible browse: row A = (main, pinned Tree B), row B =
   (main, follow-active), baseline on Tree A -> both engage. Browse the
   baseline tree A->B -> exactly ONE main series/panel/card (earlier row
   wins). Browse B->A -> row B re-engages, never reset.
6  Eye (hide): click a comparand row's eye -> its curves/panel vanish, card +
   ✎ stay; eye off -> return. Baseline eye -> baseline curves vanish; Run a
   query while hidden -> no visible change; unhide -> query hits now charted.
7  Query isolation: with a comparand charted, Run a query -> only the
   baseline's charted set changes; the comparand's selection is untouched.
8  Resets: (a) row (alpha, follow-active), switch baseline branch main->alpha
   -> row resets to placeholder. (b) row (alpha, pinned Tree B), switch
   baseline branch main->alpha -> row SURVIVES. (c) delete scenario beta -> a
   row on beta resets.
9  Pinned-tree recovery: (a) pin a row to Tree B, delete Tree B -> row clears.
   (c) follow-active row on alpha; baseline browses to a tree missing on alpha
   -> row DISENGAGES but is NOT cleared; browse back -> re-engages.
10 Collapse sticks: expand a comparand card, collapse it, refresh the baseline
   tree (same tree) -> it stays collapsed; re-point the row at a different
   tree -> it re-expands.
11 Add/remove + cap: "+ Compare tree" adds rows up to 7 comparands (8 total),
   then disables; "-" removes a row (baseline has none); new comparand cards
   start collapsed, expand on tree load.
12 Palette: same colour family for baseline + a comparand -> overlay draws
   both in that family, swatches match. Distinct families -> distinct colours.
EOF

# ── Done (summary prints LAST) ────────────────────────────────────────────────
header "Done — workspace info"
ok "Workspace key : $WS_KEY"
ok "Tree A ID     : $TREE_A   (Compare Demo Tree A — Leaf One, Leaf Two)"
ok "Tree B ID     : $TREE_B   (Compare Demo Tree B — Leaf B1, Leaf B2; ~10× heavier than Tree A)"
ok "Scenarios     : alpha (Leaf One 10% -> 30%), beta (unchanged)"
ok "Expires at    : $EXPIRES"
ok "Open in app   : $BASE/w/$WS_KEY"
info "Re-run anytime — the workspace key remains valid until expiry."
