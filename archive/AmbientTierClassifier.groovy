/*
 * AmbientTierP1 – Outdoor-based ambient tier classifier
 *
 * Classifies ambient light into: Dark / Dim / Moderate / Bright
 * based on an outdoor illuminance sensor, with hysteresis, and
 * writes the current tier into a Hub Variable.
 *
 * Tested on C-7 using Hub Variables (getGlobalVar/setGlobalVar).
 */

definition(
    name:        "Ambient Tier Classifier",
    namespace:   "custom.ashwin",
    author:      "ChatGPT",
    description: "Classify ambient light into tiers and store the tier in a Hub Variable.",
    iconUrl:     "https://raw.githubusercontent.com/croscoded/hubitat-icons/main/sun-64.png",
    iconX2Url:   "https://raw.githubusercontent.com/croscoded/hubitat-icons/main/sun-128.png",
    iconX3Url:   "https://raw.githubusercontent.com/croscoded/hubitat-icons/main/sun-256.png"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Ambient Tier Classifier", uninstall: true, install: true) {
        section("Outdoor sensor") {
            input "outdoorSensor", "capability.illuminanceMeasurement",
                  title: "Outdoor illuminance sensor", required: true, multiple: false
        }

        section("Tier thresholds (lux at outdoor sensor)") {
            input "darkMax", "number",
                  title: "Dark / Dim boundary (max Dark)", defaultValue: 200, required: true
            input "dimMax", "number",
                  title: "Dim / Moderate boundary (max Dim)", defaultValue: 1000, required: true
            input "moderateMax", "number",
                  title: "Moderate / Bright boundary (max Moderate)", defaultValue: 7000, required: true
            input "hysteresisPct", "number",
                  title: "Hysteresis % (to avoid flapping)", defaultValue: 12, required: true
        }

        section("Outputs") {
            paragraph "Create this first under Settings → Hub Variables as a String."
            input "tierVarName", "text",
                  title: "Hub Variable name for tier (e.g., AmbientTierP1)",
                  required: true
            input "prevTierVarName", "text",
                  title: "Optional Hub Variable name for previous tier",
                  required: false
        }

        section("Scheduling & logging") {
            input "heartbeatMinutes", "number",
                  title: "Heartbeat (minutes, 0 = disable)", defaultValue: 10, required: true
            input "infoLogging", "bool",  title: "Enable info logging",  defaultValue: true
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

/* ==========================
 * Lifecycle
 * ========================== */

def installed() {
    infoLog "Installed."
    initialize()
}

def updated() {
    infoLog "Updated."
    initialize()
}

def initialize() {
    unschedule()
    unsubscribe()

    if (!outdoorSensor) {
        log.warn "[AmbientTierP1] No outdoor sensor configured; nothing to do."
        return
    }

    // Subscribe to illuminance events
    subscribe(outdoorSensor, "illuminance", "illuminanceHandler")

    // Heartbeat via cron (every N minutes)
    Integer hb = (heartbeatMinutes ?: 0) as Integer
    if (hb > 0) {
        String cron = "0 0/${hb} * * * ?"
        schedule(cron, "heartbeat")
        infoLog "Scheduling heartbeat every ${hb} minute(s)."
    } else {
        infoLog "Heartbeat disabled."
    }

    // Evaluate immediately with current lux
    Integer lux = safeLux(outdoorSensor.currentIlluminance)
    debugLog "Initial illuminance=${lux}."
    evaluateTier(lux)
}

/* ==========================
 * Event handlers
 * ========================== */

def illuminanceHandler(evt) {
    Integer lux = safeLux(evt.value)
    debugLog "Illuminance event: ${lux} lux."
    evaluateTier(lux)
}

def heartbeat() {
    Integer lux = safeLux(outdoorSensor.currentIlluminance)
    debugLog "Heartbeat check: ${lux} lux."
    evaluateTier(lux)
}

/* ==========================
 * Core logic
 * ========================== */

private Integer safeLux(val) {
    try {
        return (val as Integer)
    } catch (e) {
        log.warn "[AmbientTierP1] Non-numeric illuminance '${val}', treating as 0."
        return 0
    }
}

private void evaluateTier(Integer lux) {
    String oldTier = state.currentTier ?: "Unknown"
    String newTier = computeTier(lux, oldTier)

    if (newTier != oldTier) {
        infoLog "Tier change: ${oldTier} → ${newTier} (outdoor=${lux} lux)"
        state.previousTier = oldTier
        state.currentTier  = newTier
    } else {
        debugLog "Tier remains ${oldTier} (outdoor=${lux} lux)."
    }

    // Always push to Hub Variables so you can “force write” by just running evaluateTier
    if (tierVarName) {
        setHubStringVarByName(tierVarName, newTier)
    }
    if (prevTierVarName) {
        setHubStringVarByName(prevTierVarName, oldTier)
    }
}

private String computeTier(Integer lux, String oldTier) {
    // Raw thresholds
    Integer dMax = (darkMax ?: 200) as Integer
    Integer dimM = (dimMax ?: 1000) as Integer
    Integer modM = (moderateMax ?: 7000) as Integer
    Integer hPct = (hysteresisPct ?: 12) as Integer

    // Hysteresis margins
    BigDecimal h = (hPct / 100.0)
    Integer dUp      = Math.round(dMax * (1 + h))      // leave Dark
    Integer dDown    = Math.round(dMax * (1 - h))      // re-enter Dark
    Integer dimUp    = Math.round(dimM * (1 + h))
    Integer dimDown  = Math.round(dimM * (1 - h))
    Integer modUp    = Math.round(modM * (1 + h))
    Integer modDown  = Math.round(modM * (1 - h))

    switch (oldTier) {
        case "Dark":
            if (lux >= dUp) return "Dim"
            return "Dark"

        case "Dim":
            if (lux < dDown)   return "Dark"
            if (lux >= dimUp)  return "Moderate"
            return "Dim"

        case "Moderate":
            if (lux < dimDown) return "Dim"
            if (lux >= modUp)  return "Bright"
            return "Moderate"

        case "Bright":
            if (lux < modDown) return "Moderate"
            return "Bright"

        default:
            // First classification – no hysteresis yet
            if (lux <= dMax)   return "Dark"
            if (lux <= dimM)   return "Dim"
            if (lux <= modM)   return "Moderate"
            return "Bright"
    }
}

/* ==========================
 * Hub Variable helpers
 * ========================== */

/**
 * Set a String Hub Variable by name using Hubitat's
 * built-in getGlobalVar / setGlobalVar helpers.
 */
private void setHubStringVarByName(String name, String value) {
    try {
        def gv = getGlobalVar(name)   // returns [name:..., type:..., value:...]
        if (!gv) {
            log.warn "[AmbientTierP1] Hub Variable '${name}' not found."
            return
        }

        def current = gv.value
        if (current == value) {
            debugLog "Hub Variable '${name}' already '${value}', no update."
            return
        }

        debugLog "Setting Hub Variable '${name}' from '${current}' to '${value}'."
        setGlobalVar(name, value)
    } catch (e) {
        log.error "[AmbientTierP1] Error writing Hub Variable '${name}': ${e}"
    }
}

/* ==========================
 * Logging helpers
 * ========================== */

private void infoLog(msg)  { if (settings.infoLogging)  log.info  "[AmbientTierP1] ${msg}" }
private void debugLog(msg) { if (settings.debugLogging) log.debug "[AmbientTierP1] ${msg}" }
