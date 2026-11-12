# Hubitat Apps

Groovy apps for the Jungalow Hubitat hub (192.168.86.25), versioned in git and
deployed through the hub's MCP Rule Server (`importUrl`), with `deploy.sh` as the LAN fallback.

## Apps

| File | App | Hub instance |
|------|-----|--------------|
| GreatRoomLighting.groovy | Great Room Lighting Controller (v1.60) | app:584 (Apps Code id 529) |
| GoveeHolidayScenes.groovy | Govee Holiday Scenes (v1.16) — scene-by-date for the Govee string lights, deck lights and pathway bulbs | app:590 (Apps Code id 590) |
| GarageDoorBlink.groovy | Garage Door Blink (v2.1) — blinks the kitchen pendant through the Hue bridge's on/off signal when the garage door opens | app:597 (Apps Code id 595) |
| drivers/ESPHome-AthomHumanPresenceSensor.groovy | The Athom presence sensor's driver (third-party, MIT) with a local illuminance deadband and 10-minute heartbeat | Drivers Code id 963 (device 364) |
| tools/LuxCsvRepair.groovy | One-off utility: rebuilt `lux_clean.csv` on 2026-09-30 (used once, then removed from the hub) | none |

## Lux Logger (Rule Machine rule 543) — disabled 2026-11-11

Appends `date,time,device,value` rows to File Manager `lux_clean.csv` on every
illuminance change from devices 49 (outdoor), 289, 41, 364. Rule settings that matter:
`timeFormat=HH:mm:ss`, `dateFormat=yyyy-MM-dd`, and the append content ends with a
real newline character (RM renders `%nl%` as the text "null" and does not convert `\n`).
Rows before 2026-09-30 08:18 were rewritten from 12-hour times by `tools/LuxCsvRepair.groovy`;
the untouched original is `lux_clean_raw_backup_20260930.csv` on the hub.

## Workflow

1. Edit the .groovy file (usually via Claude from phone/desktop).
2. Commit and push, then deploy via MCP (below). `./deploy.sh <file>` is the LAN fallback; it takes each
   file's Apps Code id from `deploy.conf` and refuses an id whose code on the hub is a different app.
3. If the change touched subscriptions, inputs or anything `initialize()` does: press **Done** on the app
   (in the UI, or over MCP as below). Pure logic changes take effect immediately.

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
5. If the change touched subscriptions, inputs or `initialize()`: `hub_manage_native_rules_and_apps →
   hub_set_native_app` with the instance id and one setting written back at its current value
   (e.g. `{"lrMmwaveEmptyMinutes": 20}` on 584) presses **Done**. Empty settings are rejected. A Done press drops
   every manual hold and re-applies the scene, so pick a moment when nobody has set a light by hand. The re-apply
   itself changes no level: the factor, the morning ramp and (since v1.58) a dimmed kitchen zone carry over.

## Great Room: manual changes (v1.41-1.42)

A switch press (or a Hue-app / dashboard change that is not the app's own command) takes
that one light out of the automation; the rest of the room carries on. It comes back at the
next scene change (predawn end, wind-down, mode change, TV time, everyone leaving, room off)
or after 6 h. A press put back where the automation had it counts for nothing; three presses
in 30 s is a child and the scene is restored after 20 s; a brighter press during the morning
ramp is a floor the ramp continues from. The red switch LED that used to mark a manual hold
went away with the ZEN71: the cans dimmer is a ZEN77 on the built-in driver, which has no LED
commands, so the three LED settings currently do nothing.

The detection compares a light's report with the command the app last sent it, which lives in `state`, and
Hubitat only saves an execution's state when it ends. The app is therefore `singleThreaded: true` (v1.56): the
reports queue behind the execution that sent the commands instead of running against the state from before it.
Without that, on 2026-11-09 08:04 the seven "off" reports of an empty-house turn-off were read as hand presses,
five lights stayed held off through the next scene, and the living-room color-temperature command (which turns a
Hue light on) was the only one that got through. Keep executions short: a slow synchronous call would now delay
every handler behind it (the Sheets webhook is async; the Athom `initialize()` is the one call that can block).

## Great Room: how the two indoor sensors vote (v1.44)

The bookcase ZSE40 (289) reports a capped percentage that the room's own lights push up by
~40 points, so it only counts while the lights are off (turn on below 50) and can never say
"bright". The kitchen Hue sensor (41) reads real lux: turn on below 40, and a lit room may only
turn off once it reads the daylight level (100 since v1.50; the lights add ~30 lux at that
sensor). The bookcase can only turn the room on while the kitchen reads under its own ceiling
(60, lights off); keep that at least 30 under the daylight level or the 2026-10-23 loop returns.
Daytime dark-room debounce is 10 min, 3 min only in the last 90 min before sunset.

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

Since v1.49 the stairs sensor (318) and the front door (Ring contact 55, which only helps once the
Ring integration delivers open events; it reported none in the week to 2026-10-29) are "exits":
motion on the stairs or the door opening after the living room last moved ends the hold outright,
with no grace. The mmWave anchor is dated when the mmWave went active, not when the 45 s anchor fired.

Since v1.46 the mmWave also votes against the living-room dead-end hold: alive, reading empty
for `lrMmwaveEmptyMinutes` (20) with no living-room motion in that time, and the hold drops so
the normal timeout ends the room (a 2026-10-25 walk-out through an uncovered door kept the lights
on for the full 90-minute backstop). A blip restarts the 20 minutes.

## Great Room: party mode (v1.52)

`partyMode` with `partyDate` (MM-DD) and `partyFrom`/`partyTo` (HH:mm): a light somebody set by hand stays that way until the room
turns off for an empty house; scene changes, mode changes, wind-down, TV time, app restarts, the
six-hour limit and the three-presses rule leave it alone; at `partyTo` the hand-set lights go back to the automation. First used 2026-11-08 13:00-18:00;
switched off again on 2026-11-09 (`partyDate` is MM-DD, so a party mode left on would return on the same day next year).

## Govee Holiday Scenes (app 590)

Lights: string lights 433 (H7021, 96 ft, 30 bulbs), deck lights 434 (H7067, 15), Front String
Lights 441 (H702C, 144 ft, 45 bulbs, added 2026-10-26), all powered by the Twinkle/Front Lights
plugs; the per-light bulb count must match the Govee API's segment range (the alternate pattern
paints segments 0..count-1), and pathway bulbs 436 and 437 (Govee H600A on a non-smart sunset timer: no plug event, so they
are marked "self-powered": nothing is sent between sunrise and the hub's sunset (true of every automatic
send only since v1.15; until then each afternoon power-on sent to the unpowered bulbs four times and logged four
warnings; the Apply button still sends to everything), a burst goes out at
sunset +1/+3/+6 min, and they are re-sent every 5 min while the plugs are on until the
driver reports `cloudAPI=Success`). A two-color spec such as `alt:#8B00FF/#FF5500` alternates bulbs on the
deck lights and paints a single bulb one color (436 = first color, 437 = second).
Game days override everything with the Seahawks colors the same way.
Since v1.11 `earlyOnDates` (MM-DD list) + `earlyOnTime` switch the plugs on at that time on those
days instead of waiting for dusk. First used 2026-11-07/08 at 15:00; the dates were blanked on 2026-11-09
(they are MM-DD and would fire again a year later).

The early power-on leans on the hub's Room Lighting instance "Outdoor Lights" (app 418: plugs 394, 395, 396, 397
and a dual plug, 315/316, whose outlets have not reported since December 2024; on below 400 lux, off above 500). That app acts on
its own state, not the plugs': while it believes the lights are off it logs "Already Turned Off" for every
reading above 500 and leaves switched-on plugs alone, it takes them over at its next activation (below 400), and
only then does its morning turn-off apply to them. On 2026-11-07 it activated at 15:17 (17 minutes after the
early power-on) and was then **paused** some time before Sunday morning's first reading above 500 (in the Hubitat
UI: the MCP connection keeps a rollback baseline for every app it edits and had none for 418), so nothing turned the
plugs off until 2026-11-09 14:42, almost 48 hours. A paused instance shows as "Outdoor Lights (Paused)" in the apps list
and in the `triggered` list of any plug or lux event; neither app here can see or undo it. When outdoor lights
are on in daylight, look there first, resume it, then press its Turn Off (or Activate) so its state matches the plugs.
Since v1.9 a window's scene text may hold per-light rules separated by `;`: `Light name = alternatives`
(label or device id; `*` or a bare rule is the fallback; lights with no rule keep the default scene).
`off` switches a light off (it is switched back on when its next look arrives), `default` means the
default scene, and `ct:2700` is a plain color temperature (verified through the driver's colorTemperature;
a scene cannot be verified when it repeats the last one, and a pathway bulb does not restore a scene after
its timer cuts power, so the bulbs and the deck lights use `ct:2700`). On a light with per-segment control
the `ct:` look first paints every segment with the temperature's RGB: segment colors survive a whole-light
color-temperature command on the RGBIC deck lights (they kept the game colors through a week of accepted
2700 K commands, 2026-11-02 to 11-07). Default since 2026-11-01:
`GH-ILZ04Q | ct:2700` (the bulb strings keep their DIY, deck and pathway bulbs warm white). Diwali 2026 (custom window 1, 11-01 to 11-08):
`LED Bulb String Lights = Pastel; Front String Lights = Pastel; Smart LED Bulb = alt:#FFB3C6/#C8A2FF; Smart LED Bulb 2 = alt:#FFB3C6/#C8A2FF`
(deck lights keep the default). Since v1.16 the window is `diwali-7` to `diwali`: the week before Diwali through Diwali day, from a table of
Lakshmi Puja dates for 2026-2036 (Drik Panchang) in `diwaliDates()`. Extend the table before 2037.

## Motion Logger (Rule Machine rule 544) — disabled 2026-11-11

Same shape as the Lux Logger: appends `date,time,device,value` (motion/roomState/mmwave
values) to `motion.csv`. Fixed the same way on 2026-09-30; raw original kept as
`motion_raw_backup_20260930.csv` on the hub.

## Archive

`archive/` holds code that used to run on the hub and was retired (kept for reference only):
`AmbientTierClassifier.groovy` (Apps Code 526, superseded by the adaptive logic in
Great Room Lighting v1.31; the code is still installed on the hub, with no instance) and
`GreatRoomLighting_hub_v22.groovy` (the Great Room app as it ran before this repo existed: hub
version 22, app v1.26).

## Gotchas

- The ids in deploy.conf (`APP_IDS`) are **Apps Code editor ids**
  (http://HUB/app/editor/ID), not the installed instance ids (app:584).
- If the hub has login security enabled, deploy.sh needs a session cookie
  step added (currently assumes open LAN access).
- The Athom presence sensor (device 364, 192.168.86.227) reports illuminance every few seconds with a few lux of
  jitter. Since 2026-11-11 its driver (drivers/, Drivers Code 963) turns a reading into an event only on a move of
  5 lux or 10%, or after 10 minutes. Keep the heartbeat: the Great Room app reads 30 minutes without an event as a
  dead sensor.
- Hub modes: Mode Manager sets Day when outdoor lux rises above 300 and Night when it falls below 200, nothing else
  (the TV Time mode trigger and rule 565 went on 2026-11-11). Evening, Away, Early Morning and TV Time exist but
  nothing sets them; the Great Room app treats any mode but Night as day. TV time in the app is the switch (419).
- Hubitat saves an app's `state` when an execution ends. An app that compares events with what it
  just wrote to `state` needs `singleThreaded: true` (all three apps here have it).

## Audit 2026-11-09

Fixed that day (Great Room v1.57 and v1.58, Govee v1.15, Garage Door Blink v2.1, deploy.sh): see the version notes at the top
of each file. Settings the code stopped reading long ago were removed from the installed apps; their last values, in
case one is ever wanted again: Great Room `cansLuxThreshold` 150, `chromecast` device 423 (Foyer TV),
`chromecastIdleDelay` 300, `chromecastPlayDelay` 120, `partyEndDate` 11-08; Govee `gameBulbCount` 15.

Decided on 2026-11-11 and done the same day:

- Great Room v1.60: a kitchen, dining or hallway light switched off by hand comes back when the kitchen side is next
  entered after its dim time (5 min) of quiet; with no scene up, a lamp lit by hand is left alone; Sheets logging,
  the cloud-cover rule (last values: weather device 424, 70 %, 400 lux) and the Evening branches removed;
  `GreatRoomLightingLogger.gs` deleted from the repo (the Apps Script and Sheet in Google are untouched). The dead
  Ring front door sensor (55) is out of the exit doors.
- The hallway dimmer (128) runs jtp10181's "Zooz ZEN Dimmer Advanced" and fades. Its LED shows a manual hold by
  staying lit (a ZEN27 has one colour); the cans (399) still use the built-in driver, so their LED shows nothing.
- Govee v1.16: Diwali window follows the real date.
- Mode Manager no longer sets TV Time; rule 565 and the empty rule "Light" (447) deleted (MCP backups kept).
- Outdoor Lights (418) automates 394, 395, 396, 397 (the dead dual plug 315/316 removed).
- Lux Logger (543) and Motion Logger (544) disabled; `lux_clean.csv` and `motion.csv` deleted from the hub
  (the 2026-09-30 raw backups and `lux.csv` remain).
- Rule "Google Sheets" (336) now sets LuxStore and TempStore from the outdoor sensor before its GET.
- Deleted: Rachio Community (446) with its devices 345-347, Harmony Hub 323 with children 327-329.
- Disabled, kept: Ring Front Door Sensor 55, Device Watchdog child "Athom" (586, duplicated the app's check).
- Frame dashboard (Mac mini, ~/frame-dashboard): polls Maker API every 30 s instead of 10 s and the mode every
  5 min; device map drops 127, 42, 55 and the dryer 363, adds the LED Bulb String Lights 433 to Outdoor.

Hub leftovers found in the same pass (none of them this repo's code):

- Motion Lighting "Downstairs Motion (accent light)" (27) still switches Kitchen Cabinet Lights (42) on and off
  several times a day; the light has not answered since November 2025.
- Rule Machine "Google Sheets" (336) sends a GET to a Google Form on every outdoor temperature or lux change with
  two variables that are never set (both 0); "Light" (447) has no triggers and no actions.
- Rachio Community (446) throws a `heartbeat()` MissingMethodException every 15 minutes; Living Room Harmony Hub
  (323) retries an unreachable host every 10 minutes.
- Paused: Room Lighting "Garage Lights" (126), Rule Machine "Office Lights Failsafe (2h no motion)" (596).
- "Kitchen Cans (old)" (439, the retired ZEN71), the group "Kitchen (All)" (127), about 20 Apps Code entries with
  no installed instance, and File Manager files from 2022-2024 (HubiGraph, WeatherTile, webcore, `lux.csv`), the two
  raw CSV backups of 2026-09-30 (3.7 MB) and `mcp_nl_test_backup_20260930-081839.txt`.
- Hub firmware 2.5.2.139 is available (running 2.5.2.128); Hubitat Package Manager shows updates.

