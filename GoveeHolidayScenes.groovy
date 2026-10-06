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
 *  Version: 1.8 - single bulbs: a two-color pattern paints a whole bulb one of its colors (first/second, chosen per bulb),
 *                 so two pathway bulbs can be purple and orange next to the alternating deck lights; "self-powered"
 *                 lights (own photocell or wall switch, so no plug event) are re-sent every few minutes while the plugs
 *                 are on until the driver confirms the look (cloudAPI=Success for colors, effectNum for scenes)
 *  Version: 1.7 - a window's scene may be a two-color bulb pattern: 'alt:#8B00FF/#FF5500' paints even/odd bulbs on any
 *                 light with segment control, so the deck lights can alternate purple and orange while the bulb string
 *                 shows a DIY scene ('GH-iVIOcJ | alt:#8B00FF/#FF5500': each light takes the first look it can do)
 *  Version: 1.6 - single-threaded: two plugs reporting "on" in the same second no longer schedule a second set of re-sends
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
    iconX2Url: "",
    singleThreaded: true   // plug events arrive together; serialize handlers so unschedule/schedule pairs never interleave
)

preferences {
    page(name: "mainPage")
}

def appVersion() { return "1.8" }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Govee Holiday Scenes v${appVersion()}", install: true, uninstall: true) {

        section("<b>Lights</b>") {
            input "goveeLights", "capability.lightEffects", title: "Govee lights (Govee v2 driver)", multiple: true, required: true, submitOnChange: true
            goveeLights?.each { dev ->
                if (dev.hasCommand("segmentedColorRgb")) {
                    input "segCount_${dev.id}".toString(), "number", title: "${dev.displayName}: bulbs/segments (used by the alternate-colors pattern)", defaultValue: 15, required: false, width: 6
                } else {
                    input "altSlot_${dev.id}".toString(), "enum", title: "${dev.displayName}: single bulb, so a two-color pattern paints it one color", options: ["A": "the first color", "B": "the second color"], defaultValue: defaultSlot(dev), required: false, width: 6
                }
                input "selfPowered_${dev.id}".toString(), "bool", title: "${dev.displayName}: has its own power (photocell or wall switch, not one of the plugs below), so keep re-sending its look while the plugs are on until the driver confirms it", defaultValue: false, width: 6
            }
            input "powerSwitches", "capability.switch", title: "Plugs/switches that power these lights", multiple: true, required: true, submitOnChange: true
            input "bootDelay", "number", title: "Seconds after power-on before the first scene command", defaultValue: 45, required: true
            input "applyAttempts", "number", title: "How many times to send the scene after power-on (1-4; re-sends at +75s, +255s and +15 min for slow Wi-Fi rejoins)", defaultValue: 4, required: true
            if (selfPoweredLights()) {
                input "selfRetryMinutes", "number", title: "Self-powered lights: minutes between re-sends while unconfirmed", defaultValue: 5, required: true, width: 6
                input "selfRetryHours", "number", title: "Self-powered lights: stop re-sending this many hours after the plugs turn on", defaultValue: 6, required: true, width: 6
            }
        }

        section("<b>Scenes</b>") {
            paragraph "Scene names must match the light's scene catalog (see Status below). Give alternatives separated by | and each light uses the first one it can do, e.g. 'Static warm white | White Light'. " +
                      "An alternative may also be a two-color bulb pattern, 'alt:#8B00FF/#FF5500' (even bulbs the first color, odd bulbs the second), which any light with per-bulb control can show without a DIY scene; " +
                      "a single bulb shows one of the two colors (chosen per bulb in the Lights section). " +
                      "Windows are MM-DD and may wrap the year end (e.g. 11-27 to 01-06)."
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
                    options: ["scene": "A scene by name (built-in or DIY)", "alternate": "Alternate two colors bulb by bulb (no DIY needed; a single bulb takes one of the two colors)", "auto": "The scene where a light has it, the alternate-colors pattern elsewhere"]
                if (gamePattern in ["alternate", "auto"]) {
                    paragraph "Bulbs are painted even/odd with the two colors below (counts per light are set in the Lights section); a single bulb shows the color chosen for it there. The Govee driver cannot confirm segment commands and logs a spurious 'command failed' line for them even when they work, so check the lights."
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

    // Resume the self-powered re-send loop if the lights are on right now (a code update mid-evening must not drop it)
    if (state.confirmedLooks == null) state.confirmedLooks = [:]
    if (selfPoweredLights() && anyPowerOn()) {
        state.retryStartedAt = now()
        runIn(30, "retrySelfPowered")
    }

    def selfPowered = selfPoweredLights()
    log.info "Lights: ${goveeLights*.displayName.join(', ')} | Power: ${powerSwitches*.displayName.join(', ')} | " +
             "Self-powered: ${selfPowered ? selfPowered*.displayName.join(', ') : 'none'} | " +
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

    // Collapse several plugs switching together into one set of attempts (singleThreaded keeps these two lines atomic;
    // the time check keeps the attempt clock anchored to the first plug instead of sliding with each later one)
    long sincePowerOn = now() - ((state.powerOnAt ?: 0L) as Long)
    if (sincePowerOn >= 0 && sincePowerOn < 5000) {
        logDebug "Power-on within ${sincePowerOn} ms of the last one; keeping the attempts already scheduled"
        return
    }
    unschedule("applyScenes")
    unschedule("retrySelfPowered")
    state.powerOnAt = now()
    state.retryStartedAt = now()
    state.selfRetryCount = 0
    state.confirmedLooks = [:]   // everything rebooted (or may have); each light is confirmed again by its next send

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
        unschedule("retrySelfPowered")
        state.confirmedLooks = [:]
        state.retryStartedAt = null
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
    state.retryStartedAt = now()
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

/** Send every light (or data.devices only) its current look: the game-day look when a game is on, else the active window's scene. */
def applyScenes(data) {
    if (appPaused) { logDebug "Paused; not applying"; return }
    String reason = data?.reason ?: "manual"
    def attempt = data?.attempt ?: 1
    List only = data?.devices ? data.devices.collect { it.toString() } : null
    def lights = only ? goveeLights.findAll { it.id.toString() in only } : goveeLights
    boolean game = gameActive() && gamePattern in ["alternate", "auto"]
    String target = targetSceneName()
    def sent = []
    def missing = []

    lights.each { dev ->
        Map look = game ? gameLook(dev) : resolveLook(dev, target)
        if (look == null) {
            missing << dev.displayName
            return
        }
        String what = sendLook(dev, look)
        if (what) sent << what
    }

    state.lastApplied = [scene: target, at: now(), reason: reason, attempt: attempt, devices: sent]
    log.info "${game ? 'Game-day look' : 'Scene'} '${target}' sent (${reason}, attempt ${attempt}${only ? ', ' + lights.size() + ' light(s)' : ''}): ${sent.join(', ') ?: 'none'}"
    if (missing) {
        log.warn "'${target}' cannot be shown by: ${missing.join(', ')} (scene not in its catalog: use 'Reload scene catalogs', or check the name)"
    }
    if (sent) {
        runIn(20, "verifyScenes", [data: [target: target, attempt: attempt, devices: lights*.id.collect { it.toString() }], overwrite: true])
    }
}

/**
 * One light's look for a spec of |-separated alternatives: the first alternative this light can do.
 * A scene name resolves when it is in the light's catalog; 'alt:#RRGGBB/#RRGGBB' resolves as even/odd bulbs on a light
 * with per-bulb control, or as one of the two colors on a single bulb.
 * Returns [type: "scene", id, name], [type: "alt", a, b], [type: "solid", color, slot, a, b] or null.
 */
Map resolveLook(dev, String spec) {
    if (!spec) return null
    for (String alt : spec.split(/\|/)) {
        String token = alt.trim()
        if (!token) continue
        def m = (token =~ /(?i)^alt(?:ernate)?\s*:\s*(#?[0-9A-Fa-f]{6})\s*\/\s*(#?[0-9A-Fa-f]{6})$/)
        if (m.matches()) {
            Map two = twoColorLook(dev, normHex(m.group(1)), normHex(m.group(2)))
            if (two) return two
            continue
        }
        def id = resolveSceneId(dev, token)
        if (id != null) return [type: "scene", id: id, name: token]
    }
    return null
}

/** How this light shows a two-color pattern: even/odd bulbs when it has segment control, else the whole bulb in its slot's color. */
Map twoColorLook(dev, String a, String b) {
    if (dev.hasCommand("segmentedColorRgb")) return [type: "alt", a: a, b: b]
    if (dev.hasCommand("setColor")) {
        String slot = slotOf(dev)
        return [type: "solid", color: slot == "B" ? b : a, slot: slot, a: a, b: b]
    }
    return null
}

/** The game-day look for one light: the game scene where preferred and present, else the two-color pattern, else the scene fallback. */
Map gameLook(dev) {
    def sceneId = gameScene ? resolveSceneId(dev, gameScene) : null
    boolean preferScene = (gamePattern == "auto" && sceneId != null)
    if (!preferScene) {
        if (hexToColorMap(gameColorA) && hexToColorMap(gameColorB)) {
            Map two = twoColorLook(dev, normHex(gameColorA), normHex(gameColorB))
            if (two) return two
        } else {
            log.warn "Game-day colors must be hex like #69BE28 (got '${gameColorA}' / '${gameColorB}')"
        }
    }
    if (sceneId != null) return [type: "scene", id: sceneId, name: gameScene.trim()]
    return null
}

/** Stable identity of a look, used to remember which lights have confirmed it. */
String lookKey(Map look) {
    if (look == null) return null
    if (look.type == "scene") return "scene:${look.id}"
    if (look.type == "solid") return "solid:${look.color}"
    return "alt:${look.a}/${look.b}"
}

/** Send one look to one light. Returns a short description for the log, or null when nothing was sent. */
String sendLook(dev, Map look) {
    switch (look?.type) {
        case "scene":
            logDebug "${dev.displayName}: setEffect(${look.id}) for '${look.name}'"
            dev.setEffect(look.id)
            return "${dev.displayName}=${look.id}"
        case "alt":
            int count = paintAlternate(dev, look.a, look.b)
            return count ? "${dev.displayName}=${look.a}/${look.b} (${count} bulbs)" : null
        case "solid":
            return paintSolid(dev, look.color) ? "${dev.displayName}=${look.color} (color ${look.slot})" : null
    }
    return null
}

/** Which color of a two-color pattern a single bulb shows: its setting, else alternate by position among the single bulbs. */
String slotOf(dev) {
    def s = settings["altSlot_${dev.id}".toString()]
    return s ? s.toString() : defaultSlot(dev)
}

String defaultSlot(dev) {
    def singles = (goveeLights ?: []).findAll { !it.hasCommand("segmentedColorRgb") }
    int idx = singles.findIndexOf { it.id == dev.id }
    return (idx > 0 && idx % 2 == 1) ? "B" : "A"
}

List selfPoweredLights() {
    return (goveeLights ?: []).findAll { settings["selfPowered_${it.id}".toString()] }
}

String normHex(String h) {
    String x = h.trim().toUpperCase()
    return x.startsWith("#") ? x : "#" + x
}

/** Paint a single bulb one solid color through setColor. Returns false when the color is malformed. */
boolean paintSolid(dev, String hex) {
    Map c = hexToColorMap(hex)
    if (!c) {
        log.warn "${dev.displayName}: color must be hex like #8B00FF (got '${hex}'); nothing sent"
        return false
    }
    logDebug "${dev.displayName}: setColor(${c}) for ${hex}"
    dev.setColor(c)
    return true
}

/** Paint even bulbs color A and odd bulbs color B through the driver's per-segment command. Returns the bulb count used. */
int paintAlternate(dev, String hexA, String hexB) {
    Map a = hexToColorMap(hexA)
    Map b = hexToColorMap(hexB)
    if (!a || !b) {
        log.warn "${dev.displayName}: colors must be hex like #8B00FF (got '${hexA}' / '${hexB}'); nothing sent"
        return 0
    }
    int first = (gameFirstBulb ?: 0) as Integer
    int count = Math.max(2, (settings["segCount_${dev.id}".toString()] ?: 15) as Integer)
    def evens = (0..<count).findAll { it % 2 == 0 }.collect { it + first }
    def odds = (0..<count).findAll { it % 2 == 1 }.collect { it + first }
    logDebug "${dev.displayName}: segments ${evens} <- ${hexA}, ${odds} <- ${hexB}"
    dev.segmentedColorRgb(evens.toString(), a)
    dev.segmentedColorRgb(odds.toString(), b)
    return count
}

/** "#RRGGBB" ->[hue: 0-100, saturation: 0-100, level: 0-100] as the Govee driver's color map expects; null if malformed. */
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
        log.info "Game day: ${upcoming.game.label} kicks off ${upcoming.game.time}; game look whenever the lights are on from ${upcoming.start.format('HH:mm', location.timeZone)}" +
                 (gameAllNight != false ? " until they go off for the night" : " to ${upcoming.end.format('HH:mm', location.timeZone)}")
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
    state.retryStartedAt = now()
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
    state.retryStartedAt = now()
    runIn(2, "applyScenes", [data: [reason: "game window end", attempt: 1], overwrite: false])
    runIn(62, "applyScenes", [data: [reason: "game window end", attempt: 2], overwrite: false])
}

String nextGameText() {
    long t = now()
    def upcoming = gameSchedule().find { gameWindow(it).end.time > t }
    if (!upcoming) return "none left in the table"
    Map w = gameWindow(upcoming)
    String when = gameKickoff(upcoming).format("EEE MMM d, h:mm a", location.timeZone)
    String win = gameAllNight != false ? "whenever the lights are on from ${w.start.format('h:mm a', location.timeZone)} until they go off for the night" : "while the lights are on between ${w.start.format('h:mm a', location.timeZone)} and ${w.end.format('h:mm a', location.timeZone)}"
    return "${upcoming.label}, kickoff ${when} (game look ${win}; this app never switches the lights on, the plug automation does that at dusk)${state.gameWindowActive ? ' - ACTIVE NOW' : ''}"
}

/**
 * Check the lights that were just sent to. The driver only records effectNum when Govee's cloud accepted a scene, and it
 * sets cloudAPI to "Pending" before every command and "Success" only after the cloud accepted it, so a stale value means
 * the command failed (usually "device offline"). Segment commands get no usable answer from the driver.
 */
def verifyScenes(data) {
    String target = data?.target ?: targetSceneName()
    boolean game = gameActive() && gamePattern in ["alternate", "auto"]
    List sentTo = (data?.devices ?: goveeLights*.id).collect { it.toString() }
    Map confirmedLooks = (state.confirmedLooks ?: [:]) as Map
    def confirmed = []
    def unconfirmed = []
    def unverifiable = []
    goveeLights.each { dev ->
        String id = dev.id.toString()
        if (!(id in sentTo)) return
        Map look = game ? gameLook(dev) : resolveLook(dev, target)
        if (look == null) return
        String key = lookKey(look)
        String why = null
        if (look.type == "scene") {
            def current = dev.currentValue("effectNum")
            if (current?.toString() != look.id.toString()) why = "driver effectNum=${current ?: 'none'}"
        } else if (look.type == "solid") {
            def api = dev.currentValue("cloudAPI")
            if (api?.toString() != "Success") why = "driver cloudAPI=${api ?: 'none'}"
        } else {
            unverifiable << dev.displayName
            confirmedLooks[id] = key
            return
        }
        if (why) {
            unconfirmed << "${dev.displayName} (${why})"
            confirmedLooks.remove(id)
        } else {
            confirmed << dev.displayName
            confirmedLooks[id] = key
        }
    }
    state.confirmedLooks = confirmedLooks
    state.lastVerify = [scene: target, at: now(), attempt: data?.attempt, confirmed: confirmed, unconfirmed: unconfirmed,
                        note: unverifiable ? "segment commands cannot be confirmed by the driver (${unverifiable.join(', ')})" : null]
    if (unconfirmed) {
        log.warn "'${target}' NOT confirmed on ${unconfirmed.join(', ')}: Govee's cloud rejected the command, usually 'device offline' (check the light has power and Wi-Fi). Confirmed: ${confirmed ?: 'none'}"
    } else if (confirmed) {
        log.info "'${target}' confirmed by the driver on ${confirmed.join(', ')}"
    }
    armSelfRetry()
}

/** Self-powered lights whose current look the driver has not confirmed yet. */
List pendingSelfPowered() {
    boolean game = gameActive() && gamePattern in ["alternate", "auto"]
    String target = targetSceneName()
    Map confirmedLooks = (state.confirmedLooks ?: [:]) as Map
    return selfPoweredLights().findAll { dev ->
        Map look = game ? gameLook(dev) : resolveLook(dev, target)
        look != null && confirmedLooks[dev.id.toString()] != lookKey(look)
    }
}

/** Keep re-sending to unconfirmed self-powered lights while the plugs are on, until they confirm or the time limit passes. */
def armSelfRetry() {
    unschedule("retrySelfPowered")
    if (appPaused || !anyPowerOn()) return
    def pending = pendingSelfPowered()
    if (!pending) return
    long started = (state.retryStartedAt ?: 0L) as Long
    if (!started) { started = now(); state.retryStartedAt = started }
    long limit = Math.max(1, (selfRetryHours ?: 6) as Integer) * 3600000L
    if (now() - started > limit) {
        log.info "Still unconfirmed ${selfRetryHours ?: 6} h after the plugs turned on: ${pending*.displayName.join(', ')}; no more re-sends until the next power-on"
        return
    }
    int mins = Math.max(1, (selfRetryMinutes ?: 5) as Integer)
    runIn(mins * 60, "retrySelfPowered")
    logDebug "Re-sending to ${pending*.displayName.join(', ')} in ${mins} min"
}

def retrySelfPowered() {
    if (appPaused || !anyPowerOn()) return
    def pending = pendingSelfPowered()
    if (!pending) return
    state.selfRetryCount = ((state.selfRetryCount ?: 0) as Integer) + 1
    applyScenes([reason: "self-powered re-send", attempt: "r${state.selfRetryCount}", devices: pending*.id.collect { it.toString() }])
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
    boolean game = gameActive() && gamePattern in ["alternate", "auto"]
    Map confirmedLooks = (state.confirmedLooks ?: [:]) as Map
    goveeLights.each { dev ->
        Map catalog = sceneCatalog(dev)
        String current = dev.currentValue("effectName") ?: dev.currentValue("effectNum") ?: "?"
        if (game) {
            sb << "<b>${dev.displayName}</b>: game-day look &rarr; ${lookDesc(dev, gameLook(dev))} "
        } else {
            sb << "<b>${dev.displayName}</b>: '${target}' &rarr; ${lookText(dev, target)} "
        }
        sb << "(catalog: ${catalog.size()} scenes; driver reports current effect: ${current})<br/>"
        if (settings["selfPowered_${dev.id}".toString()]) {
            Map cur = game ? gameLook(dev) : resolveLook(dev, target)
            boolean ok = cur != null && confirmedLooks[dev.id.toString()] == lookKey(cur)
            sb << "&nbsp;&nbsp;Self-powered: ${ok ? 'current look confirmed by the driver' : 'not confirmed yet (re-sent every ' + (selfRetryMinutes ?: 5) + ' min while the plugs are on, up to ' + (selfRetryHours ?: 6) + ' h after power-on)'}<br/>"
        }
        windows().findAll { it.scene }.each { win ->
            sb << "&nbsp;&nbsp;${win.name}: '${win.scene}' &rarr; ${lookText(dev, win.scene)}<br/>"
        }
        if ((gameSwitch || scheduleEnabled) && gamePattern in ["alternate", "auto"]) {
            sb << "&nbsp;&nbsp;Game day: ${lookDesc(dev, gameLook(dev))}<br/>"
        } else if ((gameSwitch || scheduleEnabled) && gameScene) {
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
        if (state.lastVerify.note) sb << " (${state.lastVerify.note})"
    } else {
        sb << "<b>Last verification</b>: none yet"
    }
    return sb.toString()
}


String notFound() {
    return '<span style="color:red">NOT FOUND</span>'
}

String lookText(dev, String spec) {
    return lookDesc(dev, resolveLook(dev, spec))
}

String lookDesc(dev, Map look) {
    if (look == null) return notFound()
    if (look.type == "alt") {
        def cnt = settings["segCount_${dev.id}".toString()] ?: 15
        return "alternate ${look.a} / ${look.b} (${cnt} bulbs)"
    }
    if (look.type == "solid") return "solid ${look.color} (color ${look.slot} of ${look.a} / ${look.b})"
    return "${look.id} ('${look.name}')"
}


// ==================== LOGGING ====================

def logDebug(msg) {
    if (logEnable) log.debug msg
}
