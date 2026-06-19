package io.hafa.mideakt

import kotlin.test.Test
import kotlin.test.assertTrue

class MideaktTest {
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
