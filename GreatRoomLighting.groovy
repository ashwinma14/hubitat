/**
 *  Great Room Lighting Controller
 *  
 *  A unified lighting automation for a great room (kitchen, dining, hallway, living room).
 *  Handles presence detection, ambient light levels, TV time, and manual overrides.
 *
 *  Author: Claude (for Ashwin)
 *  Date: 2026-02-12
 *  Version: 1.36 - mornings dim as gradually as dusks brighten: the ambient factor steps down one band at a time,
 *                  the hold-up only applies while the factor is rising, a mode change on a lit room fades over the
 *                  ambient fade, and the kitchen cans wait for a 75% factor instead of the dark-room floor
 *  Version: 1.35 - living-room PIR as a second opinion for zone dimming: the ceiling never dims while the PIR
 *                  has seen motion in the last few minutes, and PIR motion re-brightens a ceiling the mmWave dimmed
 *  Version: 1.34 - kitchen cans also come on whenever the room itself measured dark (not only once outdoor
 *                  lux is under the cans threshold), and they join at the end of the room's fade-in
 *  Version: 1.33 - second indoor lux sensor (kitchen Hue, true lux): the room counts as dark when either sensor
 *                  says so, because the bookcase ZSE40 caps at 100% and then goes silent for hours; the dark-room
 *                  factor floor now applies to every daytime turn-on; dusk debounce threshold 1000 lux
 *  Version: 1.32 - dusk tuning from the first live evening: a dark-room turn-on starts at 55% (not 35%),
 *                  main lights never dip on the Evening/Night mode change while dusk is still ramping,
 *                  dining steps 50/75/100 with the factor, shorter dark-room debounce once outdoor lux is under 800
 *  Version: 1.31 - adaptive brightness: scene levels scale with outdoor lux, fades on every change,
 *                  sunrise-relative predawn with a morning ramp, kitchen cans only when dark, daytime debounce
 *  Version: 1.30 - Athom staleness guard with dead-sensor alerts, evening wind-down scene
 */

definition(
    name: "Great Room Lighting Controller",
    namespace: "jungalow",
    author: "Claude",
    description: "Unified lighting automation for the great room based on presence, mode, and TV time",
    category: "Lighting",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def appVersion() { return "1.36" }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Great Room Lighting Controller", install: true, uninstall: true) {
        
        section("<b>Devices</b>") {
            input "motionZone", "capability.motionSensor", title: "Motion Zone", required: true
            input "livingRoomPresence", "capability.motionSensor", title: "Living Room Presence Sensor (Athom - for zone dimming)", required: false
            input "lrMotionSensor", "capability.motionSensor", title: "Living room motion sensor (PIR second opinion: the ceiling never dims while it has seen motion recently, and its motion re-brightens a dimmed ceiling)", required: false
            input "kitchenPresence", "capability.motionSensor", title: "Kitchen Presence Sensor (FP1E - for true presence)", required: false
            input "indoorLuxSensor", "capability.illuminanceMeasurement", title: "Indoor Lux Sensor (for dark room detection)", required: false
            input "indoorLuxSensor2", "capability.illuminanceMeasurement", title: "Second indoor lux sensor (true-lux sensor such as the kitchen Hue motion sensor; the room counts as dark when EITHER sensor says so)", required: false
            input "outdoorLuxSensor", "capability.illuminanceMeasurement", title: "Outdoor Lux Sensor (for cloudy day check)", required: false
            input "weatherDevice", "capability.relativeHumidityMeasurement", title: "Weather Device (for cloud cover)", required: false, description: "OpenWeatherMap device with cloudiness attribute"
            input "luxSensor", "capability.illuminanceMeasurement", title: "Lux Sensor (for logging only)", required: false
            input "diningSwitch", "capability.switch", title: "Dining Switch", required: true
            input "hallwaySwitch", "capability.switchLevel", title: "Hallway Switch", required: true
            input "kitchenCans", "capability.switch", title: "Kitchen Cans", required: true
            input "kitchenPendant", "capability.switchLevel", title: "Kitchen Pendant", required: true
            input "bookcaseGOLamp", "capability.switchLevel", title: "Bookcase GO Lamp", required: true
            input "bookcaseColorLamp", "capability.switchLevel", title: "Bookcase Color Lamp", required: true
            input "lrHueLights", "capability.switchLevel", title: "LR Hue Lights", required: true
            input "activatorSwitch", "capability.switch", title: "Activator Switch", required: false
        }
        
        section("<b>Mode Settings</b>") {
            paragraph "Lights turn ON when mode is Night or Evening, OR when indoor lux is below threshold, OR when it's cloudy."
            paragraph "Configure your Mode Manager to change modes based on outdoor lux sensor thresholds."
            input "indoorLuxThreshold", "number", title: "Indoor lux threshold (turn on lights if below)", defaultValue: 15, required: true
            input "indoorLuxThreshold2", "number", title: "Second indoor sensor: turn on lights if below (lux)", defaultValue: 40, required: true
            input "cloudyThreshold", "number", title: "Cloud cover % threshold (turn on if above)", defaultValue: 70, required: true
            input "cloudyLuxThreshold", "number", title: "Outdoor lux threshold for cloudy check", defaultValue: 400, required: true
            input "presenceTimeout", "number", title: "Minutes before presence times out", defaultValue: 8, required: true
            input "lightsOffDelay", "number", title: "Seconds delay before turning lights off", defaultValue: 10, required: true
            input "lrDimDelay", "number", title: "Seconds before LR ceiling dims after leaving the room", defaultValue: 60, required: true
            input "lrBrightHoldMinutes", "number", title: "Minimum minutes LR ceiling stays bright after brightening (anti-flicker)", defaultValue: 3, required: true
            input "lrDimVetoMinutes", "number", title: "Minutes since the LR motion sensor last saw motion before the ceiling may dim", defaultValue: 3, required: true
        }
        
        section("<b>TV Time</b>") {
            input "tvTimeSwitch", "capability.switch", title: "TV Time Switch", required: false, description: "Virtual switch to trigger TV Time mode"
        }
        
        section("<b>Scene Settings - Day Mode</b>") {
            input "dayHallwayLevel", "number", title: "Hallway brightness (0-100)", defaultValue: 99
            input "dayKitchenPendantLevel", "number", title: "Kitchen Pendant brightness", defaultValue: 100
            input "dayBookcaseLevel", "number", title: "Bookcase lamps brightness", defaultValue: 50
            input "dayHueLevel", "number", title: "LR Hue lights brightness", defaultValue: 100
        }
        
        section("<b>Scene Settings - Night Mode</b>") {
            input "nightHallwayLevel", "number", title: "Hallway brightness (0-100)", defaultValue: 80
            input "nightKitchenPendantLevel", "number", title: "Kitchen Pendant brightness", defaultValue: 30
            input "nightBookcaseLevel", "number", title: "Bookcase lamps brightness", defaultValue: 15
            input "nightHueLevel", "number", title: "LR Hue lights brightness", defaultValue: 80
        }
        
        section("<b>Scene Settings - Predawn (gentle wake)</b>") {
            input "predawnEnd", "text", title: "Predawn ends at (HH:mm, 24h)", defaultValue: "06:15", required: true
            input "predawnHallwayLevel", "number", title: "Hallway brightness", defaultValue: 20
            input "predawnKitchenPendantLevel", "number", title: "Kitchen Pendant brightness", defaultValue: 10
            input "predawnBookcaseLevel", "number", title: "Bookcase lamps brightness", defaultValue: 10
            input "predawnHueLevel", "number", title: "LR Hue lights brightness", defaultValue: 30
        }

        section("<b>Scene Settings - Wind-down (evening)</b>") {
            input "windDownStart", "text", title: "Wind-down starts at (HH:mm, 24h)", defaultValue: "21:30", required: true
            input "windDownDiningLevel", "number", title: "Dining brightness", defaultValue: 30
            input "windDownHallwayLevel", "number", title: "Hallway brightness", defaultValue: 40
            input "windDownKitchenPendantLevel", "number", title: "Kitchen Pendant brightness", defaultValue: 15
            input "windDownBookcaseLevel", "number", title: "Bookcase lamps brightness", defaultValue: 10
            input "windDownHueLevel", "number", title: "LR Hue lights brightness", defaultValue: 50
        }

        section("<b>Scene Settings - TV Time</b>") {
            input "tvBookcaseLevel", "number", title: "Bookcase lamps brightness", defaultValue: 15
            input "tvOtherLightsOff", "bool", title: "Turn off other lights?", defaultValue: true
        }
        
        section("<b>Adaptive Brightness</b>") {
            paragraph "Scene levels are scaled by outdoor light so the room comes up gradually at dusk and in the morning: 35% of the scene levels while it is still bright outside, then 55%, 75% and 100% as it gets darker. Every change fades."
            input "adaptiveEnabled", "bool", title: "Enable adaptive brightness", defaultValue: true, submitOnChange: true
            input "adaptiveLux55", "number", title: "Outdoor lux below which levels run at 55%", defaultValue: 400, required: true, width: 4
            input "adaptiveLux75", "number", title: "Outdoor lux below which levels run at 75%", defaultValue: 150, required: true, width: 4
            input "adaptiveLux100", "number", title: "Outdoor lux below which levels run at 100%", defaultValue: 50, required: true, width: 4
            input "cansFactor", "number", title: "Kitchen cans (on/off only) join once the factor reaches this percent", defaultValue: 75, required: true, width: 6
            input "ambientStepMinutes", "number", title: "Minimum minutes between ambient steps", defaultValue: 10, required: true, width: 6
            input "turnOnFadeSeconds", "number", title: "Fade-in when the room turns on (seconds)", defaultValue: 60, required: true, width: 4
            input "ambientFadeSeconds", "number", title: "Fade for ambient steps (seconds)", defaultValue: 45, required: true, width: 4
            input "sceneFadeSeconds", "number", title: "Fade for scene changes (seconds)", defaultValue: 3, required: true, width: 4
            input "predawnSunriseOffset", "number", title: "Predawn lasts until this many minutes before sunrise (or the predawn end time above, whichever is later)", defaultValue: 20, required: true, width: 6
            input "morningRampMinutes", "number", title: "Minutes to ramp from predawn levels to full once predawn ends", defaultValue: 30, required: true, width: 6
            input "daytimeOnDebounceMinutes", "number", title: "Daytime with bright outdoors: the dark-room condition must hold this many minutes before the lights turn on", defaultValue: 10, required: true, width: 4
            input "duskLuxThreshold", "number", title: "...but once outdoor lux is below this, use the shorter dusk debounce", defaultValue: 1000, required: true, width: 4
            input "duskDebounceMinutes", "number", title: "Dusk debounce (minutes)", defaultValue: 3, required: true, width: 4
            input "darkRoomMinFactor", "number", title: "When the room turned on because it measured dark, never run below this factor (%) until it turns off", defaultValue: 55, required: true
            paragraph "Dining runs 50% / 75% / 100% as the factor reaches 55 / 75 / 100. While the factor is rising (dusk, morning ramp) the living room, hallway and dining never step down; when it is falling (a brightening morning) it steps down one band at a time, at most one step per the interval above."
            paragraph adaptiveStatus()
        }

        section("<b>Sensor Health</b>") {
            input "athomStaleMinutes", "number", title: "Treat Athom as dead after no events for (minutes)", defaultValue: 30, required: true
            input "notifyDevices", "capability.notification", title: "Notify these devices on sensor failure/recovery", multiple: true, required: false
        }

        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
            input "logEnableMinutes", "number", title: "Disable debug logging after (minutes, 0=never)", defaultValue: 30
            input "webhookUrl", "text", title: "Google Sheets Webhook URL (optional)", required: false
        }
        
        section("<b>App Control</b>") {
            input "appPaused", "bool", title: "Pause automation", defaultValue: false, description: "When paused, app will not control lights"
        }
    }
}



// ==================== LIFECYCLE ====================

def installed() {
    log.info "Great Room Lighting Controller installed"
    initialize()
}

def updated() {
    log.info "Great Room Lighting Controller updated"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    // Validate required devices
    if (!motionZone) {
        log.error "Motion zone not configured"
        return
    }
    
    log.info "Devices configured:"
    log.info "  Motion Zone: ${motionZone?.displayName}"
    log.info "  Living Room Presence: ${livingRoomPresence?.displayName ?: 'not configured'}"
    log.info "  Living Room Motion (PIR): ${lrMotionSensor?.displayName ?: 'not configured'}"
    log.info "  Kitchen Presence: ${kitchenPresence?.displayName ?: 'not configured'}"
    log.info "  Indoor Lux Sensor: ${indoorLuxSensor?.displayName ?: 'not configured'}"
    log.info "  Indoor Lux Sensor 2: ${indoorLuxSensor2?.displayName ?: 'not configured'}"
    log.info "  Outdoor Lux Sensor: ${outdoorLuxSensor?.displayName ?: 'not configured'}"
    log.info "  Weather Device: ${weatherDevice?.displayName ?: 'not configured'}"
    log.info "  Lux Sensor (logging): ${luxSensor?.displayName ?: 'not configured'}"
    log.info "  Dining: ${diningSwitch?.displayName}"
    log.info "  Hallway: ${hallwaySwitch?.displayName}"
    log.info "  Kitchen Cans: ${kitchenCans?.displayName}"
    log.info "  Kitchen Pendant: ${kitchenPendant?.displayName}"
    log.info "  Bookcase GO: ${bookcaseGOLamp?.displayName}"
    log.info "  Bookcase Color: ${bookcaseColorLamp?.displayName}"
    log.info "  LR Hue: ${lrHueLights?.displayName}"
    log.info "  Activator: ${activatorSwitch?.displayName}"
    log.info "  TV Time Switch: ${tvTimeSwitch?.displayName}"
    
    // Subscribe to motion zone
    subscribe(motionZone, "motion", motionHandler)
    
    // Subscribe to living room presence for zone-based dimming
    if (livingRoomPresence) {
        subscribe(livingRoomPresence, "mmwave", livingRoomPresenceHandler)
        // Also subscribe to mmwave for true presence detection
        subscribe(livingRoomPresence, "mmwave", truePresenceHandler)
    }
    
    // Living-room PIR: second opinion for the zone dim
    if (lrMotionSensor) {
        subscribe(lrMotionSensor, "motion.active", lrMotionHandler)
        if (lrMotionSensor.currentMotion == "active") state.lastLrMotionAt = now()
    }

    // Subscribe to kitchen presence for true presence detection
    if (kitchenPresence) {
        subscribe(kitchenPresence, "roomState", truePresenceHandler)
    }
    
    // Subscribe to indoor lux sensor for dark room detection
    if (indoorLuxSensor) {
        subscribe(indoorLuxSensor, "illuminance", indoorLuxHandler)
    }
    if (indoorLuxSensor2) {
        subscribe(indoorLuxSensor2, "illuminance", indoorLuxHandler)
    }
    
    // Subscribe to weather device for cloud cover changes
    if (weatherDevice) {
        subscribe(weatherDevice, "cloudiness", cloudHandler)
    }
    
    // Subscribe to outdoor lux for ambient-based dining level
    if (outdoorLuxSensor) {
        subscribe(outdoorLuxSensor, "illuminance", outdoorLuxHandler)
    }
    
    // Subscribe to illuminance for logging only
    if (luxSensor) {
        subscribe(luxSensor, "illuminance", luxHandler)
    }
    
    // Subscribe to TV Time switch
    if (tvTimeSwitch) {
        subscribe(tvTimeSwitch, "switch", tvTimeSwitchHandler)
    }
    
    // Subscribe to mode changes - this now drives lightNeeded
    subscribe(location, "mode", modeHandler)
    
    // Subscribe to manual switch changes for override detection
    if (diningSwitch) subscribe(diningSwitch, "switch", manualSwitchHandler)
    if (hallwaySwitch) subscribe(hallwaySwitch, "switch", manualSwitchHandler)
    if (kitchenCans) subscribe(kitchenCans, "switch", manualSwitchHandler)
    if (kitchenPendant) subscribe(kitchenPendant, "switch", manualSwitchHandler)
    if (bookcaseGOLamp) subscribe(bookcaseGOLamp, "switch", manualSwitchHandler)
    if (bookcaseColorLamp) subscribe(bookcaseColorLamp, "switch", manualSwitchHandler)
    if (lrHueLights) subscribe(lrHueLights, "switch", manualSwitchHandler)
    
    // Initialize state
    state.presenceActive = (motionZone.currentMotion == "active")
    state.manualOverride = false
    state.tvTimeActive = false
    state.lastAutomationAction = now()
    state.pendingPresenceOff = false
    state.pendingBrightOff = false
    state.turningOff = false
    state.lastLrBrightenTime = 0
    
    // Set lightNeeded based on current mode and indoor lux
    state.lightNeeded = isLightNeeded()
    
    // Schedule 3am TV Time auto-reset
    schedule("0 0 3 * * ?", resetTvTime)

    // Predawn ends at the later of the configured time and (sunrise - offset); re-armed just after midnight
    schedule("0 1 0 * * ?", armPredawnEnd)
    armPredawnEnd()

    // Adaptive brightness bookkeeping
    state.ambientFactor = rawAmbientFactor()
    state.ambientChangedAt = now()
    state.morningRamp = null
    state.pendingDarkOn = false
    state.currentScene = state.currentScene ?: "off"
    runEvery10Minutes(ambientRecheck)

    // Re-evaluate lighting when the wind-down window opens
    def wdParts = (windDownStart ?: "21:30").split(":")
    schedule("0 ${wdParts[1] as Integer} ${wdParts[0] as Integer} * * ?", windDownStarted)

    // Watch the Athom for silent failures
    if (livingRoomPresence) {
        state.athomStaleAlerted = false
        runEvery15Minutes(checkSensorHealth)
    }

    // Auto-disable logging after specified time
    if (logEnable && logEnableMinutes && logEnableMinutes > 0) {
        runIn(logEnableMinutes * 60, disableLogging)
    }
    
    logDebug "Initialized. Presence: ${state.presenceActive}, Mode: ${location.mode}, lightNeeded: ${state.lightNeeded}"
    
    // Delay initial evaluation slightly
    runIn(5, delayedInitialEvaluation)
}

def delayedInitialEvaluation() {
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    log.info "Initialization complete. State: presence=${state.presenceActive}, lightNeeded=${state.lightNeeded}, mode=${location.mode}, paused=${appPaused}"
    
    // Log initialization to Google Sheets
    safeLogToSheet("init", "complete", "App started v${appVersion()}", currentLux)
    
    // Check if paused
    if (appPaused) {
        log.info "App is paused - not taking any action"
        return
    }
    
    // If conditions say lights should be ON, turn them on
    if (state.presenceActive && state.lightNeeded && !state.manualOverride && !state.tvTimeActive) {
        log.info "Conditions warrant lights on - applying scene"
        if (location.mode == "Night") {
            if (isPredawn()) { applyPredawnScene() } else if (isWindDown()) { applyWindDownScene() } else { applyNightScene() }
        } else {
            applyDayScene()
        }
    } else {
        log.info "Waiting for events (motion, mode changes, etc.) to take action..."
    }
}

def disableLogging() {
    log.info "Debug logging auto-disabled after ${logEnableMinutes} minutes"
    app.updateSetting("logEnable", false)
}

// ==================== LIGHT NEEDED LOGIC ====================

def isLightNeeded() {
    // Light is needed if:
    // 1. Mode is Night or Evening (outdoor sensor triggered dusk), OR
    // 2. Indoor lux is below threshold (cloudy day, dark room), OR
    // 3. Cloud cover is high AND outdoor lux is below cloudy threshold
    
    def modeNeedsLight = (location.mode == "Night" || location.mode == "Evening")
    
    // Indoor lux check with hysteresis to prevent feedback loop: each sensor turns ON below its threshold and
    // counts as still-dark below threshold + 30 while the lights are on (the lights add ~20 to the reading).
    // Two sensors vote; the room is dark when EITHER says so (the bookcase ZSE40 caps at 100% and can sit there silent for hours).
    def lightsCurrentlyOn = (diningSwitch?.currentSwitch == "on" || kitchenCans?.currentSwitch == "on")
    boolean indoorNeedsLight = sensorDark(indoorLuxSensor, indoorLuxThreshold ?: 15, lightsCurrentlyOn) ||
                               sensorDark(indoorLuxSensor2, indoorLuxThreshold2 ?: 40, lightsCurrentlyOn)
    
    // Cloud cover check - if cloudy and not bright enough outside
    // Uses hysteresis: lights ON when outdoor lux drops below cloudyLuxThreshold (400)
    // lights OFF when outdoor lux rises above cloudyLuxThreshold + 300 (700)
    def cloudiness = weatherDevice?.currentValue("cloudiness") as Integer ?: 0
    def cloudThreshold = cloudyThreshold ?: 70
    def luxThreshold = cloudyLuxThreshold ?: 400
    def luxOffThreshold = luxThreshold + 300  // Hysteresis for outdoor lux
    def outdoorLux = outdoorLuxSensor?.currentIlluminance ?: 1000  // Default high so it won't trigger without sensor
    
    def cloudyNeedsLight = false
    if (cloudiness >= cloudThreshold) {
        if (lightsCurrentlyOn) {
            // Lights are on - only turn off if outdoor lux rises well above threshold
            cloudyNeedsLight = (outdoorLux < luxOffThreshold)
        } else {
            // Lights are off - turn on if outdoor lux drops below threshold
            cloudyNeedsLight = (outdoorLux < luxThreshold)
        }
    }
    
    logDebug "isLightNeeded: mode=${location.mode} (${modeNeedsLight}), indoor ${indoorReadings()} lightsOn=${lightsCurrentlyOn} (${indoorNeedsLight}), clouds=${cloudiness}%/${cloudThreshold}% outdoorLux=${outdoorLux}/${luxThreshold}/${luxOffThreshold} (${cloudyNeedsLight})"
    
    return modeNeedsLight || indoorNeedsLight || cloudyNeedsLight
}

/** One indoor sensor's vote: below its on-threshold with the lights off; below on-threshold + 30 with them on. */
boolean sensorDark(dev, Number onThreshold, boolean lightsOn) {
    def lux = dev?.currentIlluminance
    if (lux == null) return false
    BigDecimal on = (onThreshold ?: 0) as BigDecimal
    return (lux as BigDecimal) < (lightsOn ? on + 30 : on)
}

/** True when either indoor sensor reads below its turn-on threshold right now (no hysteresis). */
boolean roomMeasuredDark() {
    return sensorDark(indoorLuxSensor, indoorLuxThreshold ?: 15, false) || sensorDark(indoorLuxSensor2, indoorLuxThreshold2 ?: 40, false)
}

String indoorReadings() {
    String a = indoorLuxSensor ? "${indoorLuxSensor.displayName} ${indoorLuxSensor.currentIlluminance}/${indoorLuxThreshold ?: 15}" : "no sensor"
    String b = indoorLuxSensor2 ? ", ${indoorLuxSensor2.displayName} ${indoorLuxSensor2.currentIlluminance}/${indoorLuxThreshold2 ?: 40}" : ""
    return a + b
}

def isLightNeededForMode(String mode) {
    // Legacy method - just checks mode
    return (mode == "Night" || mode == "Evening")
}

def getDiningLevelForAmbient() {
    // Simple two-level dimming for Edison bulbs (poor dimming granularity)
    // Dark = full brightness, some daylight = dimmed
    def outdoorLux = outdoorLuxSensor?.currentIlluminance ?: 0
    
    def level = (outdoorLux < 100) ? 100 : 50
    
    logDebug "Dining ambient level: outdoorLux=${outdoorLux} -> ${level}%"
    return level
}

def isPredawn() {
    // True before today's predawn end (configured time, or sunrise minus the offset when adaptive brightness is on), hub-local time
    def cal = java.util.Calendar.getInstance(location.timeZone)
    def nowMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    return nowMins < predawnEndMinutes()
}

Integer predawnEndMinutes() {
    def parts = (predawnEnd ?: "06:15").split(":")
    Integer configured = (parts[0] as Integer) * 60 + (parts[1] as Integer)
    if (adaptiveEnabled == false) return configured
    try {
        def cal = java.util.Calendar.getInstance(location.timeZone)
        cal.setTime(location.sunrise)
        Integer sunriseMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return Math.max(configured, sunriseMins - ((predawnSunriseOffset ?: 20) as Integer))
    } catch (e) {
        logDebug "Sunrise lookup failed (${e.message}); using configured predawn end"
        return configured
    }
}

def armPredawnEnd() {
    unschedule("predawnEnded")
    Integer endMins = predawnEndMinutes()
    def cal = java.util.Calendar.getInstance(location.timeZone)
    Integer nowMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    if (nowMins < endMins) {
        def t = java.util.Calendar.getInstance(location.timeZone)
        t.set(java.util.Calendar.HOUR_OF_DAY, (endMins / 60) as Integer)
        t.set(java.util.Calendar.MINUTE, endMins % 60)
        t.set(java.util.Calendar.SECOND, 0)
        runOnce(t.getTime(), "predawnEnded")
        logDebug "Predawn ends today at ${String.format('%02d:%02d', (endMins / 60) as Integer, endMins % 60)}"
    }
}

def predawnEnded() {
    // Predawn window just closed - come up gradually (morning ramp) rather than jumping to the full scene
    if (adaptiveEnabled != false) {
        state.morningRamp = [start: now(), minutes: (morningRampMinutes ?: 30) as Integer]
        log.info "Predawn ended - ramping up over ${morningRampMinutes ?: 30} minutes"
        runIn(180, "morningRampStep")
    }
    if (state.presenceActive && !state.manualOverride && !state.tvTimeActive) {
        evaluateLighting("predawn window ended")
    }
}

def morningRampStep() {
    if (!state.morningRamp) return
    Integer rf = rampFactor()
    if (rf >= 100) {
        state.morningRamp = null
        log.info "Morning ramp complete"
    } else {
        runIn(180, "morningRampStep")
    }
    if (state.presenceActive && state.lightNeeded && !state.manualOverride && !state.tvTimeActive && state.currentScene in ["day", "night"]) {
        reapplyCurrentScene("morning ramp ${rf}%", (ambientFadeSeconds ?: 45) as Integer)
    }
}

def isWindDown() {
    // True at/after the configured wind-down start time (default 21:30), hub-local time
    def parts = (windDownStart ?: "21:30").split(":")
    def startMins = (parts[0] as Integer) * 60 + (parts[1] as Integer)
    def cal = java.util.Calendar.getInstance(location.timeZone)
    def nowMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    return nowMins >= startMins
}

def windDownStarted() {
    // Wind-down window just opened - ease the lights down if someone is around
    if (state.presenceActive && !state.manualOverride && !state.tvTimeActive) {
        evaluateLighting("wind-down started")
    }
}

def isAthomStale() {
    // True when the Athom has not sent ANY event recently - its readings can't be trusted.
    // The sensor floods illuminance events when alive, so silence means it's dead/throttled.
    if (!livingRoomPresence) return false
    try {
        def last = livingRoomPresence.getLastActivity()
        if (last == null) return true
        def staleMs = (athomStaleMinutes ?: 30) * 60 * 1000
        return (now() - last.time) > staleMs
    } catch (e) {
        logDebug "isAthomStale check failed: ${e.message}"
        return false
    }
}

def checkSensorHealth() {
    if (!livingRoomPresence) return
    def stale = isAthomStale()
    if (stale && !state.athomStaleAlerted) {
        state.athomStaleAlerted = true
        def mins = athomStaleMinutes ?: 30
        log.warn "Athom presence sensor is stale - no events for over ${mins} minutes. Power cycle it, then click Initialize on the device."
        notifyAll("Great Room: Athom presence sensor looks dead (no events for ${mins}+ min). Power cycle it, then click Initialize on device 364.")
        safeLogToSheet("health", "stale", "Athom silent ${mins}+ min", 0)
    } else if (!stale && state.athomStaleAlerted) {
        state.athomStaleAlerted = false
        log.info "Athom presence sensor recovered - events flowing again"
        notifyAll("Great Room: Athom presence sensor is reporting again.")
        safeLogToSheet("health", "recovered", "Athom reporting again", 0)
    }
}

def notifyAll(String msg) {
    try {
        notifyDevices?.each { it.deviceNotification(msg) }
    } catch (e) {
        log.warn "Notification failed: ${e.message}"
    }
}

def isTruePresenceDetected() {
    // Check mmWave/radar presence attributes (not just PIR motion)
    // These detect sitting still, unlike motion sensors
    
    def athomPresent = false
    def fp1ePresent = false
    
    // Check Athom mmwave attribute (ignored when the sensor has gone silent)
    if (livingRoomPresence) {
        if (isAthomStale()) {
            logDebug "Athom stale - ignoring its mmwave reading"
        } else {
            def mmwave = livingRoomPresence.currentValue("mmwave")
            athomPresent = (mmwave == "active")
            logDebug "Athom mmwave: ${mmwave} -> ${athomPresent ? 'present' : 'not present'}"
        }
    }
    
    // Check FP1E roomState attribute
    if (kitchenPresence) {
        def roomState = kitchenPresence.currentValue("roomState")
        fp1ePresent = (roomState == "occupied")
        logDebug "FP1E roomState: ${roomState} -> ${fp1ePresent ? 'present' : 'not present'}"
    }
    
    def result = athomPresent || fp1ePresent
    logDebug "True presence detected: ${result} (athom=${athomPresent}, fp1e=${fp1ePresent})"
    return result
}

// ==================== EVENT HANDLERS ====================

def motionHandler(evt) {
    logDebug "Motion event: ${evt.device.displayName} = ${evt.value}"
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    // Log motion event to Google Sheets (wrapped to prevent crashes)
    safeLogToSheet("motion", evt.value, evt.device.displayName, currentLux)
    
    if (evt.value == "active") {
        // Cancel any pending presence timeout
        unschedule(presenceTimedOut)
        state.pendingPresenceOff = false
        
        def wasAlreadyPresent = state.presenceActive
        state.presenceActive = true
        
        if (!wasAlreadyPresent) {
            logDebug "Presence detected - someone is here"
        }
        
        // Refresh indoor lux sensor on EVERY motion active event (not just first)
        // This catches gradual darkening while someone is already present
        if (indoorLuxSensor?.hasCommand("refresh")) {
            logDebug "Refreshing indoor lux sensor..."
            indoorLuxSensor.refresh()
            // Wait 2 seconds for sensor to update, then evaluate
            runIn(2, evaluateLightingAfterRefresh)
        } else if (!wasAlreadyPresent) {
            // Only evaluate on first presence if no indoor sensor
            evaluateLighting("presence activated")
        }
    } else {
        // Motion zone went inactive - start timeout
        if (state.presenceActive && !state.pendingPresenceOff) {
            state.pendingPresenceOff = true
            def timeoutSeconds = (presenceTimeout ?: 8) * 60
            logDebug "Motion zone inactive - starting ${presenceTimeout} minute timeout"
            safeLogToSheet("timeout", "started", "${presenceTimeout} minutes", currentLux)
            runIn(timeoutSeconds, presenceTimedOut)
        }
    }
}

def evaluateLightingAfterRefresh() {
    def indoorLux = indoorLuxSensor?.currentIlluminance ?: 999
    logDebug "Indoor lux after refresh: ${indoorLux}"
    
    // Only evaluate if lights might need to change
    def lightsCurrentlyOn = (diningSwitch?.currentSwitch == "on" || kitchenCans?.currentSwitch == "on")
    def lightNeeded = isLightNeeded()
    
    if (!lightsCurrentlyOn && lightNeeded && !state.manualOverride && !state.tvTimeActive) {
        // Lights are off but should be on
        evaluateLighting("motion + indoor lux ${indoorLux}")
    } else if (lightsCurrentlyOn && !lightNeeded && !state.manualOverride && !state.tvTimeActive) {
        // Lights are on but shouldn't be (unlikely but handle it)
        evaluateLighting("motion + indoor lux ${indoorLux} (too bright)")
    } else {
        logDebug "No lighting change needed (lights=${lightsCurrentlyOn ? 'on' : 'off'}, needed=${lightNeeded})"
    }
}

def presenceTimedOut() {
    logDebug "Presence timeout reached - checking true presence"
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    // Check if someone is still present via mmWave/radar
    if (isTruePresenceDetected()) {
        logDebug "True presence still detected - keeping lights on"
        safeLogToSheet("timeout", "extended", "True presence detected", currentLux)
        state.pendingPresenceOff = false
        // Re-check in 2 minutes
        runIn(120, presenceTimedOut)
        return
    }
    
    safeLogToSheet("timeout", "completed", "Presence cleared", currentLux)
    
    state.presenceActive = false
    state.pendingPresenceOff = false
    state.manualOverride = false  // Clear override when everyone leaves
    evaluateLighting("presence timeout")
}

def truePresenceHandler(evt) {
    logDebug "True presence event: ${evt.device.displayName} ${evt.name} = ${evt.value}"
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    // If presence detected and we have a pending timeout, cancel it
    if ((evt.value == "active" || evt.value == "occupied") && state.pendingPresenceOff) {
        logDebug "True presence detected - canceling pending timeout"
        unschedule(presenceTimedOut)
        state.pendingPresenceOff = false
        safeLogToSheet("presence", "true presence", evt.device.displayName, currentLux)
    }
}

def luxHandler(evt) {
    // Lux sensor is now just for logging, not for triggering light changes
    def lux = evt.value.toInteger()
    
    logDebug "Illuminance changed: ${lux} lux (logging only)"
    
    // Log lux changes to Google Sheets (only significant changes)
    def lastLoggedLux = state.lastLoggedLux ?: 0
    if (Math.abs(lux - lastLoggedLux) >= 10) {
        safeLogToSheet("lux", lux.toString(), "mode: ${location.mode}", lux)
        state.lastLoggedLux = lux
    }
}

def indoorLuxHandler(evt) {
    def lux = (evt.value as BigDecimal).intValue()
    boolean second = indoorLuxSensor2 && ("${evt.deviceId}" == "${indoorLuxSensor2.id}")
    def threshold = second ? (indoorLuxThreshold2 ?: 40) : (indoorLuxThreshold ?: 15)
    String who = evt.displayName
    
    logDebug "Indoor lux changed: ${who} ${lux} (threshold: ${threshold})"
    
    // If presence is active, check if lighting needs to change
    if (state.presenceActive && !state.manualOverride && !state.tvTimeActive) {
        def wasLightNeeded = state.lightNeeded
        state.lightNeeded = isLightNeeded()
        
        if (wasLightNeeded != state.lightNeeded) {
            if (state.lightNeeded) {
                // Got darker - turn on immediately, cancel any pending off
                unschedule(turnOffDueToBright)
                state.pendingBrightOff = false
                if (daytimeDebounceApplies()) {
                    // Bright outside, dim inside: make the condition hold before lighting the whole room in daylight.
                    // A passing cloud at midday gets the long debounce; real dusk (outdoor already under duskLuxThreshold) the short one.
                    Integer mins = darkRoomDebounceMinutes()
                    if (!state.pendingDarkOn) {
                        state.pendingDarkOn = true
                        state.pendingDarkMins = mins
                        log.info "${who} ${lux} below ${threshold} in daylight (outdoor ${outdoorLuxSensor?.currentIlluminance} lux) - confirming for ${mins} minutes before turning on"
                        runIn(mins * 60, "confirmDarkRoom")
                    }
                } else {
                    log.info "${who} ${lux} below ${threshold} - turning on lights"
                    safeLogToSheet("indoorLux", lux.toString(), "below threshold, turning on", lux)
                    evaluateLighting("indoor lux changed to ${lux}")
                }
            } else {
                // Got brighter - cancel a pending daytime turn-on, delay 10 minutes before turning off
                if (state.pendingDarkOn) {
                    unschedule("confirmDarkRoom")
                    state.pendingDarkOn = false
                    log.info "Indoor readings back above threshold (${indoorReadings()}) - daytime turn-on cancelled"
                }
                if (!state.pendingBrightOff) {
                    log.info "Indoor readings rose above threshold (${indoorReadings()}) - will turn off in 10 minutes if still bright"
                    safeLogToSheet("indoorLux", lux.toString(), "above threshold, scheduling off", lux)
                    state.pendingBrightOff = true
                    runIn(600, turnOffDueToBright)  // 10 minutes
                }
            }
        } else if (state.pendingBrightOff && state.lightNeeded) {
            // Lux dropped back below threshold while waiting - cancel pending off
            log.info "Indoor lux dropped below threshold - canceling pending off"
            unschedule(turnOffDueToBright)
            state.pendingBrightOff = false
        }
    }
}

def turnOffDueToBright() {
    state.pendingBrightOff = false
    def lux = indoorLuxSensor?.currentIlluminance ?: 0
    
    // Verify still bright before turning off (both sensors, same hysteresis as the turn-on decision)
    state.lightNeeded = isLightNeeded()
    if (!state.lightNeeded && state.presenceActive && !state.manualOverride && !state.tvTimeActive) {
        log.info "Still bright after 10 minutes (${indoorReadings()}) - turning off lights"
        safeLogToSheet("indoorLux", lux.toString(), "still bright, turning off", lux)
        evaluateLighting("bright for 10+ minutes")
    } else {
        log.info "Conditions changed - not turning off (${indoorReadings()})"
    }
}

def cloudHandler(evt) {
    def cloudiness = evt.value.toInteger()
    def threshold = cloudyThreshold ?: 70
    
    logDebug "Cloud cover changed: ${cloudiness}% (threshold: ${threshold}%)"
    
    // If presence is active, check if lighting needs to change
    if (state.presenceActive && !state.manualOverride && !state.tvTimeActive) {
        def wasLightNeeded = state.lightNeeded
        state.lightNeeded = isLightNeeded()
        
        if (wasLightNeeded != state.lightNeeded) {
            if (state.lightNeeded) {
                log.info "Cloud cover increased to ${cloudiness}% - turning on lights"
                safeLogToSheet("cloud", cloudiness.toString(), "above threshold, turning on", cloudiness)
            } else {
                log.info "Cloud cover decreased to ${cloudiness}% - turning off lights"
                safeLogToSheet("cloud", cloudiness.toString(), "below threshold, turning off", cloudiness)
            }
            evaluateLighting("cloud cover changed to ${cloudiness}%")
        }
    }
}

def outdoorLuxHandler(evt) {
    def lux = evt.value.toInteger()

    if (adaptiveEnabled != false) {
        // Adaptive: a band change re-applies the whole scene (dining included) with a slow fade
        ambientStep("outdoor lux ${lux}")
        return
    }
    
    // Legacy path: only the dining level follows outdoor light
    def newDiningLevel = getDiningLevelForAmbient()
    def lastDiningLevel = state.lastDiningLevel ?: 100
    if (newDiningLevel != lastDiningLevel && state.presenceActive && state.lightNeeded && !state.manualOverride && !state.tvTimeActive) {
        state.lastDiningLevel = newDiningLevel
        log.info "Outdoor lux ${lux} -> dining level ${newDiningLevel}%"
        safeLogToSheet("ambient", lux.toString(), "dining -> ${newDiningLevel}%", lux)
        diningSwitch?.setLevel(newDiningLevel)
    }
}

def tvTimeSwitchHandler(evt) {
    logDebug "TV Time switch: ${evt.value}"
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    if (evt.value == "on") {
        logDebug "Activating TV Time"
        safeLogToSheet("tvTime", "activated", "manual switch", currentLux)
        state.tvTimeActive = true
        state.manualOverride = false  // Clear override when TV time activates
        evaluateLighting("TV Time activated")
    } else {
        logDebug "Deactivating TV Time"
        safeLogToSheet("tvTime", "deactivated", "manual switch", currentLux)
        state.tvTimeActive = false
        evaluateLighting("TV Time deactivated")
    }
}

def resetTvTime() {
    // Auto-reset at 3am in case TV Time was left on
    if (state.tvTimeActive || tvTimeSwitch?.currentSwitch == "on") {
        log.info "3am auto-reset: turning off TV Time"
        def currentLux = luxSensor?.currentIlluminance ?: 0
        safeLogToSheet("tvTime", "auto-reset", "3am cleanup", currentLux)
        state.tvTimeActive = false
        tvTimeSwitch?.off()
    }
}

def livingRoomPresenceHandler(evt) {
    logDebug "Living room presence: ${evt.value}"
    
    // Check if lights are actually on (not relying on state variables)
    def lightsOn = lrHueLights?.currentSwitch == "on"
    
    // If lights are on and not in override/TV mode, adjust zone levels
    if (lightsOn && !state.manualOverride && !state.tvTimeActive) {
        def currentLux = luxSensor?.currentIlluminance ?: 0
        
        if (evt.value == "active") {
            // Cancel any pending dim and immediately brighten (LR ceiling only)
            unschedule(applyLrDimmed)
            safeLogToSheet("zone", "LR active", "brightening", currentLux)
            applyLrZoneLevel(true)
        } else {
            // Delay before dimming to avoid flicker
            logDebug "LR inactive - will dim in ${lrDimDelay ?: 60} seconds"
            runIn(lrDimDelay ?: 60, applyLrDimmed)
        }
    }
}

/** Living room occupied: the mmWave says active, or the PIR saw motion within lrDimVetoMinutes (the mmWave misses people sitting still). */
boolean livingRoomOccupied() {
    boolean mm = (livingRoomPresence && !isAthomStale()) ? (livingRoomPresence.currentValue("mmwave") == "active") : true
    return mm || pirRecent()
}

boolean pirRecent() {
    if (!lrMotionSensor) return false
    if (lrMotionSensor.currentMotion == "active") return true
    return pirVetoRemainingMs() > 0
}

long pirVetoRemainingMs() {
    long vetoMs = ((lrDimVetoMinutes ?: 3) as Long) * 60000L
    return vetoMs - (now() - ((state.lastLrMotionAt ?: 0L) as Long))
}

def lrMotionHandler(evt) {
    state.lastLrMotionAt = now()
    // The mmWave said the room was empty but the PIR disagrees: bring the ceiling back, then let the normal dim check run again
    if (state.lrDimmed && lrHueLights?.currentSwitch == "on" && !state.manualOverride && !state.tvTimeActive) {
        log.info "${evt.displayName} saw motion while the LR ceiling was dimmed (mmWave says ${livingRoomPresence?.currentValue('mmwave')}) - brightening"
        unschedule(applyLrDimmed)
        applyLrZoneLevel(true)
        runIn(lrDimDelay ?: 60, applyLrDimmed)
    }
}

def applyLrDimmed() {
    // Never dim on a dead sensor's last word - fail bright
    if (isAthomStale()) {
        logDebug "Athom stale - skipping LR dim (fail bright)"
        return
    }

    // Verify still inactive and conditions still apply
    def mmwaveState = livingRoomPresence?.currentValue("mmwave")
    def lightsOn = lrHueLights?.currentSwitch == "on"

    if (mmwaveState != "inactive" || !lightsOn || state.manualOverride || state.tvTimeActive) {
        return
    }

    // PIR veto: someone sitting still fools the mmWave but not a PIR that saw them move a minute ago
    if (pirRecent()) {
        Integer waitSec = Math.max(5, ((pirVetoRemainingMs() / 1000) as Integer) + 1)
        if (lrMotionSensor?.currentMotion == "active") waitSec = Math.max(waitSec, ((lrDimVetoMinutes ?: 3) as Integer) * 60)
        logDebug "LR dim deferred ${waitSec}s (${lrMotionSensor?.displayName} saw motion recently)"
        runIn(waitSec, applyLrDimmed)
        return
    }

    // Anti-flap: never dim inside the bright-hold window; re-check when it expires
    def holdMs = (lrBrightHoldMinutes ?: 3) * 60 * 1000
    def sinceBrighten = now() - ((state.lastLrBrightenTime ?: 0) as Long)
    if (sinceBrighten < holdMs) {
        def waitSec = (((holdMs - sinceBrighten) / 1000) as Integer) + 1
        logDebug "LR dim deferred ${waitSec}s (bright-hold active)"
        runIn(waitSec, applyLrDimmed)
        return
    }

    def currentLux = luxSensor?.currentIlluminance ?: 0
    safeLogToSheet("zone", "LR inactive", "dimming", currentLux)
    applyLrZoneLevel(false)
}

def applyLrZoneLevel(Boolean occupied) {
    // Zone dimming touches ONLY the LR ceiling - other lights keep their scene levels
    def isNightish = (location.mode == "Night" || location.mode == "Evening")
    def fullLevel = isNightish ? (isPredawn() ? (predawnHueLevel ?: 30) : (isWindDown() ? (windDownHueLevel ?: 50) : (nightHueLevel ?: 80))) : (dayHueLevel ?: 100)
    def lrLevel = scaled(occupied ? fullLevel : ((fullLevel * 50 / 100) as Integer))
    if (occupied) {
        state.lastLrBrightenTime = now()
    }
    state.lrDimmed = !occupied
    if (lrHueLights?.currentLevel == lrLevel && lrHueLights?.currentSwitch == "on") {
        logDebug "LR zone level already ${lrLevel}% - skipping"
        return
    }
    state.lastAutomationAction = now()
    setLevelSmooth(lrHueLights, lrLevel, 5)
    rememberLevels([lr: lrLevel])
    logDebug "LR zone level -> ${lrLevel}% (${occupied ? 'occupied' : 'unoccupied'})"
}

def modeHandler(evt) {
    logDebug "Mode changed to: ${evt.value}"
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    // Log mode changes to Google Sheets
    safeLogToSheet("mode", evt.value, "", currentLux)
    
    // Update lightNeeded based on new mode AND indoor lux
    def wasNeeded = state.lightNeeded
    state.lightNeeded = isLightNeeded()
    
    logDebug "Mode changed: lightNeeded was ${wasNeeded}, now ${state.lightNeeded}"
    
    if (evt.value in ["Evening", "Night"] && state.pendingDarkOn) {
        unschedule("confirmDarkRoom")
        state.pendingDarkOn = false
    }

    // Always re-evaluate when mode changes (this is now our primary trigger)
    if (wasNeeded != state.lightNeeded || state.presenceActive) {
        state.modeChangeFade = roomIsOn()
        evaluateLighting("mode changed to ${evt.value}")
        state.modeChangeFade = false
    }
}

def manualSwitchHandler(evt) {
    // Detect if a switch was changed manually (physical button press)
    def timeSinceAutomation = now() - (state.lastAutomationAction ?: 0)
    def isPhysical = (evt.type == "physical" || evt.isPhysical())
    
    logDebug "Switch change: ${evt.device.displayName} = ${evt.value}, type=${evt.type}, isPhysical=${isPhysical}, timeSinceAuto=${timeSinceAutomation}ms"
    
    // Only set manual override if it was a PHYSICAL button press
    // Digital commands from other automations should not trigger override
    if (isPhysical && !state.turningOff) {
        log.info "Physical switch change detected: ${evt.device.displayName} = ${evt.value}"
        def currentLux = luxSensor?.currentIlluminance ?: 0
        safeLogToSheet("manual", evt.value, "${evt.device.displayName} (physical)", currentLux)
        state.manualOverride = true
    } else if (timeSinceAutomation > 10000 && !state.turningOff) {
        // Log other automations for debugging but don't set override
        logDebug "Other automation changed: ${evt.device.displayName} = ${evt.value}"
        def currentLux = luxSensor?.currentIlluminance ?: 0
        safeLogToSheet("other_auto", evt.value, evt.device.displayName, currentLux)
    } else {
        logDebug "Ignoring switch change (our automation): ${evt.device.displayName} = ${evt.value}"
    }
}

// ==================== MAIN LOGIC ====================

def evaluateLighting(String reason) {
    // Check if app is paused
    if (appPaused) {
        logDebug "App is paused - skipping automation"
        return
    }
    
    // Recalculate lightNeeded with current sensor values
    state.lightNeeded = isLightNeeded()
    
    logDebug "Evaluating lighting (${reason})"
    logDebug "  State: presence=${state.presenceActive}, lightNeeded=${state.lightNeeded}, override=${state.manualOverride}, tvTime=${state.tvTimeActive}, mode=${location.mode}"
    
    // Check manual override
    if (state.manualOverride) {
        logDebug "Manual override active - skipping automation"
        return
    }
    
    // No one present? Turn everything off
    if (!state.presenceActive) {
        logDebug "No presence - scheduling lights off"
        runIn(lightsOffDelay ?: 10, turnAllLightsOff)
        return
    }
    
    // TV Time?
    if (state.tvTimeActive) {
        unschedule(turnAllLightsOff)  // Cancel pending off - we want lights on (TV scene)
        logDebug "TV Time active - applying TV scene"
        applyTvScene()
        return
    }
    
    // Light not needed (bright outside)?
    if (!state.lightNeeded) {
        // If we're waiting for hysteresis on bright-off, don't turn off yet
        if (state.pendingBrightOff) {
            logDebug "Light not needed but waiting for bright hysteresis - keeping lights on"
            return
        }
        logDebug "Light not needed - turning lights off"
        def currentLux = luxSensor?.currentIlluminance ?: 0
        safeLogToSheet("bright", "off", "Light no longer needed", currentLux)
        turnAllLightsOff()  // Turn off immediately, don't schedule
        return
    }
    
    // Daytime debounce pending (bright outside, dim inside): keep waiting unless the room is already on
    if (state.pendingDarkOn && !roomIsOn()) {
        logDebug "Dark-room debounce pending - not turning on yet"
        return
    }

    // We need lights - cancel any pending off
    unschedule(turnAllLightsOff)
    
    // Apply scene based on mode
    if (location.mode == "Night") {
        if (isPredawn()) {
            logDebug "Night mode (predawn) - applying predawn scene"
            applyPredawnScene()
        } else if (isWindDown()) {
            logDebug "Night mode (wind-down) - applying wind-down scene"
            applyWindDownScene()
        } else {
            logDebug "Night mode - applying night scene"
            applyNightScene()
        }
    } else if (location.mode == "Evening") {
        if (isWindDown()) {
            logDebug "Evening mode (wind-down) - applying wind-down scene"
            applyWindDownScene()
        } else {
            logDebug "Evening mode - applying night scene"
            applyNightScene()
        }
    } else {
        logDebug "Other mode (${location.mode}) - applying day scene"
        applyDayScene()
    }
}

// ==================== ADAPTIVE BRIGHTNESS ====================

/** Which scene-level band the outdoor light calls for: 35 / 55 / 75 / 100 percent of the scene's base levels. scale widens the edges for hysteresis. */
Integer bandFactor(Number lux, BigDecimal scale) {
    BigDecimal l55 = ((adaptiveLux55 ?: 400) as BigDecimal) * scale
    BigDecimal l75 = ((adaptiveLux75 ?: 150) as BigDecimal) * scale
    BigDecimal l100 = ((adaptiveLux100 ?: 50) as BigDecimal) * scale
    BigDecimal l = lux as BigDecimal
    if (l < l100) return 100
    if (l < l75) return 75
    if (l < l55) return 55
    return 35
}

/** The band for the current outdoor reading, no hysteresis or rate limit (used when the room turns on and for status). */
Integer rawAmbientFactor() {
    if (adaptiveEnabled == false) return 100
    def lux = outdoorLuxSensor?.currentIlluminance
    if (lux == null) return 100
    return bandFactor(lux, 1.0)
}

/**
 * Ambient factor with hysteresis and a rate limit, committed to state.
 * Darker outside: step up as soon as the reading crosses an edge. Brighter outside: step down only once the
 * reading is 40% past the edge, so dusk never flickers. At most one step per ambientStepMinutes.
 */
Integer ambientFactor(boolean commit = true) {
    if (adaptiveEnabled == false) return 100
    def lux = outdoorLuxSensor?.currentIlluminance
    if (lux == null) return 100
    Integer last = (state.ambientFactor ?: rawAmbientFactor()) as Integer
    Integer up = bandFactor(lux, 1.0)
    Integer down = bandFactor(lux, 1.4)
    Integer target = last
    if (up > last) target = up
    else if (down < last) target = Math.max(down, nextBandDown(last))   // brightening: one band per step, never a plunge
    if (target == last || !commit) return commit ? last : target
    long since = now() - ((state.ambientChangedAt ?: 0) as Long)
    long minMs = ((ambientStepMinutes ?: 10) as Long) * 60000L
    if (since < minMs) {
        runIn((((minMs - since) / 1000) as Integer) + 1, "ambientRecheck")
        return last
    }
    state.ambientFactor = target
    state.ambientChangedAt = now()
    log.info "Ambient factor ${last}% -> ${target}% (outdoor ${lux} lux)"
    return target
}

/** The band just below the given factor (100 -> 75 -> 55 -> 35). */
Integer nextBandDown(Integer factor) {
    if (factor > 75) return 75
    if (factor > 55) return 55
    return 35
}

/** Morning ramp: 30% of scene levels when predawn ends, rising to 100% over morningRampMinutes. 100 when no ramp is running. */
Integer rampFactor() {
    if (!state.morningRamp) return 100
    long elapsed = now() - ((state.morningRamp.start ?: 0) as Long)
    Integer mins = (state.morningRamp.minutes ?: 30) as Integer
    if (mins <= 0) return 100
    return Math.min(100, (30 + (70 * elapsed / (mins * 60000L))) as Integer)
}

/** Ambient factor, raised to the dark-room floor while one is set (the room already measured dark), capped by the morning ramp. */
Integer effectiveFactor() {
    Integer ambient = ambientFactor()
    Integer floor = (state.factorFloor ?: 0) as Integer
    return Math.min(Math.max(ambient, floor), rampFactor())
}

/** Dining Edisons dim poorly, so they step 50 / 75 / 100 with the factor instead of scaling continuously. */
Integer diningLevelFor(Integer factor) {
    if (factor >= 100) return 100
    if (factor >= 75) return 75
    return 50
}

/** While the factor is rising (dusk, morning ramp) a main light never steps down from its last commanded level. */
Integer holdUp(Integer target, String key, boolean hold) {
    if (!hold) return target
    Integer last = ((state.lastLevels ?: [:])[key] ?: 0) as Integer
    return Math.max(target, last)
}

def rememberLevels(Map levels) {
    Map current = (state.lastLevels ?: [:]) as Map
    levels.each { k, v -> current[k] = v }
    state.lastLevels = current
}

/** Scale a scene level by the effective factor, never below 10% (or the level itself when lower) and never above 100. */
Integer scaled(Number base) {
    Integer b = (base ?: 0) as Integer
    if (b <= 0) return 0
    Integer v = Math.round(((b * effectiveFactor()) / 100.0d) as double) as Integer
    return Math.min(100, Math.max(Math.min(10, b), v))
}

/** The cans are on/off only, so they join once the effective factor reaches cansFactor (default 75%) and drop out below it. */
boolean cansWanted(Integer factor) {
    if (adaptiveEnabled == false) return true
    if (factor == null) return true
    return factor >= ((cansFactor ?: 75) as Integer)
}

def setCans(boolean on) {
    if (!kitchenCans) return
    if (on && kitchenCans.currentSwitch != "on") kitchenCans.on()
    else if (!on) {
        unschedule("cansOnDelayed")
        if (kitchenCans.currentSwitch != "off") kitchenCans.off()
    }
}

/** Cans cannot fade, so while the rest of the room is fading in they join at the end of the fade instead of popping on first. */
def applyCans(boolean on, Integer fade) {
    if (!kitchenCans) return
    if (!on) { setCans(false); return }
    if (kitchenCans.currentSwitch == "on") return
    if ((fade ?: 0) <= 5) { setCans(true); return }
    runIn(fade, "cansOnDelayed")
}

def cansOnDelayed() {
    if (!(state.currentScene in ["day", "night"]) || !roomIsOn()) return
    if (state.manualOverride || state.tvTimeActive) return
    if (cansWanted((state.lastFactor ?: 100) as Integer)) setCans(true)
}

boolean roomIsOn() {
    return [hallwaySwitch, kitchenPendant, lrHueLights, diningSwitch].any { it?.currentSwitch == "on" }
}

/** Fade for a scene application: long fade-in when the room was off, short when changing an already-lit room. */
Integer sceneFade(Number override) {
    if (override != null) return override as Integer
    if (!roomIsOn()) {
        // Fresh turn-on: start from the current outdoor reading, not a factor left over from last night
        state.ambientFactor = rawAmbientFactor()
        state.ambientChangedAt = now()
        return (turnOnFadeSeconds ?: 60) as Integer
    }
    if (state.modeChangeFade) {
        // A mode change re-applies a scene with different bases: take the slow fade, not the 3-second one
        state.modeChangeFade = false
        return (ambientFadeSeconds ?: 45) as Integer
    }
    return (sceneFadeSeconds ?: 3) as Integer
}

/** setLevel with a transition where the driver takes one (all the dimmers and Hue devices here do). */
def setLevelSmooth(dev, Number level, Number seconds) {
    if (!dev) return
    Integer lvl = Math.max(0, Math.min(100, (level ?: 0) as Integer))
    Integer sec = Math.max(0, (seconds ?: 0) as Integer)
    try {
        dev.setLevel(lvl, sec)
    } catch (e) {
        logDebug "${dev.displayName}: setLevel with duration failed (${e.message}); plain setLevel"
        dev.setLevel(lvl)
    }
}

/** Re-apply the current day/night scene when the ambient band changes while the room is on. */
def ambientStep(String reason) {
    if (adaptiveEnabled == false || appPaused) return
    if (!(state.presenceActive && state.lightNeeded && !state.manualOverride && !state.tvTimeActive)) return
    if (!(state.currentScene in ["day", "night"]) || !roomIsOn()) return
    Integer before = (state.ambientFactor ?: 100) as Integer
    Integer after = ambientFactor()
    if (after == before) return
    reapplyCurrentScene("ambient ${reason}", (ambientFadeSeconds ?: 45) as Integer)
}

def ambientRecheck() {
    ambientStep("recheck")
}

def reapplyCurrentScene(String reason, Integer fade) {
    logDebug "Re-applying ${state.currentScene} scene (${reason}, fade ${fade}s)"
    if (state.currentScene == "day") applyDayScene(fade)
    else if (state.currentScene == "night") applyNightScene(fade)
}

boolean daytimeDebounceApplies() {
    if (adaptiveEnabled == false) return false
    if (location.mode in ["Evening", "Night"]) return false
    if (((daytimeOnDebounceMinutes ?: 10) as Integer) <= 0) return false
    def lux = outdoorLuxSensor?.currentIlluminance
    return lux != null && lux >= (adaptiveLux55 ?: 400)
}

/** Long debounce under a bright sky (a cloud passing), short once outdoor light is already fading. */
Integer darkRoomDebounceMinutes() {
    Integer dayMins = (daytimeOnDebounceMinutes ?: 10) as Integer
    Integer duskMins = (duskDebounceMinutes ?: 3) as Integer
    def lux = outdoorLuxSensor?.currentIlluminance
    if (lux != null && lux < (duskLuxThreshold ?: 1000)) return Math.max(0, Math.min(dayMins, duskMins))
    return dayMins
}

def confirmDarkRoom() {
    state.pendingDarkOn = false
    state.lightNeeded = isLightNeeded()
    if (state.lightNeeded && state.presenceActive && !state.manualOverride && !state.tvTimeActive) {
        // The room has proven it is dark: the "still bright outside" 35% band would barely register, so hold a floor until the room turns off
        state.factorFloor = (darkRoomMinFactor ?: 55) as Integer
        log.info "Dark room held for ${state.pendingDarkMins ?: daytimeOnDebounceMinutes ?: 10} minutes - turning on lights (factor floor ${state.factorFloor}%)"
        evaluateLighting("dark room confirmed")
    } else {
        log.info "Dark-room condition cleared before the debounce ended - not turning on"
    }
}

String adaptiveStatus() {
    def lux = outdoorLuxSensor?.currentIlluminance
    Integer endMins = predawnEndMinutes()
    String predawnAt = String.format('%02d:%02d', (endMins / 60) as Integer, endMins % 60)
    String ramp = state.morningRamp ? "morning ramp at ${rampFactor()}%" : "no morning ramp running"
    String floor = state.factorFloor ? ", dark-room floor ${state.factorFloor}%" : ""
    return "Outdoor ${lux != null ? lux + ' lux' : 'sensor missing'}: band ${rawAmbientFactor()}%, applied factor ${state.ambientFactor ?: '-'}%${floor}; " +
           "${ramp}; predawn ends ${predawnAt} today; scene now: ${state.currentScene ?: 'off'}; " +
           "cans ${cansWanted((state.lastFactor ?: state.ambientFactor ?: 100) as Integer) ? 'allowed' : 'held off'} (from a ${cansFactor ?: 75}% factor)"
}

// ==================== SCENE ACTIONS ====================

def applyDayScene(Number fadeOverride = null) {
    state.lastAutomationAction = now()
    def currentLux = luxSensor?.currentIlluminance ?: 0
    Integer fade = sceneFade(fadeOverride)
    if (!state.factorFloor && roomMeasuredDark()) {
        // The room measured dark while it is still "daylight" outside: the 35% band would barely register
        state.factorFloor = (darkRoomMinFactor ?: 55) as Integer
        log.info "Room measured dark (${indoorReadings()}) - factor floor ${state.factorFloor}% until the room turns off"
    }
    Integer factor = effectiveFactor()
    
    // Check if living room is occupied (default to true if sensor not configured)
    def lrOccupied = livingRoomOccupied()
    Integer lrLevel = scaled(lrOccupied ? (dayHueLevel ?: 100) : 50)
    Integer hallwayLevel = scaled(dayHallwayLevel ?: 99)

    // Dining Edisons step 50 / 75 / 100 with the factor
    Integer diningLevel = diningLevelFor(factor)
    boolean cansOn = cansWanted(factor)

    logDebug "Day scene: factor=${factor}%, fade=${fade}s, LR occupied=${lrOccupied}, lrLevel=${lrLevel}, diningLevel=${diningLevel}, cans=${cansOn}"

    // Kitchen/Hallway zone
    setLevelSmooth(diningSwitch, diningLevel, fade)
    setLevelSmooth(hallwaySwitch, hallwayLevel, fade)
    applyCans(cansOn, fade)
    setLevelSmooth(kitchenPendant, scaled(dayKitchenPendantLevel ?: 100), fade)
    
    // Living Room zone - only ceiling dims when unoccupied, bookcase stays constant
    setLevelSmooth(bookcaseGOLamp, scaled(dayBookcaseLevel ?: 50), fade)
    setLevelSmooth(bookcaseColorLamp, scaled(dayBookcaseLevel ?: 50), fade)
    setLevelSmooth(lrHueLights, lrLevel, fade)
    state.lrDimmed = !lrOccupied
    
    // Set color temp if supported (warm white)
    if (lrHueLights?.hasCommand("setColorTemperature")) {
        lrHueLights.setColorTemperature(2700)
    }
    
    activatorSwitch?.on()
    state.currentScene = "day"
    state.lastFactor = factor
    rememberLevels([lr: lrLevel, hallway: hallwayLevel, dining: diningLevel])

    log.info "Day scene applied (factor ${factor}%, fade ${fade}s, LR ${lrOccupied ? 'occupied' : 'unoccupied'} ${lrLevel}%, dining ${diningLevel}%, cans ${cansOn ? 'on' : 'off'})"
    safeLogToSheet("scene", "Day", "factor ${factor}% LR ${lrLevel}% dining ${diningLevel}%", currentLux)
}

def applyNightScene(Number fadeOverride = null) {
    state.lastAutomationAction = now()
    def currentLux = luxSensor?.currentIlluminance ?: 0
    Integer fade = sceneFade(fadeOverride)
    Integer factor = effectiveFactor()
    
    // Check if living room is occupied (default to true if sensor not configured)
    def lrOccupied = livingRoomOccupied()
    def lrFull = nightHueLevel ?: 80

    // Hold-up: while the factor is rising (dusk, morning ramp) and the room was already lit by the day/night scene,
    // the main lights never step down (the night bases are lower than the day bases, so the mode change used to dip them).
    // A falling factor (brightening morning) is allowed through: that is the one-band-at-a-time step-down.
    boolean rising = factor >= ((state.lastFactor ?: factor) as Integer)
    boolean hold = rising && factor < 100 && roomIsOn() && (state.currentScene in ["day", "night"])
    Integer lrLevel = holdUp(scaled(lrOccupied ? lrFull : ((lrFull * 50 / 100) as Integer)), "lr", hold && lrOccupied)
    Integer hallwayLevel = holdUp(scaled(nightHallwayLevel ?: 80), "hallway", hold)
    Integer diningLevel = holdUp(diningLevelFor(factor), "dining", hold)
    boolean cansOn = cansWanted(factor)

    logDebug "Night scene: factor=${factor}%, fade=${fade}s, hold=${hold}, LR occupied=${lrOccupied}, lrLevel=${lrLevel}, hallway=${hallwayLevel}, diningLevel=${diningLevel}, cans=${cansOn}"

    // Kitchen/Hallway zone
    setLevelSmooth(diningSwitch, diningLevel, fade)
    setLevelSmooth(hallwaySwitch, hallwayLevel, fade)
    applyCans(cansOn, fade)
    setLevelSmooth(kitchenPendant, scaled(nightKitchenPendantLevel ?: 30), fade)
    
    // Living Room zone - only ceiling dims when unoccupied, bookcase stays constant
    setLevelSmooth(bookcaseGOLamp, scaled(nightBookcaseLevel ?: 15), fade)
    setLevelSmooth(bookcaseColorLamp, scaled(nightBookcaseLevel ?: 15), fade)
    setLevelSmooth(lrHueLights, lrLevel, fade)
    state.lrDimmed = !lrOccupied
    
    // Set color temp if supported (warm white)
    if (lrHueLights?.hasCommand("setColorTemperature")) {
        lrHueLights.setColorTemperature(2700)
    }
    
    activatorSwitch?.on()
    state.currentScene = "night"
    state.lastFactor = factor
    rememberLevels([lr: lrLevel, hallway: hallwayLevel, dining: diningLevel])

    log.info "Night scene applied (factor ${factor}%, fade ${fade}s${hold ? ', hold' : ''}, LR ${lrOccupied ? 'occupied' : 'unoccupied'} ${lrLevel}%, hallway ${hallwayLevel}%, dining ${diningLevel}%, cans ${cansOn ? 'on' : 'off'})"
    safeLogToSheet("scene", "Night", "factor ${factor}% LR ${lrLevel}% dining ${diningLevel}%", currentLux)
}

def applyPredawnScene() {
    state.lastAutomationAction = now()
    def currentLux = luxSensor?.currentIlluminance ?: 0

    def lrOccupied = livingRoomOccupied()
    def lrFull = predawnHueLevel ?: 30
    def lrLevel = lrOccupied ? lrFull : ((lrFull * 50 / 100) as Integer)
    if (lrOccupied) {
        state.lastLrBrightenTime = now()
    }

    // Gentle wake: low warm light only - dining Edisons and kitchen cans stay off
    Integer fade = sceneFade(null)
    diningSwitch?.off()
    setLevelSmooth(hallwaySwitch, predawnHallwayLevel ?: 20, fade)
    setCans(false)
    setLevelSmooth(kitchenPendant, predawnKitchenPendantLevel ?: 10, fade)
    setLevelSmooth(bookcaseGOLamp, predawnBookcaseLevel ?: 10, fade)
    setLevelSmooth(bookcaseColorLamp, predawnBookcaseLevel ?: 10, fade)
    setLevelSmooth(lrHueLights, lrLevel, fade)
    state.currentScene = "predawn"

    // Extra warm color temp for early morning
    if (lrHueLights?.hasCommand("setColorTemperature")) {
        lrHueLights.setColorTemperature(2200)
    }

    activatorSwitch?.on()

    log.info "Predawn scene applied (LR ${lrOccupied ? 'occupied' : 'unoccupied'})"
    safeLogToSheet("scene", "Predawn", "gentle wake", currentLux)
}

def applyWindDownScene() {
    state.lastAutomationAction = now()
    def currentLux = luxSensor?.currentIlluminance ?: 0

    def lrOccupied = livingRoomOccupied()
    def lrFull = windDownHueLevel ?: 50
    def lrLevel = lrOccupied ? lrFull : ((lrFull * 50 / 100) as Integer)
    if (lrOccupied) {
        state.lastLrBrightenTime = now()
    }

    // Ease toward bedtime: dimmer and warmer everywhere, over 30 s - kitchen cans off (not dimmable)
    Integer fade = roomIsOn() ? 30 : sceneFade(null)
    setLevelSmooth(diningSwitch, windDownDiningLevel ?: 30, fade)
    setLevelSmooth(hallwaySwitch, windDownHallwayLevel ?: 40, fade)
    setCans(false)
    setLevelSmooth(kitchenPendant, windDownKitchenPendantLevel ?: 15, fade)
    setLevelSmooth(bookcaseGOLamp, windDownBookcaseLevel ?: 10, fade)
    setLevelSmooth(bookcaseColorLamp, windDownBookcaseLevel ?: 10, fade)
    setLevelSmooth(lrHueLights, lrLevel, fade)
    state.currentScene = "winddown"

    // Warm color temp for the late evening
    if (lrHueLights?.hasCommand("setColorTemperature")) {
        lrHueLights.setColorTemperature(2400)
    }

    activatorSwitch?.on()

    log.info "Wind-down scene applied (LR ${lrOccupied ? 'occupied' : 'unoccupied'})"
    safeLogToSheet("scene", "WindDown", "evening ease", currentLux)
}

def applyTvScene() {
    state.lastAutomationAction = now()
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    // Accent lights - very warm (2200K) and dim
    bookcaseGOLamp?.setColorTemperature(2200)
    bookcaseGOLamp?.setLevel(tvBookcaseLevel ?: 15)
    bookcaseColorLamp?.setColorTemperature(2200)
    bookcaseColorLamp?.setLevel(tvBookcaseLevel ?: 15)
    
    // Kitchen pendant as subtle ambient light
    kitchenPendant?.setLevel(5)
    
    // Turn off other lights
    if (tvOtherLightsOff != false) {
        unschedule("cansOnDelayed")
        diningSwitch?.off()
        hallwaySwitch?.off()
        kitchenCans?.off()
        lrHueLights?.off()
    }
    
    activatorSwitch?.off()
    state.currentScene = "tv"
    
    logDebug "TV Time scene applied"
    log.info "TV Time scene applied"
    safeLogToSheet("scene", "TV Time", "accent only", currentLux)
}

def turnAllLightsOff() {
    // Skip when everything is already off - avoids repeated radio commands and duplicate log rows
    def anyOn = [diningSwitch, hallwaySwitch, kitchenCans, kitchenPendant,
                 bookcaseGOLamp, bookcaseColorLamp, lrHueLights, activatorSwitch].any { it?.currentSwitch == "on" }
    if (!anyOn) {
        logDebug "turnAllLightsOff: everything already off - skipping"
        return
    }
    state.lastAutomationAction = now()
    state.turningOff = true
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    unschedule("cansOnDelayed")
    diningSwitch?.off()
    hallwaySwitch?.off()
    kitchenCans?.off()
    kitchenPendant?.off()
    bookcaseGOLamp?.off()
    bookcaseColorLamp?.off()
    lrHueLights?.off()
    activatorSwitch?.off()
    
    // Clear the turningOff flag after a delay
    runIn(3, clearTurningOffFlag)
    state.currentScene = "off"
    state.factorFloor = null     // the dark-room floor lasts only while the room is lit
    state.lastLevels = null      // nothing to hold against once everything is off
    state.lastFactor = null
    
    log.info "All lights turned off"
    safeLogToSheet("scene", "Off", "all lights off", currentLux)
}

def clearTurningOffFlag() {
    state.turningOff = false
}

// ==================== GOOGLE SHEETS LOGGING ====================

def safeLogToSheet(String eventType, String value, String details, lux) {
    try {
        logToSheet(eventType, value, details, lux as Integer)
    } catch (e) {
        log.warn "Logging failed (non-fatal): ${e.message}"
    }
}

def logToSheet(String eventType, String value, String details, Integer lux) {
    if (!webhookUrl) {
        return  // No webhook configured
    }
    
    try {
        def payload = [
            timestamp: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone),
            eventType: eventType,
            value: value,
            details: details,
            lux: lux ?: 0,
            mode: location.mode,
            presence: state.presenceActive ? "true" : "false"
        ]
        
        def params = [
            uri: webhookUrl,
            contentType: "application/json",
            body: groovy.json.JsonOutput.toJson(payload),
            timeout: 10
        ]
        
        asynchttpPost(handleLogResponse, params)
        
    } catch (e) {
        logDebug "Error sending to Google Sheets: ${e.message}"
    }
}

def handleLogResponse(response, data) {
    // Silent handler - we don't need to do anything with the response
    if (response.hasError()) {
        logDebug "Google Sheets logging error: ${response.getErrorMessage()}"
    }
}

// ==================== HELPER FUNCTIONS ====================

def logDebug(msg) {
    if (logEnable) log.debug msg
}
