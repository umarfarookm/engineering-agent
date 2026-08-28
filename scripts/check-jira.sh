#!/usr/bin/env bash
# Verifies Jira credentials by calling Jira directly, with no application involved.
# Use this to tell a credential problem apart from an application problem.
#
#   ./scripts/check-jira.sh            # check auth + list active issues
#   ./scripts/check-jira.sh ENG-267    # also dump one issue's raw fields
set -euo pipefail

if [[ -f .env ]]; then set -a; source .env; set +a; fi

: "${JIRA_BASE_URL:?JIRA_BASE_URL is not set (check your .env)}"
: "${JIRA_EMAIL:?JIRA_EMAIL is not set (check your .env)}"
: "${JIRA_API_TOKEN:?JIRA_API_TOKEN is not set (check your .env)}"

AUTH="$JIRA_EMAIL:$JIRA_API_TOKEN"
STATUSES="${JIRA_IN_PROGRESS_STATUSES:-In Progress,In Review,Code Review}"

# Build the status list as "A", "B", "C" for the JQL IN clause.
JQL_STATUSES=$(printf '%s' "$STATUSES" | awk -F, '{for(i=1;i<=NF;i++){gsub(/^ +| +$/,"",$i); printf "%s\"%s\"", (i>1?", ":""), $i}}')

echo "==> Jira instance: $JIRA_BASE_URL"

# --fields: list every field id and name, for locating a custom field such as acceptance criteria.
if [[ "${1:-}" == "--fields" ]]; then
  curl -sS -u "$AUTH" -H 'Accept: application/json' "$JIRA_BASE_URL/rest/api/3/field" \
    -o /tmp/jira-fields.json
  python3 - <<'PYEOF'
import json
for f in json.load(open("/tmp/jira-fields.json")):
    print("{:<24} {}".format(f["id"], f.get("name", "")))
PYEOF
  exit 0
fi

echo
echo "==> 1. Who am I? (GET /rest/api/3/myself)"
code=$(curl -sS -u "$AUTH" -o /tmp/jira-myself.json -w '%{http_code}' \
  -H 'Accept: application/json' "$JIRA_BASE_URL/rest/api/3/myself")

if [[ "$code" != "200" ]]; then
  echo "    FAILED with HTTP $code"
  case "$code" in
    401) echo "    401 means the email or API token is wrong. The token must be an API token" 
         echo "    from id.atlassian.com, not your Atlassian password." ;;
    403) echo "    403 often means CAPTCHA is required — log into Jira in a browser once, then retry." ;;
    404) echo "    404 means JIRA_BASE_URL is wrong. It should look like https://your-site.atlassian.net" ;;
  esac
  exit 1
fi
python3 - <<'PYEOF'
import json
d = json.load(open("/tmp/jira-myself.json"))
print("    OK: {} <{}>".format(d.get("displayName"), d.get("emailAddress")))
PYEOF

echo
echo "==> 2. Active issues assigned to me"
echo "    statuses: $STATUSES"
JQL="assignee = currentUser() AND status IN ($JQL_STATUSES) ORDER BY updated DESC"

code=$(curl -sS -u "$AUTH" -o /tmp/jira-search.json -w '%{http_code}' -G \
  -H 'Accept: application/json' \
  --data-urlencode "jql=$JQL" \
  --data-urlencode 'fields=summary,status,updated' \
  --data-urlencode 'maxResults=50' \
  "$JIRA_BASE_URL/rest/api/3/search/jql")

if [[ "$code" != "200" ]]; then
  echo "    FAILED with HTTP $code"
  python3 - <<'PYEOF' 2>/dev/null || cat /tmp/jira-search.json
import json
d = json.load(open("/tmp/jira-search.json"))
print("   ", "; ".join(d.get("errorMessages", [])) or d)
PYEOF
  echo
  echo "    A 400 here usually means a status name in JIRA_IN_PROGRESS_STATUSES does not exist"
  echo "    on your board. Compare against the status names shown on the board itself."
  exit 1
fi

python3 - <<'PYEOF'
import json
d = json.load(open("/tmp/jira-search.json"))
issues = d.get("issues", [])
if not issues:
    print("    No issues matched. Either nothing is assigned to you in those statuses,")
    print("    or your board uses different status names.")
for i in issues:
    f = i.get("fields", {})
    print(f"    {i.get('key'):<12} [{f.get('status',{}).get('name','?'):<14}] {f.get('summary','')}")
print(f"\n    {len(issues)} issue(s).")
PYEOF

if [[ $# -ge 1 ]]; then
  KEY="$1"
  echo
  echo "==> 3. Raw fields for $KEY"
  curl -sS -u "$AUTH" -H 'Accept: application/json' \
    "$JIRA_BASE_URL/rest/api/3/issue/$KEY?fields=summary,status,description,issuelinks" \
    -o /tmp/jira-issue.json
  python3 - "$KEY" <<'PYEOF'
import json, sys
d = json.load(open("/tmp/jira-issue.json"))
if "errorMessages" in d:
    print("    ", "; ".join(d["errorMessages"])); sys.exit(1)
f = d.get("fields", {})
print(f"    summary: {f.get('summary')}")
print(f"    status : {f.get('status',{}).get('name')}")
desc = f.get("description")
print(f"    description present: {'yes' if desc else 'no'}")

# Report which acceptance-criteria path this project needs.
headings = []
def walk(n):
    if isinstance(n, dict):
        if n.get("type") == "heading":
            txt = "".join(c.get("text","") for c in n.get("content",[]) if isinstance(c, dict))
            headings.append(txt)
        for c in n.get("content", []) or []:
            walk(c)
if desc:
    walk(desc)
    print(f"    description headings: {headings or '(none)'}")
    if any("acceptance" in h.lower() for h in headings):
        print("    -> Acceptance criteria live in the description. No config needed.")
    else:
        print("    -> No 'Acceptance Criteria' heading found. If your project has a custom")
        print("       field for it, set JIRA_ACCEPTANCE_CRITERIA_FIELD. Find its id with:")
        print("       ./scripts/check-jira.sh --fields | grep -i acceptance")
PYEOF
fi

echo
echo "Done. If all three steps passed, the application will work with these credentials."
