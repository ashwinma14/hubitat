#!/bin/bash
#
# Deploy a Hubitat app's Groovy source to the hub's Apps Code editor over the LAN.
# (The MCP route in the README is the usual one; this is the fallback.)
#
# Usage:
#   ./deploy.sh                            # GreatRoomLighting.groovy
#   ./deploy.sh GoveeHolidayScenes.groovy  # any file listed in deploy.conf
#   ./deploy.sh MyApp.groovy 123           # a file that is not in deploy.conf yet
#
# Notes:
# - The id is the Apps Code editor id (http://HUB/app/editor/<ID>),
#   NOT the installed app instance id shown in logs (e.g. app:584).
# - Each file has its own id in deploy.conf (APP_IDS). A file with no id is refused, and so is
#   an id whose code on the hub is a different app: until 2026-11-09 every file without an
#   explicit id went to the Great Room's id and would have replaced that app.
#   FORCE=1 skips the name check (for a first upload over a placeholder).
# - After deploying changes that add/remove subscriptions or inputs, open the
#   app in Apps and click Done to re-initialize.

set -euo pipefail
cd "$(dirname "$0")"

HUB="192.168.86.25"
APP_IDS=""
FILE="${1:-GreatRoomLighting.groovy}"

# Load saved config (HUB / APP_IDS overrides)
[ -f deploy.conf ] && source deploy.conf

[ -f "$FILE" ] || { echo "ERROR: $FILE not found" >&2; exit 1; }

# The Apps Code id for a file name, from APP_IDS ("File.groovy=529 Other.groovy=590")
lookup_id() {
  local pair
  for pair in $APP_IDS; do
    if [ "${pair%%=*}" = "$1" ]; then echo "${pair#*=}"; return; fi
  done
}

# The app's name: the first name: "..." after definition( in Groovy source on stdin
# (plain, or JSON-escaped the way the hub returns it)
app_name() {
  awk '{ gsub(/\\"/, "\""); buf = buf $0 "\n" }
       END {
         i = index(buf, "definition(")
         if (!i) exit
         rest = substr(buf, i)
         if (match(rest, /name: *"[^"]*"/)) {
           s = substr(rest, RSTART, RLENGTH)
           sub(/^name: *"/, "", s); sub(/"$/, "", s)
           print s
         }
       }'
}

ID="${2:-$(lookup_id "$(basename "$FILE")")}"
if [ -z "$ID" ]; then
  echo "ERROR: no Apps Code id for $FILE." >&2
  echo "Add \"$(basename "$FILE")=<id>\" to APP_IDS in deploy.conf, or pass the id: ./deploy.sh $FILE <id>" >&2
  exit 1
fi
case "$ID" in *[!0-9]*) echo "ERROR: app code id '$ID' is not a number" >&2; exit 1 ;; esac

# Current code on the hub: its version is required by the update endpoint (prevents conflicts),
# and its app name must be this file's
CURRENT=$(curl -s --max-time 10 "http://$HUB/app/ajax/code?id=$ID")
VERSION=$(printf '%s' "$CURRENT" | sed -n 's/.*"version":\([0-9]*\).*/\1/p')
[ -n "$VERSION" ] || { echo "ERROR: could not read current version for app code id $ID" >&2; exit 1; }
echo "Current hub version: $VERSION"

LOCAL_NAME=$(app_name < "$FILE")
HUB_NAME=$(printf '%s' "$CURRENT" | app_name)
if [ "${FORCE:-0}" != "1" ] && [ "$LOCAL_NAME" != "$HUB_NAME" ]; then
  echo "ERROR: app code id $ID on the hub is \"${HUB_NAME:-unknown}\", but $FILE is \"${LOCAL_NAME:-unknown}\"." >&2
  echo "Refusing to overwrite it. Check the id (deploy.conf), or run with FORCE=1 if this is intended." >&2
  exit 1
fi

# Push the new source
RESULT=$(curl -s --max-time 20 -X POST "http://$HUB/app/ajax/update" \
  --data-urlencode "id=$ID" \
  --data-urlencode "version=$VERSION" \
  --data-urlencode "source@$FILE")

if echo "$RESULT" | grep -q '"status":"success"'; then
  echo "Deployed $FILE (\"$LOCAL_NAME\") to app code id $ID (was version $VERSION)."
  echo "If subscriptions/inputs changed, open the app and click Done."
else
  echo "DEPLOY FAILED:" >&2
  echo "$RESULT" >&2
  exit 1
fi
