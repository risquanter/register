#!/usr/bin/env bash
# Stages the app for manual screenshot clipping (deck WS-9 UI shots).
# Bootstraps (or reuses) the enterprise demo workspace and prints, for each owed
# screenshot, the URL to open + the click-path + what to clip. Open the URL in
# the IDE's Simple Browser (or any browser) and clip the region yourself.
#
# Usage:
#   ./stage-screenshots.sh                 # bootstrap a fresh workspace
#   WS_KEY=... TREE_ID=... ./stage-screenshots.sh   # reuse an existing one
#
# Prereqs: full stack incl. frontend  ->  docker compose --profile frontend up -d
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
API="${API:-http://localhost:8090}"     # register-server (bootstrap)
UI="${UI:-http://localhost:18080}"      # frontend (screenshots)

# --- frontend reachable? ---
if ! curl -sf -o /dev/null "$UI/"; then
  echo "!! frontend not reachable at $UI"
  echo "   start it:  docker compose --profile frontend up -d"
  exit 1
fi

# --- get a workspace (reuse if provided; else bootstrap once — 5 creates/IP/h) ---
if [[ -n "${WS_KEY:-}" && -n "${TREE_ID:-}" ]]; then
  echo "Reusing WS_KEY=$WS_KEY  TREE_ID=$TREE_ID"
else
  echo "Bootstrapping enterprise demo tree (counts against the 5/h create limit) …"
  resp="$(curl -s -X POST "$API/workspaces" -H 'Content-Type: application/json' \
            --data-binary @"$HERE/demo-enterprise-tree.json")"
  WS_KEY="$(echo "$resp" | jq -r .workspaceKey)"
  TREE_ID="$(echo "$resp" | jq -r .tree.id)"
  if [[ "$WS_KEY" == "null" || -z "$WS_KEY" ]]; then
    echo "!! bootstrap failed:"; echo "$resp" | jq . 2>/dev/null || echo "$resp"; exit 1
  fi
fi

WURL="$UI/w/$WS_KEY"
echo
echo "════════════════════════════════════════════════════════════════════"
echo "  Workspace ready.  Open this in the IDE Simple Browser:"
echo "      $WURL"
echo "  (Section/form state is in-app only — the URL always shows /w/<key>;"
echo "   navigate the rest by clicking. Tree name: Financial Services Enterprise Risk)"
echo "════════════════════════════════════════════════════════════════════"
echo
cat <<EOF
SHOT LIST — open $WURL, then per shot:

[slide 17]  Distribution-Preview form (lognormal / CI mode)
   Click: Tree builder → open leaf "Cloud Provider Outage" (or "Data Breach PII")
          → the edit form shows the CI inputs + live distribution preview.
   Clip:  the form card incl. the preview curve.
   NOTE:  the PDF y-axis (~0.0000007) is a DENSITY per euro, not a probability,
          and carries no meaning (units artifact; area=1). The 30% occurrence
          prob is NOT in this curve. Crop the y-tick numbers; only the SHAPE
          matters (Masse/Tail). Full analysis: deck Backup B8.

[slide 18]  Metalog / expert-quantile input
   Click: open leaf "Ransomware Attack" (expert mode) → percentile/quantile rows
          with the fitted curve preview.
   Clip:  the quantile table + preview.

[slide 27]  5 demo-step shots (UI tour)
   1 tree overview (whole hierarchy)     — Tree builder / tree view
   2 a leaf's card + distribution        — open any leaf
   3 LEC on a leaf                        — Analyze → select leaf → LEC panel
   4 LEC on a branch + at the root        — Analyze → "Technology and Cyber", then "Enterprise Risk"
   5 scenario: bump Ransomware P, re-view root LEC
   Clip:  each panel individually.

[slide 29]  Analyze view + query pane (VQL)
   Click: sidebar → Analyze → select tree "Financial Services Enterprise Risk".
   Paste into the query pane (verified — gives erfüllt/nicht erfüllt live):
       Q[>=]^{1/2} x (leaf_descendant_of(x, "Technology and Cyber"), gt_loss(p95(x), 2000000))
       Q[>=]^{1/2} x (leaf_descendant_of(x, "Third-Party and Supply Chain"), gt_loss(p95(x), 2000000))
   Clip:  the query input + the result/highlighted nodes.

[slide 3]   Tinder threat model — EXTERNAL image, source it yourself (not this app).

Reuse this same workspace later without a new create:
   WS_KEY=$WS_KEY TREE_ID=$TREE_ID ./stage-screenshots.sh
EOF

echo "════════════════════════════════════════════════════════════════════"
echo "Workspace info"
echo "  Workspace key : $WS_KEY"
echo "  Tree ID       : $TREE_ID"
echo "  Open in app   : $WURL"
