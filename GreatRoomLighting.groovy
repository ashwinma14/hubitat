/**
 *  Great Room Lighting Controller
 *  
 *  A unified lighting automation for a great room (kitchen, dining, hallway, living room).
 *  Handles presence detection, ambient light levels, TV time, and manual overrides.
 *
 *  Author: Claude (for Ashwin)
 *  Date: 2026-02-12
 *  Version: 1.27 - Add 10-minute hysteresis before turning off when bright
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

def mainPage() {
    dynamicPage(name: "mainPage", title: "Great Room Lighting Controller", install: true, uninstall: true) {
        
        section("<b>Devices</b>") {
            input "motionZone", "capability.motionSensor", title: "Motion Zone", required: true
            input "livingRoomPresence", "capability.motionSensor", title: "Living Room Presence Sensor (Athom - for zone dimming)", required: false
            input "kitchenPresence", "capability.motionSensor", title: "Kitchen Presence Sensor (FP1E - for true presence)", required: false
            input "indoorLuxSensor", "capability.illuminanceMeasurement", title: "Indoor Lux Sensor (for dark room detection)", required: false
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
            input "cloudyThreshold", "number", title: "Cloud cover % threshold (turn on if above)", defaultValue: 70, required: true
            input "cloudyLuxThreshold", "number", title: "Outdoor lux threshold for cloudy check", defaultValue: 400, required: true
            input "presenceTimeout", "number", title: "Minutes before presence times out", defaultValue: 8, required: true
            input "lightsOffDelay", "number", title: "Seconds delay before turning lights off", defaultValue: 10, required: true
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
            input "nightHallwayLevel", "number", title: "Hallway brightness (0-100)", defaultValue: 99
            input "nightKitchenPendantLevel", "number", title: "Kitchen Pendant brightness", defaultValue: 30
            input "nightBookcaseLevel", "number", title: "Bookcase lamps brightness", defaultValue: 15
            input "nightHueLevel", "number", title: "LR Hue lights brightness", defaultValue: 100
        }
        
        section("<b>Scene Settings - TV Time</b>") {
            input "tvBookcaseLevel", "number", title: "Bookcase lamps brightness", defaultValue: 15
            input "tvOtherLightsOff", "bool", title: "Turn off other lights?", defaultValue: true
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
    log.info "  Kitchen Presence: ${kitchenPresence?.displayName ?: 'not configured'}"
    log.info "  Indoor Lux Sensor: ${indoorLuxSensor?.displayName ?: 'not configured'}"
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
    
    // Subscribe to kitchen presence for true presence detection
    if (kitchenPresence) {
        subscribe(kitchenPresence, "roomState", truePresenceHandler)
    }
    
    // Subscribe to indoor lux sensor for dark room detection
    if (indoorLuxSensor) {
        subscribe(indoorLuxSensor, "illuminance", indoorLuxHandler)
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
    
    // Set lightNeeded based on current mode and indoor lux
    state.lightNeeded = isLightNeeded()
    
    // Schedule 3am TV Time auto-reset
    schedule("0 0 3 * * ?", resetTvTime)
    
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
    safeLogToSheet("init", "complete", "App started v1.25", currentLux)
    
    // Check if paused
    if (appPaused) {
        log.info "App is paused - not taking any action"
        return
    }
    
    // If conditions say lights should be ON, turn them on
    if (state.presenceActive && state.lightNeeded && !state.manualOverride && !state.tvTimeActive) {
        log.info "Conditions warrant lights on - applying scene"
        if (location.mode == "Night") {
            applyNightScene()
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
    
    // Indoor lux check with hysteresis to prevent feedback loop
    // Lights turn ON at indoorLuxThreshold, OFF at threshold + 30
    def indoorLux = indoorLuxSensor?.currentIlluminance
    def indoorThreshold = indoorLuxThreshold ?: 15
    def indoorOffThreshold = indoorThreshold + 30  // Hysteresis buffer (lights add ~21 lux to sensor)
    
    // Check current light state to apply hysteresis
    def lightsCurrentlyOn = (diningSwitch?.currentSwitch == "on" || kitchenCans?.currentSwitch == "on")
    def indoorNeedsLight = false
    if (indoorLux != null) {
        if (lightsCurrentlyOn) {
            // Lights are on - only say "not needed" if above the higher threshold
            indoorNeedsLight = (indoorLux < indoorOffThreshold)
        } else {
            // Lights are off - use normal threshold to turn on
            indoorNeedsLight = (indoorLux < indoorThreshold)
        }
    }
    
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
    
    logDebug "isLightNeeded: mode=${location.mode} (${modeNeedsLight}), indoorLux=${indoorLux}/${indoorThreshold}/${indoorOffThreshold} lightsOn=${lightsCurrentlyOn} (${indoorNeedsLight}), clouds=${cloudiness}%/${cloudThreshold}% outdoorLux=${outdoorLux}/${luxThreshold}/${luxOffThreshold} (${cloudyNeedsLight})"
    
    return modeNeedsLight || indoorNeedsLight || cloudyNeedsLight
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

def isTruePresenceDetected() {
    // Check mmWave/radar presence attributes (not just PIR motion)
    // These detect sitting still, unlike motion sensors
    
    def athomPresent = false
    def fp1ePresent = false
    
    // Check Athom mmwave attribute
    if (livingRoomPresence) {
        def mmwave = livingRoomPresence.currentValue("mmwave")
        athomPresent = (mmwave == "active")
        logDebug "Athom mmwave: ${mmwave} -> ${athomPresent ? 'present' : 'not present'}"
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
    def lux = evt.value.toInteger()
    def threshold = indoorLuxThreshold ?: 15
    
    logDebug "Indoor lux changed: ${lux} (threshold: ${threshold})"
    
    // If presence is active, check if lighting needs to change
    if (state.presenceActive && !state.manualOverride && !state.tvTimeActive) {
        def wasLightNeeded = state.lightNeeded
        state.lightNeeded = isLightNeeded()
        
        if (wasLightNeeded != state.lightNeeded) {
            if (state.lightNeeded) {
                // Got darker - turn on immediately, cancel any pending off
                unschedule(turnOffDueToBright)
                state.pendingBrightOff = false
                log.info "Indoor lux dropped below ${threshold} - turning on lights"
                safeLogToSheet("indoorLux", lux.toString(), "below threshold, turning on", lux)
                evaluateLighting("indoor lux changed to ${lux}")
            } else {
                // Got brighter - delay 10 minutes before turning off
                if (!state.pendingBrightOff) {
                    log.info "Indoor lux rose above threshold - will turn off in 10 minutes if still bright"
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
    def threshold = indoorLuxThreshold ?: 15
    
    // Verify still bright before turning off
    if (lux > threshold && state.presenceActive && !state.manualOverride && !state.tvTimeActive) {
        log.info "Still bright after 10 minutes (lux: ${lux}) - turning off lights"
        safeLogToSheet("indoorLux", lux.toString(), "still bright, turning off", lux)
        state.lightNeeded = false
        evaluateLighting("bright for 10+ minutes")
    } else {
        log.info "Conditions changed - not turning off (lux: ${lux}, threshold: ${threshold})"
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
    
    // Track last dining level to avoid unnecessary updates
    def newDiningLevel = getDiningLevelForAmbient()
    def lastDiningLevel = state.lastDiningLevel ?: 100
    
    // Only update if level changed and lights are on
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
            // Cancel any pending dim and immediately brighten
            unschedule(applyLrDimmed)
            safeLogToSheet("zone", "LR active", "brightening", currentLux)
            
            if (location.mode == "Night" || location.mode == "Evening") {
                applyNightScene()
            } else {
                applyDayScene()
            }
        } else {
            // Delay before dimming to avoid flicker
            logDebug "LR inactive - will dim in 15 seconds"
            runIn(15, applyLrDimmed)
        }
    }
}

def applyLrDimmed() {
    // Verify still inactive and conditions still apply
    def mmwaveState = livingRoomPresence?.currentValue("mmwave")
    def lightsOn = lrHueLights?.currentSwitch == "on"
    
    if (mmwaveState == "inactive" && lightsOn && !state.manualOverride && !state.tvTimeActive) {
        def currentLux = luxSensor?.currentIlluminance ?: 0
        safeLogToSheet("zone", "LR inactive", "dimming", currentLux)
        
        if (location.mode == "Night" || location.mode == "Evening") {
            applyNightScene()
        } else {
            applyDayScene()
        }
    }
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
    
    // Always re-evaluate when mode changes (this is now our primary trigger)
    if (wasNeeded != state.lightNeeded || state.presenceActive) {
        evaluateLighting("mode changed to ${evt.value}")
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
    
    // We need lights - cancel any pending off
    unschedule(turnAllLightsOff)
    
    // Apply scene based on mode
    if (location.mode == "Night") {
        logDebug "Night mode - applying night scene"
        applyNightScene()
    } else if (location.mode == "Evening") {
        logDebug "Evening mode - applying night scene"
        applyNightScene()
    } else {
        logDebug "Other mode (${location.mode}) - applying day scene"
        applyDayScene()
    }
}

// ==================== SCENE ACTIONS ====================

def applyDayScene() {
    state.lastAutomationAction = now()
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    // Check if living room is occupied (default to true if sensor not configured)
    def lrOccupied = livingRoomPresence ? (livingRoomPresence.currentValue("mmwave") == "active") : true
    def lrLevel = lrOccupied ? (dayHueLevel ?: 100) : 50
    
    // Get ambient-based dining level
    def diningLevel = getDiningLevelForAmbient()
    
    logDebug "Day scene: LR occupied=${lrOccupied}, lrLevel=${lrLevel}, diningLevel=${diningLevel}"
    
    // Kitchen/Hallway zone
    diningSwitch?.setLevel(diningLevel)
    hallwaySwitch?.setLevel(dayHallwayLevel ?: 99)
    kitchenCans?.on()
    kitchenPendant?.setLevel(dayKitchenPendantLevel ?: 100)
    
    // Living Room zone - only ceiling dims when unoccupied, bookcase stays constant
    bookcaseGOLamp?.setLevel(dayBookcaseLevel ?: 50)
    bookcaseColorLamp?.setLevel(dayBookcaseLevel ?: 50)
    lrHueLights?.setLevel(lrLevel)
    
    // Set color temp if supported (warm white)
    if (lrHueLights?.hasCommand("setColorTemperature")) {
        lrHueLights.setColorTemperature(2700)
    }
    
    activatorSwitch?.on()
    
    logDebug "Day scene applied"
    log.info "Day scene applied (LR ${lrOccupied ? 'occupied' : 'unoccupied'}, dining ${diningLevel}%)"
    safeLogToSheet("scene", "Day", "LR ${lrOccupied ? '100%' : '50%'} dining ${diningLevel}%", currentLux)
}

def applyNightScene() {
    state.lastAutomationAction = now()
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
    // Check if living room is occupied (default to true if sensor not configured)
    def lrOccupied = livingRoomPresence ? (livingRoomPresence.currentValue("mmwave") == "active") : true
    def lrLevel = lrOccupied ? (nightHueLevel ?: 100) : 50
    
    // Get ambient-based dining level (will be 100% at night since outdoor lux is low)
    def diningLevel = getDiningLevelForAmbient()
    
    logDebug "Night scene: LR occupied=${lrOccupied}, lrLevel=${lrLevel}, diningLevel=${diningLevel}"
    
    // Kitchen/Hallway zone
    diningSwitch?.setLevel(diningLevel)
    hallwaySwitch?.setLevel(nightHallwayLevel ?: 99)
    kitchenCans?.on()
    kitchenPendant?.setLevel(nightKitchenPendantLevel ?: 30)
    
    // Living Room zone - only ceiling dims when unoccupied, bookcase stays constant
    bookcaseGOLamp?.setLevel(nightBookcaseLevel ?: 15)
    bookcaseColorLamp?.setLevel(nightBookcaseLevel ?: 15)
    lrHueLights?.setLevel(lrLevel)
    
    // Set color temp if supported (warm white)
    if (lrHueLights?.hasCommand("setColorTemperature")) {
        lrHueLights.setColorTemperature(2700)
    }
    
    activatorSwitch?.on()
    
    logDebug "Night scene applied"
    log.info "Night scene applied (LR ${lrOccupied ? 'occupied' : 'unoccupied'}, dining ${diningLevel}%)"
    safeLogToSheet("scene", "Night", "LR ${lrOccupied ? '100%' : '50%'} dining ${diningLevel}%", currentLux)
    log.info "Night scene applied (LR ${lrOccupied ? 'occupied' : 'unoccupied'})"
    safeLogToSheet("scene", "Night", "LR ${lrOccupied ? '100%' : '50%'}", currentLux)
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
        diningSwitch?.off()
        hallwaySwitch?.off()
        kitchenCans?.off()
        lrHueLights?.off()
    }
    
    activatorSwitch?.off()
    
    logDebug "TV Time scene applied"
    log.info "TV Time scene applied"
    safeLogToSheet("scene", "TV Time", "accent only", currentLux)
}

def turnAllLightsOff() {
    state.lastAutomationAction = now()
    state.turningOff = true
    def currentLux = luxSensor?.currentIlluminance ?: 0
    
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
