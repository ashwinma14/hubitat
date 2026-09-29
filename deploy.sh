#!/bin/bash
#
# Deploy a Hubitat app's Groovy source to the hub's Apps Code editor.
#
# Usage:
#   ./deploy.sh                      # deploys GreatRoomLighting.groovy using APP_CODE_ID from deploy.conf
#   ./deploy.sh MyApp.groovy 123     # deploy a specific file to a specific app code id
#
# Notes:
# - APP_CODE_ID is the Apps Code editor id (http://HUB/app/editor/<ID>),
#   NOT the installed app instance id shown in logs (e.g. app:584).
# - After deploying changes that add/remove subscriptions or inputs, open the
#   app in Apps and click Done to re-initialize.

set -euo pipefail
cd "$(dirname "$0")"

HUB="192.168.86.25"
FILE="${1:-GreatRoomLighting.groovy}"

# Load saved config (HUB / APP_CODE_ID overrides)
[ -f deploy.conf ] && source deploy.conf

ID="${2:-${APP_CODE_ID:-}}"

if [ -z "$ID" ]; then
  echo "No app code id. Trying to discover from hub..."
  ID=$(curl -s --max-time 10 "http://$HUB/app/list" \
    | grep -B5 -i "great room lighting controller" \
    | grep -o 'editor/[0-9]*' | head -1 | cut -d/ -f2 || true)
  if [ -z "$ID" ]; then
    echo "ERROR: could not discover app code id. Set APP_CODE_ID in deploy.conf" >&2
    exit 1
  fi
  echo "Discovered app code id: $ID"
fi

[ -f "$FILE" ] || { echo "ERROR: $FILE not found" >&2; exit 1; }

# Get current version (required by the update endpoint; prevents conflicts)
VERSION=$(curl -s --max-time 10 "http://$HUB/app/ajax/code?id=$ID" \
  | sed -n 's/.*"version":\([0-9]*\).*/\1/p')
[ -n "$VERSION" ] || { echo "ERROR: could not read current version for app code id $ID" >&2; exit 1; }
echo "Current hub version: $VERSION"

# Push the new source
RESULT=$(curl -s --max-time 20 -X POST "http://$HUB/app/ajax/update" \
  --data-urlencode "id=$ID" \
  --data-urlencode "version=$VERSION" \
  --data-urlencode "source@$FILE")

if echo "$RESULT" | grep -q '"status":"success"'; then
  echo "Deployed $FILE to app code id $ID (was version $VERSION)."
  echo "If subscriptions/inputs changed, open the app and click Done."
else
  echo "DEPLOY FAILED:" >&2
  echo "$RESULT" >&2
  exit 1
fi
