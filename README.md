# Hubitat Apps

Groovy apps for the Jungalow Hubitat hub (192.168.86.25), versioned in git and
deployed with `deploy.sh`.

## Apps

| File | App | Hub instance |
|------|-----|--------------|
| GreatRoomLighting.groovy | Great Room Lighting Controller (v1.30) | app:577 |
| GreatRoomLightingLogger.gs | Google Apps Script webhook for Sheets logging | (Google) |

## Workflow

1. Edit the .groovy file (usually via Claude from phone/desktop).
2. `./deploy.sh` — pushes the source to the hub's Apps Code editor.
3. If the change touched subscriptions or inputs: open the app in
   Apps → Great Room Lighting and click **Done** to re-initialize.
   Pure logic changes take effect immediately.
4. Commit: `git add -A && git commit -m "..."`.

## Gotchas

- `APP_CODE_ID` in deploy.conf is the **Apps Code editor id**
  (http://HUB/app/editor/ID), not the installed instance id (app:577).
- If the hub has login security enabled, deploy.sh needs a session cookie
  step added (currently assumes open LAN access).
- The Athom presence sensor (device 364, 192.168.86.227) periodically floods
  illuminance events and gets throttled by the hub
  (LimitExceededException) — see project notes.
