package io.hafa.mideakt

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/** AC operating modes. [raw] is the byte exchanged with the device; map one back with [fromRaw]. */
enum class OperationalMode(val raw: Int) {
    AUTO(1), COOL(2), DRY(3),
    HEAT(4), FAN_ONLY(5), SMART_DRY(6);

    companion object {
        /** The mode with this on-wire [raw] value, or null if none matches. */
        fun fromRaw(raw: Int): OperationalMode? = entries.firstOrNull { it.raw == raw }
    }
}

/** Fan speeds. [raw] is the byte exchanged with the device; map one back with [fromRaw]. */
enum class FanSpeed(val raw: Int) {
    SILENT(20), LOW(40), MEDIUM(60),
    HIGH(80), AUTO(102), MAX(100);

    companion object {
        /** The fan speed with this on-wire [raw] value, or null if none matches. */
        fun fromRaw(raw: Int): FanSpeed? = entries.firstOrNull { it.raw == raw }
    }
}

private val messageId = AtomicInteger(0)

private fun buildCommand(type: Frame.Type, body: ByteArray): ByteArray {
    val withId = body + byteArrayOf((messageId.incrementAndGet() and 0xFF).toByte())
    val withCrc = withId + byteArrayOf(CRC8.calculate(withId).toByte())
    return Frame.build(type, withCrc)
}

internal object Command {
    fun getState(): ByteArray = buildCommand(
        Frame.Type.QUERY,
        intArrayOf(
            0x41, 0x81, 0x00, 0xFF, 0x03, 0xFF, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x03,
        ).toByteArray(),
    )

    fun toggleDisplay(beep: Boolean = true): ByteArray = buildCommand(
        Frame.Type.QUERY,
        intArrayOf(
            0x41, 0x02 or (if (beep) 0x40 else 0), 0x00, 0xFF, 0x02,
            0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ).toByteArray(),
    )
}

private fun IntArray.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

/** The full settable state; start from an [ACState] and override fields. */
@ConsistentCopyVisibility
data class SetState internal constructor(
    /** Whether the unit chimes to acknowledge the command. */
    var beep: Boolean = true,
    /** Whether the unit is powered on. */
    var powerOn: Boolean,
    /** Target temperature in °C, in 0.5° steps; clamped to 17–30 when sent. */
    var targetTemperature: Double,
    /** Operating mode; set from an [OperationalMode] value, e.g. `OperationalMode.COOL.raw`. */
    var mode: Int,
    /** Fan speed; set from a [FanSpeed] value, e.g. `FanSpeed.AUTO.raw`. Other values 0–127 are accepted. */
    var fanSpeed: Int,
    /** Energy-saving eco mode. */
    var eco: Boolean,
    /** Louver swing, as a bitmask: 0 = off, 3 = horizontal, 12 = vertical, 15 = both. */
    var swingMode: Int,
    /** Turbo/boost mode for maximum output. */
    var turbo: Boolean,
    /** Sleep mode (a gentler overnight temperature curve). */
    var sleep: Boolean,
    /** Show °F on the unit's panel; [targetTemperature] and the readings stay in °C regardless. */
    var fahrenheit: Boolean,
    /** Ionizer/air purifier (a.k.a. anion). */
    var purifier: Boolean,
    /** Target relative humidity in percent (0–100), used by the dry/smart-dry modes. */
    var targetHumidity: Int,
    /** 8 °C freeze-protection heating; only effective on units that support it. */
    var freezeProtection: Boolean,
) {
    internal constructor(state: ACState) : this(
        powerOn = state.powerOn,
        targetTemperature = state.targetTemperature,
        mode = state.mode,
        fanSpeed = state.fanSpeed,
        eco = state.eco,
        swingMode = state.swingMode,
        turbo = state.turbo,
        sleep = state.sleep,
        fahrenheit = state.fahrenheit,
        purifier = state.purifier,
        targetHumidity = state.targetHumidity ?: 40,
        freezeProtection = state.freezeProtection,
    )

    internal fun encode(): ByteArray {
        val beepByte = if (beep) 0x40 else 0
        val powerByte = if (powerOn) 0x01 else 0

        require(!targetTemperature.isNaN()) { "targetTemperature must not be NaN" }
        val clamped = targetTemperature.coerceIn(17.0, 30.0)
        val integral = floor(clamped).toInt()
        val hasHalf = clamped - integral > 0
        var temperature: Int
        val temperatureAlt: Int
        if (integral in 17..30) {
            temperature = (integral - 16) and 0xF
            temperatureAlt = 0
        } else {
            temperature = 0
            temperatureAlt = (integral - 12) and 0x1F
        }
        if (hasHalf) temperature = temperature or 0x10

        val modeByte = (mode and 0x7) shl 5
        val swingByte = 0x30 or (swingMode and 0x3F)
        val ecoByte = if (eco) 0x80 else 0
        val purifierByte = if (purifier) 0x20 else 0
        val sleepByte = if (sleep) 0x01 else 0
        val turboByte = if (turbo) 0x02 else 0
        val fahrenheitByte = if (fahrenheit) 0x04 else 0
        val turboAlt = if (turbo) 0x20 else 0
        val humidityByte = targetHumidity and 0x7F
        val freezeByte = if (freezeProtection) 0x80 else 0

        return buildCommand(
            Frame.Type.CONTROL,
            intArrayOf(
                0x40,
                0x02 or beepByte or powerByte,
                temperature or modeByte,
                fanSpeed,
                0x7F, 0x7F, 0x00,
                swingByte,
                turboAlt,
                ecoByte or purifierByte,
                sleepByte or turboByte or fahrenheitByte,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00,
                temperatureAlt,
                humidityByte,
                0x00,
                freezeByte,
                0x00,
                0x00,
            ).toByteArray(),
        )
    }
}

/** Parsed device state from a 0xC0 state response. */
data class ACState(
    /** Whether the unit is powered on. */
    val powerOn: Boolean,
    /** Target temperature in °C, in 0.5° steps; a device may report 13–43, wider than the 17–30 you can set. */
    val targetTemperature: Double,
    /** Operating mode (raw 0–7); resolve with [OperationalMode.fromRaw]. */
    val mode: Int,
    /** Fan speed (raw 0–127); resolve with [FanSpeed.fromRaw]. */
    val fanSpeed: Int,
    /** Measured indoor temperature in °C, or null if the unit doesn't report it. */
    val indoorTemperature: Double?,
    /** Measured outdoor temperature in °C, or null if the unit doesn't report it. */
    val outdoorTemperature: Double?,
    /** Louver swing, as a bitmask: 0 = off, 3 = horizontal, 12 = vertical, 15 = both. */
    val swingMode: Int,
    /** Whether energy-saving eco mode is on. */
    val eco: Boolean,
    /** Whether turbo/boost mode is on. */
    val turbo: Boolean,
    /** Whether sleep mode is on. */
    val sleep: Boolean,
    /** Whether the panel shows °F; the temperatures here are always °C regardless. */
    val fahrenheit: Boolean,
    /** Whether the ionizer/air purifier is on. */
    val purifier: Boolean,
    /** Whether the LED panel is lit; toggle it with [MideaClient.toggleDisplay]. */
    val displayOn: Boolean,
    /** Whether the unit is signalling that its filter needs cleaning. */
    val filterAlert: Boolean,
    /** Whether 8 °C freeze-protection heating is active. */
    val freezeProtection: Boolean,
    /** Target relative humidity in percent, or null if the unit doesn't support it. */
    val targetHumidity: Int?,
) {
    companion object {
        /** Parse a 0xAA frame into state; null if it's not a 0xC0 state response. */
        @Suppress("ReturnCount", "ThrowsCount")
        internal fun parse(frame: ByteArray): ACState? {
            if (frame.size < Frame.HEADER_LENGTH + 2) throw ProtocolException("frame too short")
            if (frame[0].toInt() and 0xFF != 0xAA || frame[2].toInt() and 0xFF != Frame.DEVICE_TYPE_AC) {
                throw ProtocolException("bad frame")
            }
            val expected = Frame.checksum(frame, 1, frame.size - 1)
            if (expected != frame[frame.size - 1].toInt() and 0xFF) throw ProtocolException("checksum mismatch")

            if (frame[10].toInt() and 0xFF != 0xC0) return null
            val p = IntArray(frame.size - 12) { frame[10 + it].toInt() and 0xFF }
            if (p.size < 16) return null  // fixed-offset reads go up to index 15

            val fahrenheit = (p[10] and 0x4) != 0
            var target = (p[2] and 0xF) + 16.0
            if (p[2] and 0x10 != 0) target += 0.5
            val altTarget = p[13] and 0x1F
            if (altTarget != 0) {
                target = altTarget + 12.0
                if (p[2] and 0x10 != 0) target += 0.5
            }

            return ACState(
                powerOn = (p[1] and 0x1) != 0,
                targetTemperature = target,
                mode = (p[2] shr 5) and 0x7,
                fanSpeed = p[3] and 0x7F,
                indoorTemperature = parseTemp(p[11], (p[15] and 0xF) / 10.0, fahrenheit),
                outdoorTemperature = parseTemp(p[12], (p[15] shr 4) / 10.0, fahrenheit),
                swingMode = p[7] and 0xF,
                eco = (p[9] and 0x10) != 0,
                turbo = (p[8] and 0x20) != 0 || (p[10] and 0x2) != 0,
                sleep = (p[10] and 0x1) != 0,
                fahrenheit = fahrenheit,
                purifier = (p[9] and 0x20) != 0,
                displayOn = p[14] != 0x70,
                filterAlert = (p[13] and 0x20) != 0,
                freezeProtection = p.size >= 22 && (p[21] and 0x80) != 0,
                targetHumidity = if (p.size >= 20) p[19] and 0x7F else null,
            )
        }

        @Suppress("ReturnCount")
        private fun parseTemp(raw: Int, decimals: Double, fahrenheit: Boolean): Double? {
            if (raw == 0xFF) return null
            val temperature = (raw - 50) / 2.0
            if (!fahrenheit && decimals != 0.0) {
                return temperature.toInt() + if (temperature >= 0) decimals else -decimals
            }
            if (decimals >= 0.5) {
                return temperature.toInt() + if (temperature >= 0) 0.5 else -0.5
            }
            return temperature
        }
    }
}
