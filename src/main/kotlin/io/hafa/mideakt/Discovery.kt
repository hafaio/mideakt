package io.hafa.mideakt

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A device found on the LAN, with the addressing and protocol version needed to connect. */
data class DiscoveredDevice(
    val id: Long,
    val ip: String,
    val port: Int,
    val version: Int,
    val name: String,
    val serialNumber: String?,
)

/** LAN discovery: broadcast the Midea probe and parse V2/V3 replies. */
object Discovery {
    private const val PROBE_HEX =
        "5a5a01114800920000000000000000000000000000000000000000000000000000000000000000" +
            "007f75bd6b3e4f8b762e849c6e578d6590036e9d4342a50f1f569eb8ec918e92e5"
    private val PORTS = intArrayOf(6445, 20086)

    /** Broadcasts the discovery probe and returns the responding devices, deduplicated by id. */
    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements")
    fun discover(timeoutMs: Int = 4000): List<DiscoveredDevice> {
        val probe = PROBE_HEX.hexToBytes()
        return DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 300

            val targets = broadcastAddresses().toMutableSet()
            targets.add(InetAddress.getByName("255.255.255.255"))
            for (target in targets) {
                for (port in PORTS) {
                    try { socket.send(DatagramPacket(probe, probe.size, target, port)) } catch (_: Exception) {}
                }
            }

            val found = LinkedHashMap<Long, DiscoveredDevice>()
            val buffer = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                val device = parse(buffer.copyOf(packet.length), packet.address.hostAddress)
                if (device != null && !found.containsKey(device.id)) found[device.id] = device
            }
            found.values.toList()
        }
    }

    @Suppress("ReturnCount")
    private fun parse(data: ByteArray, ip: String): DiscoveredDevice? {
        if (data.size < 42) return null
        val version: Int
        val body: ByteArray
        if (data[0].toInt() and 0xFF == 0x83 && data[1].toInt() and 0xFF == 0x70) {
            version = 3
            body = data.copyOfRange(8, data.size - 16)
        } else if (data[0].toInt() and 0xFF == 0x5A && data[1].toInt() and 0xFF == 0x5A) {
            version = 2
            body = data
        } else {
            return null
        }
        if (body.size < 56) return null

        val deviceId = leLong(body, 20, 6)
        val encrypted = body.copyOfRange(40, body.size - 16)
        val decrypted = try { Crypto.decryptECB(Security.ENC_KEY, encrypted) } catch (_: Exception) { return null }
        if (decrypted.size < 41) return null

        val port = (decrypted[4].toInt() and 0xFF) or ((decrypted[5].toInt() and 0xFF) shl 8)
        // The serial sits in a fixed 32-byte field padded with NULs or spaces; a field that
        // holds nothing but padding means the device reported no serial.
        val serial = String(decrypted, 8, 32, Charsets.US_ASCII).trim { it <= ' ' }.ifEmpty { null }
        val nameLength = decrypted[40].toInt() and 0xFF
        val nameEnd = minOf(41 + nameLength, decrypted.size)
        val name = String(decrypted, 41, nameEnd - 41, Charsets.UTF_8)

        return DiscoveredDevice(deviceId, ip, port, version, name, serial)
    }

    private fun leLong(bytes: ByteArray, offset: Int, count: Int): Long {
        var value = 0L
        for (i in 0 until count) value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        for (iface in NetworkInterface.getNetworkInterfaces()) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in iface.interfaceAddresses) {
                address.broadcast?.let { result.add(it) }
            }
        }
        return result
    }
}
