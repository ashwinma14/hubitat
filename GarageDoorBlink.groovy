/**
 * Garage Door Blink
 *
 * Blinks the kitchen pendant (or any lights) when the garage door opens, so whoever is inside
 * knows someone just pulled in.
 *
 * How it blinks: lights whose driver has flashOnce (CoCoHue Hue lights and groups) get the Hue
 * bridge's own alert, repeated. The alert flashes the bulbs without changing their switch or
 * level attributes, so the Great Room Lighting app's manual-override detection never sees it
 * and nothing needs restoring. Lights without flashOnce fall back to on/off toggling with the
 * original state put back afterwards; don't use the fallback on lights another automation
 * watches for manual changes.
 *
 * v1.0  2026-10-15  First version: trigger on door opening/open or contact open, Hue alert blinks,
 *                   toggle fallback, cooldown so opening+open blink once, optional mode/time limits.
 */

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
    page(name: "mainPage", title: "Garage Door Blink", install: true, uninstall: true) {
        section("Trigger") {
            input "garageDoors", "capability.garageDoorControl", title: "Garage door(s)", multiple: true, required: false,
                description: "Blinks when the door reports opening or open"
            input "contactSensors", "capability.contactSensor", title: "Or contact/tilt sensor(s)", multiple: true, required: false,
                description: "Blinks when the sensor reports open"
        }
        section("Lights") {
            input "blinkLights", "capability.switch", title: "Lights to blink", multiple: true, required: true
            input "blinkCount", "number", title: "Number of blinks", defaultValue: 3, range: "1..10", required: true, width: 6
            input "blinkIntervalMs", "number", title: "Time between blinks (ms)", defaultValue: 1500, range: "800..5000", required: true, width: 6
            paragraph "Hue lights blink through the bridge's alert and keep their on/off state. Others are toggled and put back."
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
    log.info "Garage Door Blink ready: doors ${garageDoors*.displayName ?: '-'}, sensors ${contactSensors*.displayName ?: '-'}, lights ${blinkLights*.displayName}"
}

def doorHandler(evt) {
    if (evt.value in ["opening", "open"]) trigger("${evt.displayName} ${evt.value}")
}

def contactHandler(evt) {
    trigger("${evt.displayName} open")
}

void trigger(String reason) {
    long t = now()
    long last = (state.lastBlink ?: 0L) as Long
    if (t - last < ((cooldownSec ?: 60) as Long) * 1000L) { logDebug "${reason}: in cooldown, skipped"; return }
    if (onlyModes && !(location.mode in onlyModes)) { logDebug "${reason}: mode ${location.mode} not allowed, skipped"; return }
    if (startTime && endTime && !timeOfDayIsBetween(toDateTime(startTime), toDateTime(endTime), new Date(), location.timeZone)) {
        logDebug "${reason}: outside time window, skipped"; return
    }
    if (state.blinking) { logDebug "${reason}: already blinking"; return }
    state.lastBlink = t
    state.blinking = true
    log.info "${reason} - blinking ${blinkLights*.displayName} ${blinkCount ?: 3}x"

    // remember the toggle-fallback lights' state so they can be put back exactly
    Map saved = [:]
    toggleLights().each { dev ->
        saved[dev.id.toString()] = [sw: dev.currentValue("switch"), level: dev.hasAttribute("level") ? dev.currentValue("level") : null]
    }
    state.saved = saved
    state.step = 0
    blinkStep()
}

/** One step: Hue lights get an alert on each blink; toggle lights flip on every step (two steps per blink). */
def blinkStep() {
    Integer count = (blinkCount ?: 3) as Integer
    Integer step = (state.step ?: 0) as Integer
    Integer interval = (blinkIntervalMs ?: 1500) as Integer
    List alertDevs = alertLights()
    List toggleDevs = toggleLights()

    if (step >= count * 2) { finish(); return }

    if (step % 2 == 0) alertDevs.each { it.flashOnce() }
    toggleDevs.each { dev ->
        Map s = (state.saved ?: [:])[dev.id.toString()] as Map
        boolean wasOn = s?.sw == "on"
        // first half of a blink goes to the opposite of the original state, second half back
        boolean wantOn = (step % 2 == 0) ? !wasOn : wasOn
        wantOn ? dev.on() : dev.off()
    }
    state.step = step + 1
    runInMillis((interval / 2) as Long, "blinkStep")
}

def finish() {
    toggleLights().each { dev ->
        Map s = (state.saved ?: [:])[dev.id.toString()] as Map
        if (!s) return
        if (s.sw == "on") {
            if (s.level != null && dev.hasCommand("setLevel")) dev.setLevel(s.level as Integer) else dev.on()
        } else dev.off()
    }
    state.blinking = false
    state.remove("saved")
    logDebug "blink done"
}

List alertLights() { (blinkLights ?: []).findAll { it.hasCommand("flashOnce") } }
List toggleLights() { (blinkLights ?: []).findAll { !it.hasCommand("flashOnce") } }

void logDebug(String msg) { if (debugLogging) log.debug msg }
