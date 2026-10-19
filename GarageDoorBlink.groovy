/**
 * Garage Door Blink
 *
 * Blinks the kitchen pendant (or any lights) when the garage door opens, so whoever is inside
 * knows someone just pulled in.
 *
 * How it blinks: Hue lights and groups get the Hue bridge's "on_off" signal through the Hue V2 API.
 * The bridge toggles them between full brightness and off for the chosen duration and then puts
 * them back exactly as they were, without reporting any switch/level change, so the Great Room
 * Lighting app's manual-override detection never sees it. This needs a one-time pairing with the
 * bridge (press the bridge's link button, then the Pair button in this app). Until paired, or if
 * a light can't be resolved on the bridge, Hue lights fall back to the driver's flashOnce (a soft
 * brightness pulse that is hard to see when the light is already bright). Lights without flashOnce
 * are toggled on/off and restored afterwards; don't use that fallback on lights another automation
 * watches for manual changes.
 *
 * v2.0  2026-10-19  Hue V2 "on_off" signaling (real off/on blink, state preserved) with bridge pairing
 *                   and automatic V1->V2 id resolution; flashOnce kept as the fallback.
 * v1.0  2026-10-15  First version: trigger on door opening/open or contact open, Hue alert blinks,
 *                   toggle fallback, cooldown so opening+open blink once, optional mode/time limits.
 */

import groovy.transform.Field

@Field static final String APP_VERSION = "2.0"

definition(
    name: "Garage Door Blink",
    namespace: "ashwinma14",
    author: "Ashwin Appiah",
    description: "Blink lights when the garage door opens",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: false
)

preferences {
    page(name: "mainPage", title: "Garage Door Blink v${APP_VERSION}", install: true, uninstall: true) {
        section("Trigger") {
            input "garageDoors", "capability.garageDoorControl", title: "Garage door(s)", multiple: true, required: false,
                description: "Blinks when the door reports opening or open"
            input "contactSensors", "capability.contactSensor", title: "Or contact/tilt sensor(s)", multiple: true, required: false,
                description: "Blinks when the sensor reports open"
        }
        section("Lights") {
            input "blinkLights", "capability.switch", title: "Lights to blink", multiple: true, required: true, submitOnChange: true
            input "signalSeconds", "number", title: "Hue signal length (seconds)", defaultValue: 4, range: "1..30", required: true, width: 6
            input "blinkCount", "number", title: "Fallback: number of blinks", defaultValue: 3, range: "1..10", required: true, width: 6
            input "blinkIntervalMs", "number", title: "Fallback: time between blinks (ms)", defaultValue: 1500, range: "800..5000", required: true, width: 6
            paragraph "Hue lights blink through the bridge's on/off signal and keep their state. Others use the fallback."
        }
        section("Hue bridge") {
            input "bridgeIp", "text", title: "Bridge IP address", required: false, submitOnChange: true
            paragraph hueStatusText()
            if (bridgeIp && !state.hueKey) {
                paragraph "Press the round link button on top of the Hue bridge, then within 30 seconds press:"
                input "btnPair", "button", title: "Pair with Hue bridge"
            }
            if (state.hueKey) {
                input "btnResolve", "button", title: "Re-check lights on bridge", width: 6
                input "btnForget", "button", title: "Forget pairing", width: 6
            }
            input "btnTest", "button", title: "Test blink now"
        }
        section("Limits") {
            input "cooldownSec", "number", title: "Ignore further openings for (seconds)", defaultValue: 60, required: true
            input "onlyModes", "mode", title: "Only in these modes (blank = any)", multiple: true, required: false
            input "startTime", "time", title: "Only after (blank = any time)", required: false, width: 6
            input "endTime", "time", title: "Only before", required: false, width: 6
        }
        section("Logging") {
            input "debugLogging", "bool", title: "Debug logging", defaultValue: false
            label title: "App name", required: false
        }
    }
}

def installed() { initialize() }
def updated() { unsubscribe(); unschedule(); initialize() }

def initialize() {
    if (garageDoors) subscribe(garageDoors, "door", doorHandler)
    if (contactSensors) subscribe(contactSensors, "contact.open", contactHandler)
    state.blinking = false
    if (state.hueKey && bridgeIp) resolveHueIds()
    log.info "Garage Door Blink v${APP_VERSION} ready: doors ${garageDoors*.displayName ?: '-'}, sensors ${contactSensors*.displayName ?: '-'}, lights ${blinkLights*.displayName}, Hue ${state.hueKey ? 'paired' : 'not paired'}"
}

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnPair": pairWithBridge(); break
        case "btnResolve": resolveHueIds(); break
        case "btnForget":
            state.remove("hueKey"); state.remove("hueIds"); state.pairStatus = "Pairing forgotten."
            break
        case "btnTest": trigger("Test button"); break
    }
}

String hueStatusText() {
    if (!bridgeIp) return "Enter the bridge IP to enable Hue signaling (find it in the Hue app under Settings > My Hue system)."
    if (!state.hueKey) return state.pairStatus ?: "Not paired. Hue lights will use the fallback blink until paired."
    Map ids = (state.hueIds ?: [:]) as Map
    List resolved = (blinkLights ?: []).findAll { ids[it.id.toString()] }*.displayName
    List unresolved = (blinkLights ?: []).findAll { !ids[it.id.toString()] }*.displayName
    String s = "Paired. Signal via bridge: ${resolved ?: 'none yet'}."
    if (unresolved) s += " Fallback blink: ${unresolved}."
    if (state.pairStatus) s += " ${state.pairStatus}"
    return s
}

// ---------- Hue bridge pairing and id lookup ----------

void pairWithBridge() {
    if (!bridgeIp) return
    state.pairStatus = "Pairing..."
    Map params = [
        uri: "https://${bridgeIp}", path: "/api", contentType: "application/json",
        body: [devicetype: "hubitat#garage_door_blink", generateclientkey: true],
        ignoreSSLIssues: true, timeout: 10
    ]
    asynchttpPost("pairResponse", params)
}

void pairResponse(resp, data) {
    try {
        def json = resp.json
        def first = (json instanceof List) ? json[0] : json
        if (first?.success?.username) {
            state.hueKey = first.success.username
            state.pairStatus = "Paired on ${new Date().format('MMM d HH:mm', location.timeZone)}."
            log.info "Garage Door Blink paired with Hue bridge at ${bridgeIp}"
            resolveHueIds()
        } else if (first?.error?.type == 101) {
            state.pairStatus = "Bridge says the link button wasn't pressed. Press it and try again within 30 seconds."
            log.warn "Hue pairing failed: link button not pressed"
        } else {
            state.pairStatus = "Pairing failed: HTTP ${resp.status} ${first?.error?.description ?: ''}"
            log.warn "Hue pairing failed: HTTP ${resp.status} ${resp.data}"
        }
    } catch (e) {
        state.pairStatus = "Pairing failed: ${e.message}"
        log.warn "Hue pairing failed: ${e}"
    }
}

/** Looks up the Hue V2 resource id for each selected CoCoHue light/group, matching V1 DNIs by id_v1. */
void resolveHueIds() {
    if (!state.hueKey || !bridgeIp) return
    ["grouped_light", "light"].each { String rtype ->
        Map params = [
            uri: "https://${bridgeIp}", path: "/clip/v2/resource/${rtype}",
            headers: ["hue-application-key": state.hueKey], contentType: "application/json",
            ignoreSSLIssues: true, timeout: 15
        ]
        asynchttpGet("resolveResponse", params, [rtype: rtype])
    }
}

void resolveResponse(resp, Map data) {
    if (resp.status != 200 || resp.json == null) {
        state.pairStatus = "Bridge lookup failed (HTTP ${resp.status}); key may be invalid. Forget pairing and pair again."
        log.warn "Hue ${data.rtype} lookup failed: HTTP ${resp.status}"
        return
    }
    List resources = (resp.json.data ?: []) as List
    Map ids = (state.hueIds ?: [:]) as Map
    String want = data.rtype == "grouped_light" ? "Group" : "Light"
    (blinkLights ?: []).each { dev ->
        List parts = dev.deviceNetworkId.tokenize("/")
        if (parts.size() < 4 || parts[0] != "CCH" || parts[-2] != want) return
        String last = parts[-1]
        def match
        if (last.length() > 32) {
            match = resources.find { it.id == last }
        } else {
            String v1 = (want == "Group" ? "/groups/" : "/lights/") + last
            match = resources.find { it.id_v1 == v1 }
        }
        if (match) {
            ids[dev.id.toString()] = [rtype: data.rtype, id: match.id]
            logDebug "${dev.displayName} -> ${data.rtype}/${match.id}"
        } else {
            log.warn "Garage Door Blink: ${dev.displayName} not found on bridge as ${data.rtype}; it will use the fallback blink"
        }
    }
    state.hueIds = ids
    state.pairStatus = null
}

// ---------- Triggering ----------

def doorHandler(evt) {
    if (evt.value in ["opening", "open"]) trigger("${evt.displayName} ${evt.value}")
}

def contactHandler(evt) {
    trigger("${evt.displayName} open")
}

void trigger(String reason) {
    long t = now()
    long last = (state.lastBlink ?: 0L) as Long
    boolean isTest = reason.startsWith("Test")
    if (!isTest && t - last < ((cooldownSec ?: 60) as Long) * 1000L) { logDebug "${reason}: in cooldown, skipped"; return }
    if (!isTest && onlyModes && !(location.mode in onlyModes)) { logDebug "${reason}: mode ${location.mode} not allowed, skipped"; return }
    if (!isTest && startTime && endTime && !timeOfDayIsBetween(toDateTime(startTime), toDateTime(endTime), new Date(), location.timeZone)) {
        logDebug "${reason}: outside time window, skipped"; return
    }
    if (state.blinking) { logDebug "${reason}: already blinking"; return }
    state.lastBlink = t

    List signalDevs = signalLights()
    List fallbackDevs = (blinkLights ?: []).findAll { !(it in signalDevs) }
    log.info "${reason} - signaling ${signalDevs*.displayName ?: '-'} for ${signalSeconds ?: 4}s; fallback blink ${fallbackDevs*.displayName ?: '-'}"

    signalDevs.each { sendHueSignal(it) }
    if (fallbackDevs) startFallbackBlink(fallbackDevs)
}

List signalLights() {
    if (!state.hueKey || !bridgeIp) return []
    Map ids = (state.hueIds ?: [:]) as Map
    return (blinkLights ?: []).findAll { ids[it.id.toString()] }
}

void sendHueSignal(dev) {
    Map ref = ((state.hueIds ?: [:]) as Map)[dev.id.toString()] as Map
    Integer ms = ((signalSeconds ?: 4) as Integer) * 1000
    Map params = [
        uri: "https://${bridgeIp}", path: "/clip/v2/resource/${ref.rtype}/${ref.id}",
        headers: ["hue-application-key": state.hueKey], contentType: "application/json",
        body: [signaling: [signal: "on_off", duration: ms]],
        ignoreSSLIssues: true, timeout: 10
    ]
    asynchttpPut("signalResponse", params, [name: dev.displayName, devId: dev.id.toString()])
}

void signalResponse(resp, Map data) {
    if (resp.status == 200) { logDebug "signal sent to ${data.name}"; return }
    log.warn "Garage Door Blink: Hue signal to ${data.name} failed (HTTP ${resp.status} ${resp.errorMessage ?: ''}); using fallback blink"
    def dev = (blinkLights ?: []).find { it.id.toString() == data.devId }
    if (dev) startFallbackBlink([dev])
}

// ---------- Fallback: flashOnce for Hue drivers, on/off toggle for everything else ----------

void startFallbackBlink(List devs) {
    if (state.blinking) return
    state.blinking = true
    state.fallbackIds = devs*.id*.toString()
    Map saved = [:]
    devs.findAll { !it.hasCommand("flashOnce") }.each { dev ->
        saved[dev.id.toString()] = [sw: dev.currentValue("switch"), level: dev.hasAttribute("level") ? dev.currentValue("level") : null]
    }
    state.saved = saved
    state.step = 0
    blinkStep()
}

List fallbackDevs() {
    List ids = (state.fallbackIds ?: []) as List
    return (blinkLights ?: []).findAll { it.id.toString() in ids }
}

/** One step: Hue lights get an alert on each blink; toggle lights flip on every step (two steps per blink). */
def blinkStep() {
    Integer count = (blinkCount ?: 3) as Integer
    Integer step = (state.step ?: 0) as Integer
    Integer interval = (blinkIntervalMs ?: 1500) as Integer
    List devs = fallbackDevs()
    List alertDevs = devs.findAll { it.hasCommand("flashOnce") }
    List toggleDevs = devs.findAll { !it.hasCommand("flashOnce") }

    if (step >= count * 2) { finish(); return }

    if (step % 2 == 0) alertDevs.each { it.flashOnce() }
    toggleDevs.each { dev ->
        Map s = (state.saved ?: [:])[dev.id.toString()] as Map
        boolean wasOn = s?.sw == "on"
        boolean wantOn = (step % 2 == 0) ? !wasOn : wasOn
        wantOn ? dev.on() : dev.off()
    }
    state.step = step + 1
    runInMillis((interval / 2) as Long, "blinkStep")
}

def finish() {
    fallbackDevs().findAll { !it.hasCommand("flashOnce") }.each { dev ->
        Map s = (state.saved ?: [:])[dev.id.toString()] as Map
        if (!s) return
        if (s.sw == "on") {
            if (s.level != null && dev.hasCommand("setLevel")) dev.setLevel(s.level as Integer) else dev.on()
        } else dev.off()
    }
    state.blinking = false
    state.remove("saved")
    state.remove("fallbackIds")
    logDebug "fallback blink done"
}

void logDebug(String msg) { if (debugLogging) log.debug msg }
