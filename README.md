# Hubitat Apps

Groovy apps for the Jungalow Hubitat hub (192.168.86.25), versioned in git and
deployed through the hub's MCP Rule Server (`importUrl`), with `deploy.sh` as the LAN fallback.

## Apps

| File | App | Hub instance |
|------|-----|--------------|
| GreatRoomLighting.groovy | Great Room Lighting Controller (v1.35) | app:584 (Apps Code id 529) |
| GoveeHolidayScenes.groovy | Govee Holiday Scenes (v1.7) — scene-by-date for the Govee string lights | (installed from Apps Code) |
| GreatRoomLightingLogger.gs | Google Apps Script webhook for Sheets logging | (Google) |
| tools/LuxCsvRepair.groovy | One-off utility: rebuilt `lux_clean.csv` on 2026-09-30 (used once, then removed from the hub) | none |

## Lux Logger (Rule Machine rule 543)

Appends `date,time,device,value` rows to File Manager `lux_clean.csv` on every
illuminance change from devices 49 (outdoor), 289, 41, 364. Rule settings that matter:
`timeFormat=HH:mm:ss`, `dateFormat=yyyy-MM-dd`, and the append content ends with a
real newline character (RM renders `%nl%` as the text "null" and does not convert `\n`).
Rows before 2026-09-30 08:18 were rewritten from 12-hour times by `tools/LuxCsvRepair.groovy`;
the untouched original is `lux_clean_raw_backup_20260930.csv` on the hub.

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

## Motion Logger (Rule Machine rule 544)

Same shape as the Lux Logger: appends `date,time,device,value` (motion/roomState/mmwave
values) to `motion.csv`. Fixed the same way on 2026-09-30; raw original kept as
`motion_raw_backup_20260930.csv` on the hub.

## Archive

`archive/` holds code that used to run on the hub and was retired (kept for reference only):
`AmbientTierClassifier.groovy` (Apps Code 526, superseded by the adaptive logic in
Great Room Lighting v1.31).

## Gotchas

- `APP_CODE_ID` in deploy.conf is the **Apps Code editor id**
  (http://HUB/app/editor/ID), not the installed instance id (app:584).
- If the hub has login security enabled, deploy.sh needs a session cookie
  step added (currently assumes open LAN access).
- The Athom presence sensor (device 364, 192.168.86.227) periodically floods
  illuminance events and gets throttled by the hub
  (LimitExceededException) — see project notes.
