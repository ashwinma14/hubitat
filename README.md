# Hubitat Apps

Groovy apps for the Jungalow Hubitat hub (192.168.86.25), versioned in git and
deployed through the hub's MCP Rule Server (`importUrl`), with `deploy.sh` as the LAN fallback.

## Apps

| File | App | Hub instance |
|------|-----|--------------|
| GreatRoomLighting.groovy | Great Room Lighting Controller (v1.30) | app:584 (Apps Code id 529) |
| GoveeHolidayScenes.groovy | Govee Holiday Scenes (v1.5) — scene-by-date for the Govee string lights | (installed from Apps Code) |
| GreatRoomLightingLogger.gs | Google Apps Script webhook for Sheets logging | (Google) |

## Workflow

1. Edit the .groovy file (usually via Claude from phone/desktop).
2. Commit and push, then deploy via MCP (below); `./deploy.sh` is the LAN fallback.
3. If the change touched subscriptions or inputs: open the app in
   Apps → Great Room Lighting and click **Done** to re-initialize.
   Pure logic changes take effect immediately.
4. Commit: `git add -A && git commit -m "..."`.

## Deploy via MCP (preferred)

The hub runs the MCP Rule Server (hub app 588). From a Claude session with the
Hubitat connector:

1. `git rev-parse HEAD` — take the pushed commit's SHA.
2. `hub_read_apps_code → hub_get_source(type=app, id=529)` — note `version`.
3. `hub_manage_code → hub_update_app` with `appId=529`,
   `importUrl=https://raw.githubusercontent.com/ashwinma14/hubitat/<SHA>/GreatRoomLighting.groovy`,
   `expectedVersion=<version>`, `confirm=true`, and `bestPracticeKey` from
   `hub_get_tool_guide(section='best_practice_reference')`.
   Pin the URL to the SHA, not `main`: raw.githubusercontent.com caches for ~5 minutes.
4. Verify: `hub_get_source` shows `version+1` and `totalLength` equal to the file's
   byte count (the source is ASCII, so chars == bytes), and the FNV-1a of the hub
   source equals the git file's (e.g. from the hub's `/app/ajax/code?id=529`).
5. If the change touched subscriptions or inputs, open the app and click **Done**.

## Gotchas

- `APP_CODE_ID` in deploy.conf is the **Apps Code editor id**
  (http://HUB/app/editor/ID), not the installed instance id (app:584).
- If the hub has login security enabled, deploy.sh needs a session cookie
  step added (currently assumes open LAN access).
- The Athom presence sensor (device 364, 192.168.86.227) periodically floods
  illuminance events and gets throttled by the hub
  (LimitExceededException) — see project notes.
