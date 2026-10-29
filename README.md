# Hubitat Apps

Groovy apps for the Jungalow Hubitat hub (192.168.86.25), versioned in git and
deployed through the hub's MCP Rule Server (`importUrl`), with `deploy.sh` as the LAN fallback.

## Apps

| File | App | Hub instance |
|------|-----|--------------|
| GreatRoomLighting.groovy | Great Room Lighting Controller (v1.48) | app:584 (Apps Code id 529) |
| GoveeHolidayScenes.groovy | Govee Holiday Scenes (v1.8) — scene-by-date for the Govee string lights, deck lights and pathway bulbs | app:590 (Apps Code id 590) |
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

## Great Room: manual changes (v1.41-1.42)

A switch press (or a Hue-app / dashboard change that is not the app's own command) takes
that one light out of the automation; the rest of the room carries on. It comes back at the
next scene change (predawn end, wind-down, mode change, TV time, everyone leaving, room off)
or after 6 h. A press put back where the automation had it counts for nothing; three presses
in 30 s is a child and the scene is restored after 20 s; a brighter press during the morning
ramp is a floor the ramp continues from. The kitchen cans switch (Zooz ZEN71, Advanced
driver) shows red while under manual control.

## Great Room: how the two indoor sensors vote (v1.44)

The bookcase ZSE40 (289) reports a capped percentage that the room's own lights push up by
~40 points, so it only counts while the lights are off (turn on below 50) and can never say
"bright". The kitchen Hue sensor (41) reads real lux: turn on below 40, and a lit room may only
turn off once it reads the daylight level (150). The bookcase cannot turn the room on while the
kitchen is at daylight. Daytime dark-room debounce is 10 min, 3 min only in the last 90 min
before sunset.

## Great Room: the Athom and the motion zone (v1.45)

The Athom mmWave (364, ESPHome) is a member of the hub's Motion Zone app
(DownstairsALLMotionDetection → device 404), which the app uses as its presence signal. Its
link to the hub can die with the driver still saying "online" (2026-10-23, 13:10 → 04:45:
motion stuck active, zone never inactive, room lit all night). The app now treats the Athom as
stale after 30 silent minutes or whenever the driver says offline, re-opens the connection
itself (`initialize`, which was all it took), alerts only if that fails, starts the presence
timeout without the zone when the silent Athom is the only sensor holding it, and takes a live
sensor firing as the zone edge so the room still comes back on.

Since v1.48 a zone watchdog covers any zone failure, not just a dead Athom: when every member
sensor has been quiet for `zoneWatchdogMinutes` (7; the zone's own timeout is 5) and the zone
device still says active, the presence timeout runs without it (2026-10-29: all sensors quiet
from 05:50, the Motion Zone app never timed out, room lit until 06:47).

Since v1.46 the mmWave also votes against the living-room dead-end hold: alive, reading empty
for `lrMmwaveEmptyMinutes` (20) with no living-room motion in that time, and the hold drops so
the normal timeout ends the room (a 2026-10-25 walk-out through an uncovered door kept the lights
on for the full 90-minute backstop). A blip restarts the 20 minutes.

## Govee Holiday Scenes (app 590)

Lights: string lights 433 (H7021, 96 ft, 30 bulbs), deck lights 434 (H7067, 15), Front String
Lights 441 (H702C, 144 ft, 45 bulbs, added 2026-10-26), all powered by the Twinkle/Front Lights
plugs; the per-light bulb count must match the Govee API's segment range (the alternate pattern
paints segments 0..count-1), and pathway bulbs 436 and 437 (Govee H600A on a non-smart sunset timer: no plug event, so they
are marked "self-powered": nothing is sent before the hub's sunset, a burst goes out at
sunset +1/+3/+6 min, and they are re-sent every 5 min while the plugs are on until the
driver reports `cloudAPI=Success`). A two-color spec such as `alt:#8B00FF/#FF5500` alternates bulbs on the
deck lights and paints a single bulb one color (436 = first color, 437 = second).
Game days override everything with the Seahawks colors the same way.

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
