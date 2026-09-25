#!/usr/bin/env bash
# Tests for the inventory approval script.
#
# Every case runs against a throwaway directory tree built under a temporary
# directory. Nothing here reads or writes the repository's real approval token,
# real inventory documents, or real plans.
#
# The script under test refuses to run unless stdin and stdout are terminals,
# so each case is run through script(1), which allocates a pseudo-terminal.
#
# Usage:
#   bash tests/protocol/approve-inventory-test.sh                 test the installed script
#   bash tests/protocol/approve-inventory-test.sh /path/to/candidate
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UNDER_TEST="${1:-$REPO/.claude/bin/approve-inventory}"

if [ ! -f "$UNDER_TEST" ]; then
  echo "no script to test at $UNDER_TEST" >&2
  exit 2
fi
command -v script >/dev/null 2>&1 || { echo "script(1) is required" >&2; exit 2; }

PASS=0
FAIL=0
FAILED_CASES=""

# build_tree <dir> — a throwaway repository root the script can run against.
build_tree() {
  local d="$1"
  mkdir -p "$d/.claude/bin" "$d/.claude/protocol" "$d/docs/dev/plans"
  mkdir -p "$d/modules/server/src/main/scala"
  cp "$UNDER_TEST" "$d/.claude/bin/approve-inventory"
  chmod +x "$d/.claude/bin/approve-inventory"
  printf '# PLAN-TEST\n\n## File inventory\n\nsee the inventory document.\n' \
    > "$d/docs/dev/plans/PLAN-TEST.md"
  printf 'object Existing\n' > "$d/modules/server/src/main/scala/Existing.scala"
  printf 'object Other\n'    > "$d/modules/server/src/main/scala/Other.scala"
}

# run_in <dir> <args...> — run the script with a pseudo-terminal attached.
# Prints its combined output; returns its exit status.
run_in() {
  local d="$1"; shift
  local args="$*"
  ( cd "$d" && script -qec ".claude/bin/approve-inventory $args" /dev/null 2>&1 )
}

check() {
  local name="$1" condition="$2" detail="${3:-}"
  if [ "$condition" = "1" ]; then
    PASS=$((PASS + 1))
    printf '  pass  %s\n' "$name"
  else
    FAIL=$((FAIL + 1))
    FAILED_CASES="$FAILED_CASES
  - $name${detail:+
      $detail}"
    printf '  FAIL  %s\n' "$name"
    [ -n "$detail" ] && printf '        %s\n' "$detail"
  fi
}

# ---------------------------------------------------------------- case 1
echo "case 1 — a valid pending file produces the expected bullets and token"
D=$(mktemp -d); build_tree "$D"
printf 'docs/dev/plans/PLAN-TEST.md\nmodules/server/src/main/scala/Existing.scala\n' \
  > "$D/.claude/protocol/pending"
OUT=$(run_in "$D")
B=$(grep -c '^- modules/server/src/main/scala/Existing.scala$' "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md" 2>/dev/null || echo 0)
T=$(cat "$D/.claude/protocol/approved" 2>/dev/null || true)
check "bullet written" "$([ "$B" = "1" ] && echo 1 || echo 0)" "inventory: $(cat "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md" 2>/dev/null | tr '\n' '|')"
check "token written" "$([ "$T" = "docs/dev/plans/PLAN-TEST.md" ] && echo 1 || echo 0)" "token: $T"
check "pending consumed" "$([ ! -f "$D/.claude/protocol/pending" ] && echo 1 || echo 0)"
rm -rf "$D"

# ---------------------------------------------------------------- case 2
echo "case 2 — an unmarked path that does not exist is rejected, nothing written"
D=$(mktemp -d); build_tree "$D"
printf 'docs/dev/plans/PLAN-TEST.md\nmodules/server/src/main/scala/Typo.scala\n' \
  > "$D/.claude/protocol/pending"
OUT=$(run_in "$D"); RC=$?
check "non-zero exit" "$([ "$RC" != "0" ] && echo 1 || echo 0)" "exit was $RC"
check "no inventory created" "$([ ! -f "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md" ] && echo 1 || echo 0)"
check "no token written" "$([ ! -f "$D/.claude/protocol/approved" ] && echo 1 || echo 0)"
check "pending kept" "$([ -f "$D/.claude/protocol/pending" ] && echo 1 || echo 0)"
check "message suggests the new marker" "$(echo "$OUT" | grep -q "mark the line 'new:" && echo 1 || echo 0)" "output: $(echo "$OUT" | tr '\n' '|')"
rm -rf "$D"

# ---------------------------------------------------------------- case 3
echo "case 3 — a path marked new: is accepted although it does not exist"
D=$(mktemp -d); build_tree "$D"
printf 'docs/dev/plans/PLAN-TEST.md\nnew: modules/server/src/main/scala/ToBeCreated.scala\n' \
  > "$D/.claude/protocol/pending"
OUT=$(run_in "$D")
B=$(grep -c '^- modules/server/src/main/scala/ToBeCreated.scala$' "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md" 2>/dev/null || echo 0)
check "marked new path authorized" "$([ "$B" = "1" ] && echo 1 || echo 0)" "output: $(echo "$OUT" | tr '\n' '|')"
check "marker stripped from the bullet" "$(grep -q 'new:' "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md" 2>/dev/null && echo 0 || echo 1)"
rm -rf "$D"

# ---------------------------------------------------------------- case 4
echo "case 4 — a path already listed is skipped and the inventory is unchanged"
D=$(mktemp -d); build_tree "$D"
printf 'docs/dev/plans/PLAN-TEST.md\nmodules/server/src/main/scala/Existing.scala\n' \
  > "$D/.claude/protocol/pending"
run_in "$D" >/dev/null
BEFORE=$(cat "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md")
printf 'docs/dev/plans/PLAN-TEST.md\nmodules/server/src/main/scala/Existing.scala\n' \
  > "$D/.claude/protocol/pending"
OUT=$(run_in "$D")
AFTER=$(cat "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md")
check "inventory unchanged on repeat" "$([ "$BEFORE" = "$AFTER" ] && echo 1 || echo 0)"
check "reported as already listed" "$(echo "$OUT" | grep -q 'already listed' && echo 1 || echo 0)" "output: $(echo "$OUT" | tr '\n' '|')"
rm -rf "$D"

# ---------------------------------------------------------------- case 5
echo "case 5 — amend leaves the pending file so a following approve can read it"
D=$(mktemp -d); build_tree "$D"
printf 'docs/dev/plans/PLAN-TEST.md\nmodules/server/src/main/scala/Existing.scala\n' \
  > "$D/.claude/protocol/pending"
run_in "$D" amend >/dev/null
check "amend wrote the bullet" "$(grep -q '^- modules/server/src/main/scala/Existing.scala$' "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md" 2>/dev/null && echo 1 || echo 0)"
check "amend wrote no token" "$([ ! -f "$D/.claude/protocol/approved" ] && echo 1 || echo 0)"
check "amend kept the pending file" "$([ -f "$D/.claude/protocol/pending" ] && echo 1 || echo 0)"
OUT=$(run_in "$D" approve); RC=$?
T=$(cat "$D/.claude/protocol/approved" 2>/dev/null || true)
check "following approve succeeded" "$([ "$RC" = "0" ] && echo 1 || echo 0)" "exit $RC, output: $(echo "$OUT" | tr '\n' '|')"
check "following approve wrote the token" "$([ "$T" = "docs/dev/plans/PLAN-TEST.md" ] && echo 1 || echo 0)" "token: $T"
check "approve consumed the pending file" "$([ ! -f "$D/.claude/protocol/pending" ] && echo 1 || echo 0)"
rm -rf "$D"

# ---------------------------------------------------------------- case 6
echo "case 6 — dry run changes nothing on disk"
D=$(mktemp -d); build_tree "$D"
printf 'docs/dev/plans/PLAN-TEST.md\nmodules/server/src/main/scala/Existing.scala\n' \
  > "$D/.claude/protocol/pending"
OUT=$(run_in "$D" --dry-run)
check "no inventory created" "$([ ! -f "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md" ] && echo 1 || echo 0)"
check "no token written" "$([ ! -f "$D/.claude/protocol/approved" ] && echo 1 || echo 0)"
check "pending kept" "$([ -f "$D/.claude/protocol/pending" ] && echo 1 || echo 0)"
check "reported what it would do" "$(echo "$OUT" | grep -q 'would add to' && echo 1 || echo 0)" "output: $(echo "$OUT" | tr '\n' '|')"
rm -rf "$D"

# ---------------------------------------------------------------- case 7
echo "case 7 — approve replaces the token and names the plan it drops"
D=$(mktemp -d); build_tree "$D"
printf '# PLAN-OTHER\n' > "$D/docs/dev/plans/PLAN-OTHER.md"
printf 'docs/dev/plans/PLAN-OTHER.md\n' > "$D/.claude/protocol/approved"
printf 'docs/dev/plans/PLAN-TEST.md\nmodules/server/src/main/scala/Existing.scala\n' \
  > "$D/.claude/protocol/pending"
OUT=$(run_in "$D")
T=$(cat "$D/.claude/protocol/approved")
check "token holds only the new plan" "$([ "$T" = "docs/dev/plans/PLAN-TEST.md" ] && echo 1 || echo 0)" "token: $T"
check "dropped plan is named" "$(echo "$OUT" | grep -q 'no longer approved: docs/dev/plans/PLAN-OTHER.md' && echo 1 || echo 0)" "output: $(echo "$OUT" | tr '\n' '|')"
rm -rf "$D"

# ---------------------------------------------------------------- case 8
echo "case 8 — naming an inventory document as the plan is rejected"
D=$(mktemp -d); build_tree "$D"
# The inventory document must exist, or the rejection could come from the
# "no such plan" branch instead of from the inventory-as-plan branch.
printf '# File inventory — PLAN-TEST.md\n\n- modules/server/src/main/scala/Other.scala\n' \
  > "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md"
printf 'docs/dev/plans/PLAN-TEST-INVENTORY.md\nmodules/server/src/main/scala/Existing.scala\n' \
  > "$D/.claude/protocol/pending"
OUT=$(run_in "$D"); RC=$?
check "non-zero exit" "$([ "$RC" != "0" ] && echo 1 || echo 0)" "exit $RC"
check "no doubled inventory created" "$([ ! -f "$D/docs/dev/plans/PLAN-TEST-INVENTORY-INVENTORY.md" ] && echo 1 || echo 0)"
check "no token written" "$([ ! -f "$D/.claude/protocol/approved" ] && echo 1 || echo 0)"
rm -rf "$D"

# ---------------------------------------------------------------- case 9
echo "case 9 — a path under .claude/ is refused"
D=$(mktemp -d); build_tree "$D"
printf 'docs/dev/plans/PLAN-TEST.md\n.claude/hooks/protocol-gate.sh\n' \
  > "$D/.claude/protocol/pending"
OUT=$(run_in "$D"); RC=$?
check "non-zero exit" "$([ "$RC" != "0" ] && echo 1 || echo 0)" "exit $RC"
check "nothing written" "$([ ! -f "$D/docs/dev/plans/PLAN-TEST-INVENTORY.md" ] && echo 1 || echo 0)"
rm -rf "$D"

# ---------------------------------------------------------------- summary
echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "RESULT: pass"
  exit 0
else
  echo "RESULT: fail"
  echo "failing checks:$FAILED_CASES"
  exit 1
fi
