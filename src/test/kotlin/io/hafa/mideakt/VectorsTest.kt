package io.hafa.mideakt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-validates mideakt against the golden vectors in
 * src/test/resources/vectors.json, which were generated from the canonical
 * Python reference (msmart).
 */
class VectorsTest {
    private val vectors: JsonElement = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/vectors.json")!!.bufferedReader().use { it.readText() },
    )

    private operator fun JsonElement.get(key: String): JsonElement = jsonObject.getValue(key)
    private val JsonElement.str: String get() = jsonPrimitive.content
    private val JsonElement.num: Int get() = jsonPrimitive.int
    private val JsonElement.dbl: Double get() = jsonPrimitive.double
    private val JsonElement.bool: Boolean get() = jsonPrimitive.boolean

    // Compare header+body, ignoring the trailing message-id/CRC/checksum (3 bytes)
    // which depend on a process-global message-id counter.
    private fun body(frame: ByteArray): List<Byte> = frame.dropLast(3)

    @Test
    fun encKey() {
        assertEquals(vectors["enc_key"].str, Security.ENC_KEY.toHex())
    }

    @Test
    fun crc8() {
        val crc = vectors["crc8"]
        assertEquals(crc["expected"].num, CRC8.calculate(crc["input"].str.hexToBytes()))
    }

    @Test
    fun getStateFrame() {
        assertEquals(body(vectors["get_state_frame"].str.hexToBytes()), body(Command.getState()))
    }

    @Test
    fun toggleDisplayFrame() {
        assertEquals(body(vectors["toggle_display_frame"].str.hexToBytes()), body(Command.toggleDisplay()))
    }

    @Test
    fun setStateFrame() {
        val setState = vectors["set_state"]
        val p = setState["params"]
        val state = ACState(
            powerOn = p["power_on"].bool,
            targetTemperature = p["target_temperature"].dbl,
            mode = p["operational_mode"].num,
            fanSpeed = p["fan_speed"].num,
            indoorTemperature = null, outdoorTemperature = null,
            swingMode = p["swing_mode"].num,
            eco = p["eco"].bool, turbo = p["turbo"].bool, sleep = p["sleep"].bool,
            fahrenheit = p["fahrenheit"].bool, purifier = p["purifier"].bool,
            displayOn = true, filterAlert = false, freezeProtection = p["freeze_protection"].bool,
            targetHumidity = p["target_humidity"].num,
        )
        val command = SetState(state).apply { beep = p["beep"].bool }
        assertEquals(body(setState["frame"].str.hexToBytes()), body(command.encode()))
    }

    @Test
    fun udpid() {
        val u = vectors["udpid"]
        val id = u["device_id"].jsonPrimitive.long
        assertEquals(u["little"].str, UDPID.compute(id, false))
        assertEquals(u["big"].str, UDPID.compute(id, true))
    }

    @Test
    fun stateResponse() {
        val response = vectors["state_response"]
        val parsed = response["parsed"]
        val state = ACState.parse(response["frame"].str.hexToBytes())!!
        assertEquals(parsed["power_on"].bool, state.powerOn)
        assertEquals(parsed["target_temperature"].dbl, state.targetTemperature)
        assertEquals(parsed["operational_mode"].num, state.mode)
        assertEquals(parsed["fan_speed"].num, state.fanSpeed)
        assertEquals(parsed["indoor_temperature"].dbl, state.indoorTemperature)
        assertEquals(parsed["outdoor_temperature"].dbl, state.outdoorTemperature)
        assertEquals(parsed["display_on"].bool, state.displayOn)
    }
}
