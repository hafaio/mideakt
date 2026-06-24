package io.hafa.mideakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MideaktTest {
    @Test
    fun targetTemperatureClampedOnEncode() {
        fun state(temperature: Double) = SetState(
            powerOn = true, targetTemperature = temperature, mode = OperationalMode.COOL.raw,
            fanSpeed = FanSpeed.AUTO.raw, eco = false, swingMode = 0, turbo = false,
            sleep = false, fahrenheit = false, purifier = false, targetHumidity = 40,
            freezeProtection = false,
        )
        fun diff(left: ByteArray, right: ByteArray): Set<Int> =
            left.indices.filter { left[it] != right[it] }.toSet()

        // Two encodings of one setpoint differ only in the rolling message id and its checksums.
        val idOnlyDiff = diff(state(30.0).encode(), state(30.0).encode())
        // Over-range clamps to 30 °C; under-range to 17 °C — neither adds further byte differences.
        assertEquals(idOnlyDiff, diff(state(60.0).encode(), state(30.0).encode()))
        assertEquals(idOnlyDiff, diff(state(-5.0).encode(), state(17.0).encode()))
    }

    @Test
    fun aesEcbRoundTrip() {
        val key = Security.ENC_KEY
        val data = "the quick brown fox".toByteArray()
        assertTrue(Crypto.decryptECB(key, Crypto.encryptECB(key, data)).contentEquals(data))
    }

    @Test
    fun aesCbcRoundTrip() {
        val key = ByteArray(32) { 0xAB.toByte() }
        val data = ByteArray(32) { 0x11 }
        assertTrue(Crypto.decryptCBC(key, Crypto.encryptCBC(key, data)).contentEquals(data))
    }

    @Test
    fun v2PacketRoundTrip() {
        val command = Command.getState()
        val packet = V2Packet.encode(1108152157446L, command)
        assertTrue(V2Packet.decode(packet).contentEquals(command))
    }
}
