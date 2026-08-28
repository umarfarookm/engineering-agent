#!/usr/bin/env bash
# Builds today's status, shows it, and posts it to Slack only if you say so.
#
# The same two endpoints the n8n workflow calls, with the approval gate in your terminal
# instead of a scheduled DM. Nothing runs in the background: use this when you want the
# status, rather than at an hour when your laptop may be asleep.
#
#   ./scripts/standup.sh              # preview, ask, send on y
#   ./scripts/standup.sh --dry-run    # preview only; never sends
#   ./scripts/standup.sh --yes        # send without asking (for a real cron)
set -euo pipefail

# .env fills gaps; it does not override what the caller already exported. `set -a; source`
# would do the opposite, silently ignoring a value passed for a single run.
if [[ -f .env ]]; then
  while IFS= read -r line; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    key=${line%%=*}
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    [[ -n "${!key:-}" ]] && continue
    value=${line#*=}
    value=${value%\"}; value=${value#\"}
    export "$key=$value"
  done < .env
fi

BASE_URL="${AGENT_BASE_URL:-http://127.0.0.1:${SERVER_PORT:-8080}}"
DRY_RUN=false
ASSUME_YES=false

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --yes|-y)  ASSUME_YES=true ;;
    --help|-h) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# The token is optional: bound to loopback the service runs without one, and sending an
# empty bearer would be rejected where it is required.
AUTH=()
[[ -n "${API_AUTH_TOKEN:-}" ]] && AUTH=(-H "Authorization: Bearer ${API_AUTH_TOKEN}")

fail() { echo "$*" >&2; exit 1; }

if ! curl -sS -o /dev/null --max-time 5 "$BASE_URL/api/health" 2>/dev/null; then
  fail "The agent is not running at $BASE_URL.
Start it with:  set -a && source .env && set +a && ./gradlew bootRun"
fi

# A local model takes minutes per ticket, so this call is deliberately patient.
echo "==> Building today's status (this can take a while with AI_ENABLED=true)..."
PREVIEW=$(mktemp)
trap 'rm -f "$PREVIEW"' EXIT

STATUS=$(curl -sS -o "$PREVIEW" -w '%{http_code}' -X POST "${AUTH[@]}" \
  --max-time "${STANDUP_TIMEOUT:-5400}" "$BASE_URL/api/slack/preview")

if [[ "$STATUS" == "401" ]]; then
  fail "Unauthorized. API_AUTH_TOKEN in .env does not match the running service.
The service reads it at startup, so restart it after changing .env."
elif [[ "$STATUS" != "200" ]]; then
  fail "The agent returned HTTP $STATUS:
$(cat "$PREVIEW")"
fi

python3 - "$PREVIEW" <<'PYEOF'
import json, sys
d = json.load(open(sys.argv[1]))
print()
print(d["message"])
print("-" * 68)
print(f"Destination : {d['destination']}")
print(f"Tickets     : {d['ticketCount']}")
for note in d.get("notes", []):
    print(f"  note      : {note}")
PYEOF

TICKETS=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ticketCount"])' "$PREVIEW")
if [[ "$TICKETS" == "0" ]]; then
  echo
  echo "No active tickets. Nothing to send."
  exit 0
fi

if $DRY_RUN; then
  echo
  echo "--dry-run: nothing was sent."
  exit 0
fi

if ! $ASSUME_YES; then
  echo
  read -r -p "Send this to Slack? [y/N] " REPLY
  [[ "$REPLY" =~ ^[Yy]$ ]] || { echo "Not sent."; exit 0; }
fi

# Sends the text that was just displayed, never a freshly generated one: the model is
# non-deterministic and the tickets keep moving, so a rebuild is not what you approved.
RESULT=$(mktemp)
trap 'rm -f "$PREVIEW" "$RESULT"' EXIT

STATUS=$(python3 -c '
import json,sys
d = json.load(open(sys.argv[1]))
json.dump({"message": d["message"]}, open(sys.argv[2], "w"))
' "$PREVIEW" "$RESULT" && curl -sS -o "$RESULT.out" -w '%{http_code}' -X POST "${AUTH[@]}" \
  -H 'Content-Type: application/json' --data @"$RESULT" --max-time 60 "$BASE_URL/api/slack/send")

if [[ "$STATUS" == "200" ]]; then
  echo "Sent. $(cat "$RESULT.out")"
else
  echo "Send failed with HTTP $STATUS:" >&2
  cat "$RESULT.out" >&2
  exit 1
fi
