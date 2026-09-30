/**
 *  Govee Holiday Scenes
 *
 *  Picks which scene the Govee string lights show, by date window (Halloween, ...),
 *  with a game-day override and a default scene for every other night.
 *
 *  This app does NOT own on/off. The lights are powered through smart plugs that the
 *  "Outdoor Lights" Room Lighting automation switches on lux. When a plug turns on, the
 *  Govee controllers reboot and rejoin Wi-Fi, so the scene is applied after a boot delay
 *  and re-sent a couple of times (the Hubitat-side Govee state is stale while unpowered).
 *
 *  Scenes are configured by NAME and resolved per light from that light's own
 *  `lightEffects` catalog (built-in scene ids differ per Govee model; DIY ids are shared).
 *  The Govee v2 driver's setEffect() routes DIY ids to activateDIY() itself.
 *
 *  Author: Claude (for Ashwin)
 *  Date: 2026-09-29
 *  Version: 1.5 - game night lasts until the lights go off in the morning (all-night mode, default on)
 *  Version: 1.4 - built-in Seahawks 2026 schedule with kickoff windows; calendar switch now optional
 *  Version: 1.3 - per-light segment counts, scene alternatives (A | B), game-day "auto" mode
 *  Version: 1.2 - game-day "alternate two colors" per-bulb pattern for lights without a DIY scene
 *  Version: 1.1 - verify each send against the driver (effectNum), late +15 min re-send
 *  Version: 1.0 - Halloween window, game-day override, default scene, plug-triggered apply
 */

import groovy.json.JsonSlurper

definition(
    name: "Govee Holiday Scenes",
    namespace: "jungalow",
    author: "Claude",
    description: "Date-window scene selection for Govee string lights (Halloween, game days, default)",
    category: "Lighting",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def appVersion() { return "1.5" }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Govee Holiday Scenes v${appVersion()}", install: true, uninstall: true) {

        section("<b>Lights</b>") {
            input "goveeLights", "capability.lightEffects", title: "Govee lights (Govee v2 driver)", multiple: true, required: true, submitOnChange: true
            goveeLights?.each { dev ->
                input "segCount_${dev.id}".toString(), "number", title: "${dev.displayName}: bulbs/segments (used by the alternate-colors pattern)", defaultValue: 15, required: false, width: 6
            }
            input "powerSwitches", "capability.switch", title: "Plugs/switches that power these lights", multiple: true, required: true, submitOnChange: true
            input "bootDelay", "number", title: "Seconds after power-on before the first scene command", defaultValue: 45, required: true
            input "applyAttempts", "number", title: "How many times to send the scene after power-on (1-4; re-sends at +75s, +255s and +15 min for slow Wi-Fi rejoins)", defaultValue: 4, required: true
        }

        section("<b>Scenes</b>") {
            paragraph "Scene names must match the light's scene catalog (see Status below). Give alternatives separated by | and each light uses the first one it has, e.g. 'Static warm white | White Light'. Windows are MM-DD and may wrap the year end (e.g. 11-27 to 01-06)."
            input "defaultScene", "text", title: "Default scene outside any window", defaultValue: "Static warm white", required: true, submitOnChange: true
            input "halloweenEnabled", "bool", title: "Halloween window", defaultValue: true, submitOnChange: true
            if (halloweenEnabled) {
                input "halloweenStart", "text", title: "Halloween start (MM-DD)", defaultValue: "10-01", required: true, width: 4
                input "halloweenEnd", "text", title: "Halloween end (MM-DD)", defaultValue: "10-31", required: true, width: 4
                input "halloweenScene", "text", title: "Halloween scene", defaultValue: "Halloween", required: true, width: 4, submitOnChange: true
            }
            input "custom1Name", "text", title: "Custom window 1 name (blank = unused)", required: false, submitOnChange: true
            if (custom1Name) {
                input "custom1Start", "text", title: "${custom1Name} start (MM-DD)", required: true, width: 4
                input "custom1End", "text", title: "${custom1Name} end (MM-DD)", required: true, width: 4
                input "custom1Scene", "text", title: "${custom1Name} scene", required: true, width: 4, submitOnChange: true
            }
            input "custom2Name", "text", title: "Custom window 2 name (blank = unused)", required: false, submitOnChange: true
            if (custom2Name) {
                input "custom2Start", "text", title: "${custom2Name} start (MM-DD)", required: true, width: 4
                input "custom2End", "text", title: "${custom2Name} end (MM-DD)", required: true, width: 4
                input "custom2Scene", "text", title: "${custom2Name} scene", required: true, width: 4, submitOnChange: true
            }
        }

        section("<b>Game-day override</b>") {
            input "scheduleEnabled", "bool", title: "Use the built-in Seahawks 2026 schedule (${gameSchedule().size()} games)", defaultValue: true, submitOnChange: true
            if (scheduleEnabled) {
                input "gameAllNight", "bool", title: "Keep the game look until the lights go off for the night (otherwise it ends a fixed time after kickoff)", defaultValue: true, submitOnChange: true
                input "gameLeadMinutes", "number", title: "Start the game look this many minutes before kickoff", defaultValue: 30, required: true, width: 6
                input "gameHoldMinutes", "number", title: gameAllNight == false ? "End it this many minutes after kickoff" : "Earliest a power-off may end game night (minutes after kickoff)", defaultValue: 240, required: true, width: 6
                input "extraGames", "textarea", title: "Schedule changes, one per line: 'YYYY-MM-DD HH:mm label' adds or replaces a game (flex moves, playoffs); '-YYYY-MM-DD' removes one", required: false, submitOnChange: true
                paragraph "Next game: ${nextGameText()}"
            }
            input "gameSwitch", "capability.switch", title: "Also treat this switch being on as game time (optional, e.g. a calendar-driven Seahawks_Game switch)", required: false, submitOnChange: true
            if (gameSwitch || scheduleEnabled) {
                input "gamePattern", "enum", title: "Game-day look", required: true, defaultValue: "scene", submitOnChange: true,
                    options: ["scene": "A scene by name (built-in or DIY)", "alternate": "Alternate two colors bulb by bulb (no DIY needed; per-segment lights only)", "auto": "The scene where a light has it, the alternate-colors pattern elsewhere"]
                if (gamePattern in ["alternate", "auto"]) {
                    paragraph "Bulbs are painted even/odd with the two colors below (counts per light are set in the Lights section). The Govee driver cannot confirm segment commands and logs a spurious 'command failed' line for them even when they work, so check the lights."
                    input "gameColorA", "text", title: "Color A (hex, e.g. #69BE28)", defaultValue: "#69BE28", required: true, width: 6
                    input "gameColorB", "text", title: "Color B (hex, e.g. #0055FF)", defaultValue: "#0055FF", required: true, width: 6
                    input "gameFirstBulb", "number", title: "Index of the first bulb (0 for Govee's API; try 1 if the pattern looks shifted)", defaultValue: 0, required: true
                    input "gameScene", "text", title: gamePattern == "auto" ? "Game scene name (used on lights that have it)" : "Fallback scene name for lights without segment control", defaultValue: "Seahawks Surge", required: false, submitOnChange: true
                } else {
                    input "gameScene", "text", title: "Scene while the game switch is on", defaultValue: "Seahawks Surge", required: true, submitOnChange: true
                }
            }
        }

        section("<b>Status</b>") {
            paragraph statusHtml()
            input "btnApply", "button", title: "Apply scene now"
            input "btnReload", "button", title: "Reload scene catalogs"
        }

        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
            input "logEnableMinutes", "number", title: "Disable debug logging after (minutes, 0=never)", defaultValue: 30
        }

        section("<b>App Control</b>") {
            input "appPaused", "bool", title: "Pause automation", defaultValue: false, description: "When paused, the app sends no scene commands"
        }
    }
}


// ==================== LIFECYCLE ====================

def installed() {
    log.info "Govee Holiday Scenes v${appVersion()} installed"
    initialize()
}

def updated() {
    log.info "Govee Holiday Scenes v${appVersion()} updated"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (!goveeLights || !powerSwitches) {
        log.warn "Lights or power switches not configured; nothing to do"
        return
    }

    subscribe(powerSwitches, "switch.on", "powerOnHandler")
    subscribe(powerSwitches, "switch.off", "powerOffHandler")
    if (gameSwitch) {
        subscribe(gameSwitch, "switch", "gameHandler")
    }

    // Re-evaluate shortly after midnight so a window boundary takes effect while the lights are on
    schedule("0 5 0 * * ?", "dailyRollover")
    state.gameWindowActive = false
    scheduleTodaysGame()

    if (logEnable && (logEnableMinutes ?: 0) > 0) {
        runIn((logEnableMinutes as Integer) * 60, "logsOff")
    }

    windows().each { w ->
        if (!validDate(w.start) || !validDate(w.end)) {
            log.warn "Window '${w.name}' has an invalid date (${w.start} to ${w.end}); it will be ignored"
        }
    }

    log.info "Lights: ${goveeLights*.displayName.join(', ')} | Power: ${powerSwitches*.displayName.join(', ')} | " +
             "Game switch: ${gameSwitch?.displayName ?: 'none'} | Today's scene: '${targetSceneName()}' | paused=${appPaused}"
}

def logsOff() {
    log.warn "Debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}


// ==================== EVENT HANDLERS ====================

def powerOnHandler(evt) {
    logDebug "Power on: ${evt.displayName}"
    if (appPaused) { logDebug "Paused; ignoring power-on"; return }

    // Collapse several plugs switching together into one set of attempts
    unschedule("applyScenes")
    state.powerOnAt = now()

    Integer delay = Math.max(5, (bootDelay ?: 45) as Integer)
    Integer attempts = Math.min(4, Math.max(1, (applyAttempts ?: 4) as Integer))
    [0, 75, 255, 855].take(attempts).eachWithIndex { offset, i ->
        runIn(delay + offset, "applyScenes", [data: [reason: "power-on", attempt: i + 1], overwrite: false])
    }
    logDebug "Scheduled ${attempts} scene send(s) starting in ${delay}s"
}

def powerOffHandler(evt) {
    logDebug "Power off: ${evt.displayName}"
    if (!anyPowerOn()) {
        unschedule("applyScenes")
        logDebug "All power switches off; cancelled pending scene sends"
        if (state.gameWindowActive && gameAllNight != false && now() >= ((state.gameMinEnd ?: 0) as Long)) {
            endGameWindow(false)
        }
    }
}

def gameHandler(evt) {
    logDebug "Game switch ${evt.value}"
    if (appPaused) return
    if (!anyPowerOn()) { logDebug "Lights unpowered; scene will follow at next power-on"; return }
    runIn(2, "applyScenes", [data: [reason: "game ${evt.value}", attempt: 1], overwrite: false])
    runIn(62, "applyScenes", [data: [reason: "game ${evt.value}", attempt: 2], overwrite: false])
}

def dailyRollover() {
    scheduleTodaysGame()
    if (appPaused) return
    if (!anyPowerOn()) { logDebug "Rollover: lights unpowered"; return }
    def target = targetSceneName()
    if (target == state.lastApplied?.scene) { logDebug "Rollover: scene unchanged ('${target}')"; return }
    log.info "Date rollover: switching to '${target}'"
    runIn(2, "applyScenes", [data: [reason: "date rollover", attempt: 1], overwrite: false])
    runIn(62, "applyScenes", [data: [reason: "date rollover", attempt: 2], overwrite: false])
}

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnApply":
            applyScenes([reason: "manual", attempt: 1])
            break
        case "btnReload":
            goveeLights?.each { dev ->
                log.info "Reloading scene catalog for ${dev.displayName}"
                dev.sceneLoad()
            }
            break
    }
}


// ==================== SCENE LOGIC ====================

def applyScenes(data) {
    if (appPaused) { logDebug "Paused; not applying"; return }
    String reason = data?.reason ?: "manual"
    def attempt = data?.attempt ?: 1
    if (gameActive() && gamePattern in ["alternate", "auto"]) {
        applyAlternate(reason, attempt)
        return
    }
    String target = targetSceneName()
    def applied = []
    def missing = []

    goveeLights.each { dev ->
        def id = resolveSceneId(dev, target)
        if (id == null) {
            missing << dev.displayName
            return
        }
        logDebug "${dev.displayName}: setEffect(${id}) for '${target}'"
        dev.setEffect(id)
        applied << "${dev.displayName}=${id}"
    }

    state.lastApplied = [scene: target, at: now(), reason: reason, attempt: attempt, devices: applied]
    log.info "Scene '${target}' sent (${reason}, attempt ${attempt}): ${applied.join(', ') ?: 'none'}"
    if (missing) {
        log.warn "Scene '${target}' not found in catalog for: ${missing.join(', ')} (use 'Reload scene catalogs', or check the name)"
    }
    if (applied) {
        runIn(20, "verifyScenes", [data: [target: target, attempt: attempt], overwrite: true])
    }
}

/** Game-day two-color pattern: even bulbs get color A, odd bulbs color B, via the driver's per-segment command. Lights without segment control fall back to the game scene by name. */
def applyAlternate(String reason, attempt) {
    Map a = hexToColorMap(gameColorA)
    Map b = hexToColorMap(gameColorB)
    if (!a || !b) {
        log.warn "Game-day colors must be hex like #69BE28 (got '${gameColorA}' / '${gameColorB}'); nothing sent"
        return
    }
    int first = (gameFirstBulb ?: 0) as Integer
    def applied = []
    def fallback = []

    goveeLights.each { dev ->
        def sceneId = gameScene ? resolveSceneId(dev, gameScene) : null
        boolean preferScene = (gamePattern == "auto" && sceneId != null)
        if (!preferScene && dev.hasCommand("segmentedColorRgb")) {
            int count = Math.max(2, (settings["segCount_${dev.id}".toString()] ?: 15) as Integer)
            def evens = (0..<count).findAll { it % 2 == 0 }.collect { it + first }
            def odds = (0..<count).findAll { it % 2 == 1 }.collect { it + first }
            logDebug "${dev.displayName}: segments ${evens} <- ${gameColorA}, ${odds} <- ${gameColorB}"
            dev.segmentedColorRgb(evens.toString(), a)
            dev.segmentedColorRgb(odds.toString(), b)
            applied << "${dev.displayName} (${count} bulbs)"
        } else if (sceneId != null) {
            dev.setEffect(sceneId)
            fallback << "${dev.displayName}=${sceneId}"
        } else {
            log.warn "${dev.displayName}: no segment control and scene '${gameScene}' not in its catalog; left as is"
        }
    }

    String label = "Alternate ${gameColorA}/${gameColorB}"
    state.lastApplied = [scene: label, at: now(), reason: reason, attempt: attempt, devices: applied + fallback]
    state.lastVerify = [scene: label, at: now(), attempt: attempt, confirmed: [], unconfirmed: [], note: "segment commands cannot be confirmed by the driver"]
    log.info "Game-day pattern ${label} sent (${reason}, attempt ${attempt}): segments on ${applied.join(', ') ?: 'none'}${fallback ? '; scene fallback on ' + fallback.join(', ') : ''}"
}

/** "#RRGGBB" -> [hue: 0-100, saturation: 0-100, level: 0-100] as the Govee driver's color map expects; null if malformed. */
Map hexToColorMap(String hex) {
    String h = hex?.trim()
    if (!(h ==~ /^#?[0-9A-Fa-f]{6}$/)) return null
    if (!h.startsWith("#")) h = "#" + h
    def rgb = hubitat.helper.ColorUtils.hexToRGB(h)
    def hsv = hubitat.helper.ColorUtils.rgbToHSV(rgb)
    return [hue: Math.round(hsv[0] as Double) as Integer, saturation: Math.round(hsv[1] as Double) as Integer, level: Math.max(1, Math.round(hsv[2] as Double) as Integer)]
}

boolean gameActive() {
    if (scheduleEnabled && state.gameWindowActive) return true
    return gameSwitch && gameSwitch.currentValue("switch") == "on"
}


// ==================== GAME SCHEDULE ====================

/** Seahawks 2026 regular season, kickoff in Pacific time (seahawks.com schedule release). Week 18 @ Rams is TBD; the assumed slot below is replaced via 'Schedule changes' once announced. */
List gameSchedule() {
    def games = [
        [date: "2026-09-09", time: "17:20", label: "Wk1 vs Patriots"],
        [date: "2026-09-20", time: "13:25", label: "Wk2 @ Cardinals"],
        [date: "2026-09-27", time: "10:00", label: "Wk3 @ Commanders"],
        [date: "2026-10-04", time: "13:25", label: "Wk4 vs Chargers"],
        [date: "2026-10-11", time: "13:25", label: "Wk5 vs 49ers"],
        [date: "2026-10-15", time: "17:15", label: "Wk6 @ Broncos (TNF)"],
        [date: "2026-10-25", time: "17:20", label: "Wk7 vs Chiefs (SNF)"],
        [date: "2026-11-02", time: "17:15", label: "Wk8 vs Bears (MNF)"],
        [date: "2026-11-08", time: "13:25", label: "Wk9 vs Cardinals"],
        [date: "2026-11-15", time: "13:05", label: "Wk10 @ Raiders"],
        [date: "2026-11-29", time: "13:25", label: "Wk12 @ 49ers"],
        [date: "2026-12-07", time: "17:15", label: "Wk13 vs Cowboys (MNF)"],
        [date: "2026-12-13", time: "13:25", label: "Wk14 vs Giants"],
        [date: "2026-12-19", time: "14:00", label: "Wk15 @ Eagles (Sat)"],
        [date: "2026-12-25", time: "17:15", label: "Wk16 vs Rams (Christmas)"],
        [date: "2027-01-03", time: "10:00", label: "Wk17 @ Panthers"],
        [date: "2027-01-10", time: "13:25", label: "Wk18 @ Rams (TBD, assumed)"]
    ]
    // Overrides from settings: '-YYYY-MM-DD' removes; 'YYYY-MM-DD HH:mm label' adds/replaces that date
    (extraGames ?: "").split(/\r?\n/).each { String line ->
        String l = line.trim()
        if (!l) return
        if (l.startsWith("-")) {
            String d = l.substring(1).trim()
            games.removeAll { it.date == d }
            return
        }
        def m = (l =~ /^(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})\s*(.*)$/)
        if (m.matches()) {
            games.removeAll { it.date == m.group(1) }
            games << [date: m.group(1), time: m.group(2), label: m.group(3) ?: "added game"]
        } else {
            log.warn "Schedule change line not understood: '${l}'"
        }
    }
    return games.sort { it.date + " " + it.time }
}

Date gameKickoff(Map g) {
    def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd H:mm")
    sdf.setTimeZone(location.timeZone)
    return sdf.parse("${g.date} ${g.time}")
}

/** start = kickoff - lead. minEnd = kickoff + hold (earliest a power-off may end the night). end = hard end: 10:00 the next morning in all-night mode, else minEnd. */
Map gameWindow(Map g) {
    long kick = gameKickoff(g).time
    long lead = ((gameLeadMinutes ?: 30) as Long) * 60000L
    long hold = ((gameHoldMinutes ?: 240) as Long) * 60000L
    Date minEnd = new Date(kick + hold)
    Date hardEnd = minEnd
    if (gameAllNight != false) {
        def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
        sdf.setTimeZone(location.timeZone)
        hardEnd = new Date(sdf.parse("${g.date} 10:00").time + 86400000L)
    }
    return [start: new Date(kick - lead), minEnd: minEnd, end: hardEnd, game: g]
}

/** Arm the game window: today's game, or yesterday's when its night is still running. Called at init and at the 00:05 rollover. */
def scheduleTodaysGame() {
    unschedule("gameWindowStart")
    if (!scheduleEnabled) { if (state.gameWindowActive) endGameWindow(true); return }
    long t = now()
    String today = new Date(t).format("yyyy-MM-dd", location.timeZone)
    String yesterday = new Date(t - 86400000L).format("yyyy-MM-dd", location.timeZone)
    def windows = gameSchedule().findAll { it.date == today || it.date == yesterday }.collect { gameWindow(it) }
    Map active = windows.find { t >= it.start.time && t < it.end.time }
    if (active) {
        unschedule("gameWindowEnd")
        runOnce(active.end, "gameWindowEnd")
        state.gameMinEnd = active.minEnd.time
        state.gameLabel = active.game.label
        if (!state.gameWindowActive) gameWindowStart()
        return
    }
    if (state.gameWindowActive) endGameWindow(true)
    Map upcoming = windows.find { t < it.start.time }
    if (upcoming) {
        runOnce(upcoming.start, "gameWindowStart")
        state.gameMinEnd = upcoming.minEnd.time
        state.gameLabel = upcoming.game.label
        log.info "Game day: ${upcoming.game.label} kicks off ${upcoming.game.time}; game look from ${upcoming.start.format('HH:mm', location.timeZone)}" +
                 (gameAllNight != false ? " until the lights go off for the night" : " to ${upcoming.end.format('HH:mm', location.timeZone)}")
    } else {
        logDebug "No game today"
    }
}

def gameWindowStart() {
    state.gameWindowActive = true
    String today = new Date().format("yyyy-MM-dd", location.timeZone)
    def g = gameSchedule().find { it.date == today }
    if (g) {
        Map w = gameWindow(g)
        state.gameMinEnd = w.minEnd.time
        state.gameLabel = g.label
        unschedule("gameWindowEnd")
        runOnce(w.end, "gameWindowEnd")
    }
    log.info "Game window started (${state.gameLabel ?: 'game'})"
    if (appPaused) return
    if (!anyPowerOn()) { logDebug "Lights unpowered; game look will follow at power-on"; return }
    runIn(2, "applyScenes", [data: [reason: "game window start", attempt: 1], overwrite: false])
    runIn(62, "applyScenes", [data: [reason: "game window start", attempt: 2], overwrite: false])
}

/** Hard end (next morning 10:00 in all-night mode, or kickoff + hold otherwise). */
def gameWindowEnd() {
    endGameWindow(true)
}

def endGameWindow(boolean reapply) {
    if (!state.gameWindowActive) return
    state.gameWindowActive = false
    unschedule("gameWindowEnd")
    log.info "Game window ended (${state.gameLabel ?: 'game'})"
    if (!reapply || appPaused || !anyPowerOn()) return
    runIn(2, "applyScenes", [data: [reason: "game window end", attempt: 1], overwrite: false])
    runIn(62, "applyScenes", [data: [reason: "game window end", attempt: 2], overwrite: false])
}

String nextGameText() {
    long t = now()
    def upcoming = gameSchedule().find { gameWindow(it).end.time > t }
    if (!upcoming) return "none left in the table"
    Map w = gameWindow(upcoming)
    String when = gameKickoff(upcoming).format("EEE MMM d, h:mm a", location.timeZone)
    String win = gameAllNight != false ? "from ${w.start.format('h:mm a', location.timeZone)} until the lights go off that night" : "${w.start.format('h:mm a', location.timeZone)} to ${w.end.format('h:mm a', location.timeZone)}"
    return "${upcoming.label}, kickoff ${when} (game look ${win})${state.gameWindowActive ? ' - ACTIVE NOW' : ''}"
}

/** The driver only records effectNum when Govee's cloud accepted the command, so a mismatch means it failed (usually "device offline"). */
def verifyScenes(data) {
    String target = data?.target ?: targetSceneName()
    def confirmed = []
    def unconfirmed = []
    goveeLights.each { dev ->
        def id = resolveSceneId(dev, target)
        if (id == null) return
        def current = dev.currentValue("effectNum")
        if (current?.toString() == id.toString()) {
            confirmed << dev.displayName
        } else {
            unconfirmed << "${dev.displayName} (driver effectNum=${current ?: 'none'})"
        }
    }
    state.lastVerify = [scene: target, at: now(), attempt: data?.attempt, confirmed: confirmed, unconfirmed: unconfirmed]
    if (unconfirmed) {
        log.warn "Scene '${target}' NOT confirmed on ${unconfirmed.join(', ')}: Govee's cloud rejected the command, usually 'device offline' (check the light has power and Wi-Fi). Confirmed: ${confirmed ?: 'none'}"
    } else {
        log.info "Scene '${target}' confirmed by the driver on ${confirmed.join(', ')}"
    }
}

/** The scene that should be showing right now: game override, then the active date window, then the default. */
String targetSceneName() {
    if (gameActive()) {
        if (gamePattern in ["alternate", "auto"]) return "Alternate ${gameColorA}/${gameColorB}"
        if (gameScene) return gameScene.trim()
    }
    def w = activeWindow()
    return (w ? w.scene : defaultScene)?.trim()
}

List windows() {
    def list = []
    if (halloweenEnabled) list << [name: "Halloween", start: halloweenStart, end: halloweenEnd, scene: halloweenScene]
    if (custom1Name) list << [name: custom1Name, start: custom1Start, end: custom1End, scene: custom1Scene]
    if (custom2Name) list << [name: custom2Name, start: custom2Start, end: custom2End, scene: custom2Scene]
    return list
}

Map activeWindow() {
    String today = new Date().format("MM-dd", location.timeZone)
    return windows().find { w -> w.scene && validDate(w.start) && validDate(w.end) && inWindow(today, w.start, w.end) }
}

/** MM-DD comparison; a window whose end precedes its start wraps the year end. */
boolean inWindow(String today, String start, String end) {
    if (start <= end) return today >= start && today <= end
    return today >= start || today <= end
}

boolean validDate(String s) {
    return s != null && s ==~ /^(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$/
}

boolean anyPowerOn() {
    return powerSwitches?.any { it.currentValue("switch") == "on" }
}

/** Look a scene name up in one light's lightEffects catalog. Exact (case-insensitive) match wins; then prefix match. Lowest id breaks ties. */
def resolveSceneId(dev, String spec) {
    if (!spec) return null
    Map catalog = sceneCatalog(dev)
    if (!catalog) return null
    for (String name : spec.split(/\|/)) {
        String want = name.trim().toLowerCase()
        if (!want) continue
        def exact = catalog.findAll { id, n -> n?.toString()?.trim()?.toLowerCase() == want }
        def hits = exact ?: catalog.findAll { id, n -> n?.toString()?.trim()?.toLowerCase()?.startsWith(want) }
        if (!hits) continue
        def ids = hits.keySet().findAll { it?.toString()?.isLong() }.collect { it.toString() as Long }
        if (ids) return ids.min()
    }
    return null
}

Map sceneCatalog(dev) {
    String raw = dev.currentValue("lightEffects")
    if (!raw) return [:]
    try {
        def parsed = new JsonSlurper().parseText(raw)
        return (parsed instanceof Map) ? parsed : [:]
    } catch (e) {
        log.warn "${dev.displayName}: could not parse lightEffects (${e.message})"
        return [:]
    }
}


// ==================== STATUS ====================

String statusHtml() {
    if (!goveeLights) return "Select the Govee lights first."
    String today = new Date().format("MM-dd", location.timeZone)
    def w = activeWindow()
    String target = targetSceneName()
    String gameState = (gameSwitch ? "switch=" + gameSwitch.currentValue("switch") : "no switch") + (scheduleEnabled ? ", schedule window " + (state.gameWindowActive ? "ACTIVE" : "inactive") : "")
    StringBuilder sb = new StringBuilder()
    sb << "<b>Today</b> ${today}: window = ${w ? w.name : 'none'}, game day: ${gameState}, "
    sb << "target scene = <b>${target}</b><br/>"
    String power = powerSwitches ? powerSwitches.collect { it.displayName + '=' + it.currentValue('switch') }.join(', ') : 'none selected'
    sb << "<b>Power</b>: ${power}<br/>"
    goveeLights.each { dev ->
        Map catalog = sceneCatalog(dev)
        String current = dev.currentValue("effectName") ?: dev.currentValue("effectNum") ?: "?"
        if (gameActive() && gamePattern in ["alternate", "auto"]) {
            sb << "<b>${dev.displayName}</b>: game-day override active "
        } else {
            def id = resolveSceneId(dev, target)
            sb << "<b>${dev.displayName}</b>: '${target}' &rarr; ${id != null ? id : notFound()} "
        }
        sb << "(catalog: ${catalog.size()} scenes; driver reports current effect: ${current})<br/>"
        windows().findAll { it.scene }.each { win ->
            def wid = resolveSceneId(dev, win.scene)
            sb << "&nbsp;&nbsp;${win.name}: '${win.scene}' &rarr; ${wid != null ? wid : notFound()}<br/>"
        }
        if (gameSwitch && gamePattern in ["alternate", "auto"]) {
            def gid = gameScene ? resolveSceneId(dev, gameScene) : null
            def cnt = settings["segCount_${dev.id}".toString()] ?: 15
            String seg = dev.hasCommand("segmentedColorRgb") ? "segments supported, ${cnt} bulbs" : "NO segment control"
            String plan = (gamePattern == "auto" && gid != null) ? "scene '${gameScene}' &rarr; ${gid}" : "alternate ${gameColorA} / ${gameColorB} (${seg})"
            sb << "&nbsp;&nbsp;Game day: ${plan}<br/>"
        } else if (gameSwitch && gameScene) {
            def gid = resolveSceneId(dev, gameScene)
            sb << "&nbsp;&nbsp;Game day: '${gameScene}' &rarr; ${gid != null ? gid : notFound()}<br/>"
        }
    }
    if (state.lastApplied) {
        String when = new Date(state.lastApplied.at as Long).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        sb << "<b>Last sent</b>: '${state.lastApplied.scene}' at ${when} (${state.lastApplied.reason}, attempt ${state.lastApplied.attempt})<br/>"
    } else {
        sb << "<b>Last sent</b>: never<br/>"
    }
    if (state.lastVerify) {
        String vwhen = new Date(state.lastVerify.at as Long).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        String unconfirmed = state.lastVerify.unconfirmed ? state.lastVerify.unconfirmed.join(', ') : 'none'
        sb << "<b>Last verification</b> at ${vwhen}: confirmed on ${state.lastVerify.confirmed ?: 'none'}; not confirmed on ${unconfirmed}"
    } else {
        sb << "<b>Last verification</b>: none yet"
    }
    return sb.toString()
}


String notFound() {
    return '<span style="color:red">NOT FOUND</span>'
}


// ==================== LOGGING ====================

def logDebug(msg) {
    if (logEnable) log.debug msg
}
