#!/usr/bin/env bash
# PreToolUse gate enforcing the register working protocol (G1/G3/G6/G7).
#
# Two checks, dispatched on tool_name from the hook's stdin JSON:
#  - Bash: deny any command that references the approval-token path — the
#    token is user-owned; the agent must never create, refresh, read, or
#    delete it.
#  - Edit/Write/NotebookEdit on gated paths (modules/**, build.sbt): the
#    token must name an approved plan document, and the edited file must be
#    listed in that plan's file inventory. Approval is PLAN-SCOPED (user
#    ruling 2026-07-24): not time-based, not blanket — the token binds to one
#    plan's declared file set, so unplanned files are denied even while a
#    plan is approved (that denial IS the escalation trigger: stop, present,
#    wait).
#
#    Matching rule: only bullet lines ("- ...") under the plan's
#    "## File inventory" heading (up to the next "## " heading) authorize a
#    file. Prose elsewhere in the plan — including "Not touched:" notes —
#    never grants access, even if it names the path.
#
# The user approves a plan from their own terminal:
#   mkdir -p .claude/protocol && echo "docs/dev/PLAN-<name>.md" > .claude/protocol/approved
# Multiple concurrent plans: one repo-relative plan path per line.
# Revoke / close out: rm -rf .claude/protocol   (the agent flags plan
# completion in its landing report so the user knows when).
set -euo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
TOKEN="$ROOT/.claude/protocol/approved"

deny() {
  jq -n --arg reason "$1" \
    '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$reason}}'
  exit 0
}

INPUT="$(cat)"
TOOL="$(jq -r '.tool_name // empty' <<<"$INPUT")"

if [ "$TOOL" = "Bash" ]; then
  CMD="$(jq -r '.tool_input.command // empty' <<<"$INPUT")"
  case "$CMD" in
    *".claude/protocol"*)
      deny "Blocked: the approval token under .claude/protocol/ is user-owned. Only the user may create, refresh, read, or remove it (working-protocol G7). Ask the user to run the approval command themselves."
      ;;
  esac
  exit 0
fi

FILE="$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' <<<"$INPUT")"
[ -n "$FILE" ] || exit 0
case "$FILE" in
  /*) ABS="$FILE" ;;
  *)  ABS="$ROOT/$FILE" ;;
esac

case "$ABS" in
  "$ROOT"/modules/*|"$ROOT"/build.sbt) ;;
  *) exit 0 ;;
esac

REL="${ABS#"$ROOT"/}"

if [ ! -f "$TOKEN" ]; then
  deny "Blocked (working-protocol G3): no approved plan (token absent). Present the quality-gated plan document and wait. The user approves by running: mkdir -p .claude/protocol && echo \"docs/dev/PLAN-<name>.md\" > .claude/protocol/approved"
fi

MISSING=""
# "|| [ -n "$LINE" ]": still process a final token line that lacks a
# trailing newline (read returns nonzero there but does fill LINE).
while IFS= read -r LINE || [ -n "$LINE" ]; do
  LINE="${LINE%%#*}"
  LINE="$(echo "$LINE" | xargs 2>/dev/null || true)"
  [ -n "$LINE" ] || continue
  PLAN="$ROOT/$LINE"
  if [ ! -f "$PLAN" ]; then
    MISSING="$LINE"
    continue
  fi
  # Authorization comes ONLY from bullet lines inside the plan's
  # "## File inventory" section — a path mentioned in prose (e.g. a
  # "Not touched:" note) must not grant access.
  if awk '/^## File inventory/{f=1;next} /^## /{f=0} f && /^[[:space:]]*- /' "$PLAN" \
      | grep -qF "$REL"; then
    exit 0
  fi
done < "$TOKEN"

if [ -n "$MISSING" ]; then
  deny "Blocked (working-protocol G3): the approval token names plan '$MISSING' which does not exist. Ask the user to point the token at the approved plan document."
fi

deny "Blocked (working-protocol G3): '$REL' is not a bullet entry in the '## File inventory' section of the approved plan(s) named by the token. This is a plan deviation — stop, present the deviation (why this file is needed), and wait; after approval the plan's file inventory must be amended."
