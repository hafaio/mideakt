package io.hafa.mideakt

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/**
 * Blocking TCP transport implementing the Midea V3 "8370" framing and key
 * handshake. Call from a background thread/coroutine; not thread-safe.
 */
@Suppress("TooManyFunctions")
internal class MideaConnection(
    private val host: String,
    private val port: Int,
    private val deviceId: Long,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var packetId = 0
    private var localKey: ByteArray? = null

    fun connect(connectTimeoutMs: Int = 6000, readTimeoutMs: Int = 6000) {
        val newSocket = Socket()
        newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        newSocket.soTimeout = readTimeoutMs
        socket = newSocket
        input = DataInputStream(newSocket.getInputStream())
        output = newSocket.getOutputStream()
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null; localKey = null
    }

    fun authenticate(token: ByteArray, key: ByteArray) {
        writeRaw(encodeHandshake(token))
        val data = process(readPacket())
        if (data.size != 64) throw ProtocolException("invalid handshake length")
        val payload = data.copyOfRange(0, 32)
        val receivedHash = data.copyOfRange(32, 64)
        val decrypted = Crypto.decryptCBC(key, payload)
        if (!Crypto.sha256(decrypted).contentEquals(receivedHash)) {
            throw ProtocolException("handshake hash mismatch")
        }
        localKey = Crypto.xor(decrypted, key)
    }

    fun sendApplicationFrame(frame: ByteArray) {
        writeRaw(encodeEncrypted(V2Packet.encode(deviceId, frame)))
    }

    fun readApplicationFrame(): ByteArray = V2Packet.decode(process(readPacket()))

    /** Bytes already received and waiting to be read, without blocking. */
    fun bytesAvailable(): Int = input?.available() ?: 0

    private fun nextPacketId(): Int {
        val id = packetId
        packetId = (packetId + 1) and 0xFFF
        return id
    }

    private fun be16(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun encodeHandshake(token: ByteArray): ByteArray {
        val id = nextPacketId()
        val header = byteArrayOf(0x83.toByte(), 0x70) + be16(token.size) + byteArrayOf(0x20, 0x00)
        return header + be16(id) + token
    }

    private fun encodeEncrypted(data: ByteArray): ByteArray {
        val key = localKey ?: throw ProtocolException("not authenticated")
        val id = nextPacketId()
        val remainder = (data.size + 2) % 16
        val pad = if (remainder != 0) 16 - remainder else 0
        val length = data.size + pad + 32
        val header = byteArrayOf(0x83.toByte(), 0x70) + be16(length) +
            byteArrayOf(0x20, ((pad shl 4) or 0x06).toByte())
        val payload = be16(id) + data + Random.nextBytes(pad)
        val hash = Crypto.sha256(header + payload)
        return header + Crypto.encryptCBC(key, payload) + hash
    }

    @Suppress("ThrowsCount")
    private fun process(packet: ByteArray): ByteArray {
        if (packet.size < 6 || packet[0].toInt() and 0xFF != 0x83 || packet[1].toInt() and 0xFF != 0x70) {
            throw ProtocolException("bad start of packet")
        }
        if (packet[4].toInt() and 0xFF != 0x20) throw ProtocolException("bad magic byte")
        return when (packet[5].toInt() and 0xF) {
            0x1 -> packet.copyOfRange(8, packet.size)  // handshake: 6 header + 2 packet id
            0x3 -> {
                val key = localKey ?: throw ProtocolException("not authenticated")
                val header = packet.copyOfRange(0, 6)
                val encrypted = packet.copyOfRange(6, packet.size - 32)
                val receivedHash = packet.copyOfRange(packet.size - 32, packet.size)
                val decrypted = Crypto.decryptCBC(key, encrypted)
                if (!Crypto.sha256(header + decrypted).contentEquals(receivedHash)) {
                    throw ProtocolException("response hash mismatch")
                }
                val pad = (header[5].toInt() and 0xFF) shr 4
                if (decrypted.size < 2 + pad) throw ProtocolException("short packet")
                decrypted.copyOfRange(2, decrypted.size - pad)
            }
            0xF -> throw ProtocolException("error packet received")
            else -> throw ProtocolException("unexpected packet type")
        }
    }

    private fun readPacket(): ByteArray {
        val stream = input ?: throw ProtocolException("not connected")
        val header = ByteArray(6)
        stream.readFully(header)
        if (header[0].toInt() and 0xFF != 0x83 || header[1].toInt() and 0xFF != 0x70) {
            throw ProtocolException("bad start of packet in stream")
        }
        val total = (((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)) + 8
        val rest = ByteArray(total - 6)
        stream.readFully(rest)
        return header + rest
    }

    private fun writeRaw(data: ByteArray) {
        val stream = output ?: throw ProtocolException("not connected")
        stream.write(data)
        stream.flush()
    }
}
