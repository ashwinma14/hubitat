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

def appVersion() { return "1.1" }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Govee Holiday Scenes v${appVersion()}", install: true, uninstall: true) {

        section("<b>Lights</b>") {
            input "goveeLights", "capability.lightEffects", title: "Govee lights (Govee v2 driver)", multiple: true, required: true, submitOnChange: true
            input "powerSwitches", "capability.switch", title: "Plugs/switches that power these lights", multiple: true, required: true, submitOnChange: true
            input "bootDelay", "number", title: "Seconds after power-on before the first scene command", defaultValue: 45, required: true
            input "applyAttempts", "number", title: "How many times to send the scene after power-on (1-4; re-sends at +75s, +255s and +15 min for slow Wi-Fi rejoins)", defaultValue: 4, required: true
        }

        section("<b>Scenes</b>") {
            paragraph "Scene names must match the light's scene catalog (see Status below). Windows are MM-DD and may wrap the year end (e.g. 11-27 to 01-06)."
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
            input "gameSwitch", "capability.switch", title: "Game-day switch (e.g. Seahawks_Game)", required: false, submitOnChange: true
            if (gameSwitch) {
                input "gameScene", "text", title: "Scene while the game switch is on", defaultValue: "Seahawks Surge", required: true, submitOnChange: true
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
    if (gameSwitch && gameScene && gameSwitch.currentValue("switch") == "on") return gameScene.trim()
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
def resolveSceneId(dev, String name) {
    if (!name) return null
    Map catalog = sceneCatalog(dev)
    if (!catalog) return null
    String want = name.trim().toLowerCase()
    def exact = catalog.findAll { id, n -> n?.toString()?.trim()?.toLowerCase() == want }
    def hits = exact ?: catalog.findAll { id, n -> n?.toString()?.trim()?.toLowerCase()?.startsWith(want) }
    if (!hits) return null
    def ids = hits.keySet().findAll { it?.toString()?.isLong() }.collect { it.toString() as Long }
    return ids ? ids.min() : null
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
    String gameState = gameSwitch ? gameSwitch.currentValue("switch") : "n/a"
    StringBuilder sb = new StringBuilder()
    sb << "<b>Today</b> ${today}: window = ${w ? w.name : 'none'}, game switch = ${gameState}, "
    sb << "target scene = <b>${target}</b><br/>"
    String power = powerSwitches ? powerSwitches.collect { it.displayName + '=' + it.currentValue('switch') }.join(', ') : 'none selected'
    sb << "<b>Power</b>: ${power}<br/>"
    goveeLights.each { dev ->
        Map catalog = sceneCatalog(dev)
        def id = resolveSceneId(dev, target)
        String current = dev.currentValue("effectName") ?: dev.currentValue("effectNum") ?: "?"
        sb << "<b>${dev.displayName}</b>: '${target}' &rarr; ${id != null ? id : notFound()} "
        sb << "(catalog: ${catalog.size()} scenes; driver reports current effect: ${current})<br/>"
        windows().findAll { it.scene }.each { win ->
            def wid = resolveSceneId(dev, win.scene)
            sb << "&nbsp;&nbsp;${win.name}: '${win.scene}' &rarr; ${wid != null ? wid : notFound()}<br/>"
        }
        if (gameSwitch && gameScene) {
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
