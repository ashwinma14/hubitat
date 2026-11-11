/**
 *  MIT License
 *  Copyright 2024 Joshua Glemza
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 *
 *  Local change (jungalow, 2026-11-11): illuminance deadband. The sensor reports its light reading several times a
 *  minute with a few lux of jitter, and every one became an event. A reading now becomes an event only when it moves
 *  by the deadband (default 5 lux or 10%, whichever is larger), or when 10 minutes have passed since the last one:
 *  that heartbeat is what the Great Room app's "Athom is silent" check (30 min) relies on, so it must never stop.
 */
metadata {
    definition(
        name: 'Athom Human Presence Sensor',
        namespace: 'esphome',
        author: 'Joshua Glemza',
        singleThreaded: true,
        importUrl: 'https://github.com/bradsjm/hubitat-public/raw/main/ESPHome/ESPHome-AthomHumanPresenceSensor.groovy') {

        capability 'Configuration'
        capability 'IlluminanceMeasurement'
        capability 'MotionSensor'
        capability 'Sensor'
        capability 'Refresh'
        capability 'Initialize'

        attribute 'distance', 'number'
        attribute 'mmwave', 'enum', [ 'active', 'not active' ]
        attribute 'pir', 'enum', [ 'active', 'not active' ]

        // attribute populated by ESPHome API Library automatically
        attribute 'networkStatus', 'enum', [ 'connecting', 'online', 'offline' ]

        command 'restart'
    }

    preferences {
        input name: 'ipAddress',    // required setting for API library
            type: 'text',
            title: '<b>Device IP Address</b>',
            required: true

        input name: 'password',     // optional setting for API library
            type: 'text',
            title: '<b>Device Password</b>',
            description: '<i>(if required)</i>',
            required: false

        input name: 'illumDeadbandLux', type: 'number', title: '<b>Illuminance deadband (lux)</b>', defaultValue: 5, description: \
             '<i>A reading becomes an event when it moves this much, or the percentage below, whichever is larger (0 = every reading).</i>'

        input name: 'illumDeadbandPct', type: 'number', title: '<b>Illuminance deadband (%)</b>', defaultValue: 10

        input name: 'illumHeartbeatMinutes', type: 'number', title: '<b>Illuminance heartbeat (minutes)</b>', defaultValue: 10, description: \
             '<i>Send the reading anyway after this long, so apps that watch for a silent sensor still see it alive.</i>'

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true, description: \
             '<i>Enables command logging.</i>'

        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false, description: \
             '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

void configure() {

}

void initialize() {
    // API library command to open socket to device, it will automatically reconnect if needed
    openSocket()

    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

void installed() {
    log.info "${device} driver installed"
}

void logsOff() {
    espHomeSubscribeLogs(LOG_LEVEL_INFO, false) // disable device logging
    device.updateSetting('logEnable', false)
    log.info "${device} debug logging disabled"
}

void refresh() {
    log.info "${device} refresh"
    state.clear()
    state.entities = [:]
    state.requireRefresh = true
    espHomeDeviceInfoRequest()
}

void restart() {
    log.info "${device} restart"
    espHomeButtonCommand([ key: state.entities['restart'] as Long ])
}

void updated() {
    log.info "${device} driver configuration updated"
    initialize()
    runIn(5, 'configure')
}

void uninstalled() {
    closeSocket('driver uninstalled') // make sure the socket is closed when uninstalling
    log.info "${device} driver uninstalled"
}

// the parse method is invoked by the API library when messages are received
void parse(final Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'device':
            // Device information
            break

        case 'entity':
            parseKeys(message)
            break

        case 'state':
            parseState(message)
    }
}

void parseKeys(final Map message) {
    if (logEnable) { log.debug "ESPHome entity: ${message}" }
    if (state.entities == null) { state.entities = [:] }
    final long key = message.key as long

    switch (message.objectId) {
        case 'light_sensor':
            // Illuminance Sensor
            state.entities['illuminance'] = key
            break
        case 'occupancy':
            // Occupancy Sensor
            state.entities['occupancy'] = key
            break
        case 'pir_sensor':
            // Passive Infrared Sensor
            state.entities['pir'] = key
            break
        case 'status_led':
            // ESP32 Status LED
            state.entities['status_led'] = key
            break
        case 'farthest_detection':
            // Millimeter wave radar sensor distance
            state.entities['mmwave_distance'] = key
            break
        case 'fading_time':
            // Millimeter wave radar sensor off latency
            state.entities['mmwave_off_latency'] = key
            break
        case 'detection_delay':
            // Millimeter wave radar sensor on latency
            state.entities['mmwave_on_latency'] = key
            break
        case 'trigger_sensitivity':
            // Millimeter wave radar sensor sensitivity
            state.entities['mmwave_sensitivity'] = key
            break
        case 'mmwave_sensor':
            // Millimeter wave radar sensor switch
            state.entities['mmwave_switch'] = key
            break
        case 'uart_presence_output':
        case 'uart_target_output':
            // ignore
            break
        default:
            log.warn "ESPHome entity not supported: ${message}"
            break
    }
}

void parseState(final Map message) {
    if (logEnable) { log.debug "ESPHome state: ${message}" }
    if (message.key == null) { return }
    final long key = message.key as long

    switch (key) {
        case state.entities['illuminance']:
            // Illuminance Sensor
            if (message.hasState) {
                updateIlluminance(message.state as Integer)
            }
            break
        case state.entities['mmwave_switch']:
            // Millimeter wave radar sensor
            if (message.hasState) {
                updateAttribute('mmwave', message.state ? 'active' : 'inactive')
            }
            break
        case state.entities['occupancy']:
            // Combined Millimeter wave radar and PIR
            if (message.hasState) {
                updateAttribute('motion', message.state ? 'active' : 'inactive')
            }
            break
        case state.entities['pir']:
            // PIR sensor
            if (message.hasState) {
                updateAttribute('pir', message.state ? 'active' : 'inactive')
            }
            break
        case state.entities['status_led']:
            // ESP32 Status LED
            if (message.hasState) {
                log.info "ESP32 Status LED: ${message.state}"
                device.updateSetting('statusLedEnable', message.state)
            }
            break
        case state.entities['mmwave_distance']:
            // Millimeter wave radar sensor distance
            if (message.hasState) {
                log.info "Millimeter wave radar sensor distance: ${message.state}"
                device.updateSetting('mmwaveDistance', message.state)
            }
            break
        case state.entities['mmwave_off_latency']:
            // Millimeter wave radar sensor off latency
            if (message.hasState) {
                log.info "Millimeter wave radar sensor off latency: ${message.state}"
                device.updateSetting('mmwaveOffLatency', message.state)
            }
            break
        case state.entities['mmwave_on_latency']:
            // Millimeter wave radar sensor on latency
            if (message.hasState) {
                log.info "Millimeter wave radar sensor on latency: ${message.state}"
                device.updateSetting('mmwaveOnLatency', message.state)
            }
            break
        case state.entities['mmwave_sensitivity']:
            // Millimeter wave radar sensor sensitivity
            if (message.hasState) {
                log.info "Millimeter wave radar sensor sensitivity: ${message.state}"
                device.updateSetting('mmwaveSensitivity', message.state)
            }
            break
    }
}

/**
 * Illuminance with a deadband and a heartbeat (local change, see the header). The heartbeat event is forced as a state
 * change so it counts as activity even when the value has not moved.
 */
private void updateIlluminance(final Integer lux) {
    if (lux == null) { return }
    final Integer last = device.currentValue('illuminance') as Integer
    final long lastAt = (state.illumAt ?: 0L) as long
    final long beatMs = ((settings.illumHeartbeatMinutes ?: 10) as long) * 60000L
    final BigDecimal bandLux = (settings.illumDeadbandLux != null ? settings.illumDeadbandLux : 5) as BigDecimal
    final BigDecimal bandPct = (settings.illumDeadbandPct != null ? settings.illumDeadbandPct : 10) as BigDecimal
    final BigDecimal band = [bandLux, (last ?: 0) * bandPct / 100].max()
    final boolean moved = last == null || Math.abs(lux - last) >= band
    final boolean beat = now() - lastAt >= beatMs
    if (!moved && !beat) { return }
    state.illumAt = now()
    final String descriptionText = "illuminance was set to ${lux}lx"
    if (moved && last != lux && settings.txtEnable) {
        log.info descriptionText
    }
    final Map evt = [name: 'illuminance', value: lux, unit: 'lx', descriptionText: descriptionText]
    if (!moved) { evt.isStateChange = true }
    sendEvent(evt)
}

/**
 * Check if the specified value is null or empty
 * @param value value to check
 * @return true if the value is null or empty, false otherwise
 */
private static boolean isNullOrEmpty(final Object value) {
    return value == null || (value as String).trim().isEmpty()
}

/**
 * Update the specified device attribute with the specified value and log if changed
 * @param attribute name of the attribute
 * @param value value of the attribute
 * @param unit unit of the attribute
 * @param type type of the attribute
 */
private void updateAttribute(final String attribute, final Object value, final String unit = null, final String type = null) {
    final String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (device.currentValue(attribute) != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
}

// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper
