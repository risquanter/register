#!/usr/bin/env bash
# PreToolUse gate enforcing the register working protocol (G1/G3/G6/G7).
#
# Two checks, dispatched on tool_name from the hook's stdin JSON:
#  - Bash: deny any command that references the approval-token path — the
#    token is user-owned; the agent must never create, refresh, or delete it.
#  - Edit/Write/NotebookEdit: deny edits to gated source paths (modules/**,
#    build.sbt) unless the user-owned approval token exists and is fresh.
#
# The user grants approval from their own terminal:
#   mkdir -p .claude/protocol && touch .claude/protocol/approved
# One approval stays valid for TTL_SECONDS, then edits block again.
set -euo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
TOKEN="$ROOT/.claude/protocol/approved"
TTL_SECONDS=1800

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
      deny "Blocked: the approval token under .claude/protocol/ is user-owned. Only the user may create, refresh, or remove it (working-protocol G7). Ask the user to run the approval command themselves."
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

if [ -f "$TOKEN" ]; then
  NOW=$(date +%s)
  MTIME=$(stat -c %Y "$TOKEN" 2>/dev/null || stat -f %m "$TOKEN")
  AGE=$((NOW - MTIME))
  if [ "$AGE" -le "$TTL_SECONDS" ]; then
    exit 0
  fi
  deny "Blocked (working-protocol G1/G3): approval token expired (${AGE}s old, TTL ${TTL_SECONDS}s). Present the pending signature echo / quality-gated plan and wait. The user re-approves by running: touch .claude/protocol/approved"
fi

deny "Blocked (working-protocol G1/G3): no approval token. Present the signature echo / quality-gated plan and wait for an accepted signal. The user approves by running: mkdir -p .claude/protocol && touch .claude/protocol/approved"
