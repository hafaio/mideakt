package io.hafa.mideakt

/** Thrown when [Setup.run] cannot complete, e.g. no devices are found on the LAN. */
class SetupException(message: String) : Exception(message)

/**
 * One-shot setup: discover devices, fetch each V3 device's token/key from the
 * cloud, verify by authenticating locally, and return credentials. The only step
 * that contacts the cloud.
 *
 * Version-2 devices need no cloud token; they are verified with a local query and
 * only returned if they respond.
 *
 * Defaults to a region's shared NetHome Plus community account; pass your own
 * account credentials for reliability.
 *
 * ```kotlin
 * // A region's shared community account:
 * val credentials = Setup.run().first()
 * // Or your own NetHome Plus account:
 * val credentials = Setup.run("you@example.com", "password").first()
 * ```
 *
 * Store the returned [DeviceCredentials] and hand them to [MideaClient]; setup only
 * needs to run again if a device is re-provisioned.
 */
object Setup {
    /** Runs setup using a region's shared community account; see [Setup]. */
    fun run(region: Region = Region.US): List<DeviceCredentials> =
        run(NetHomePlusCloud.forRegion(region))

    /** Runs setup using your own NetHome Plus account; see [Setup]. */
    fun run(account: String, password: String): List<DeviceCredentials> =
        run(NetHomePlusCloud(account, password))

    /** Runs setup against a pre-configured cloud client (e.g. a custom app id/key); see [Setup]. */
    fun run(cloud: NetHomePlusCloud): List<DeviceCredentials> {
        val devices = Discovery.discover()
        if (devices.isEmpty()) throw SetupException("No devices found")

        cloud.login()

        val results = devices.mapNotNull { device ->
            if (device.version >= 3) provisionV3(cloud, device) else provisionV2(device)
        }
        if (results.isEmpty()) throw SetupException("Devices found, but none could be provisioned")
        return results
    }

    /**
     * Verifies a version-2 device over its keyless transport, returning credentials
     * only if it answers a local query. V2 needs no cloud token.
     */
    private fun provisionV2(device: DiscoveredDevice): DeviceCredentials? {
        val responds = try {
            MideaClient(device.ip, device.port, device.id, device.version, ByteArray(0), ByteArray(0))
                .use { it.refresh() }
            true
        } catch (_: Exception) {
            false
        }
        return if (responds) {
            DeviceCredentials(device.name, device.id, device.ip, device.port, device.version, "", "")
        } else {
            null
        }
    }

    /**
     * Fetches a version-3 device's token/key from the cloud and verifies it locally. The
     * udpid byte order isn't discoverable, so it tries little-endian then big-endian and
     * returns the first pair that authenticates.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun provisionV3(cloud: NetHomePlusCloud, device: DiscoveredDevice): DeviceCredentials? {
        for (bigEndian in listOf(false, true)) {
            val udpid = UDPID.compute(device.id, bigEndian)
            val pair = try { cloud.getToken(udpid) } catch (_: Exception) { continue }
            val authenticated = try {
                MideaClient(
                    device.ip, device.port, device.id, device.version,
                    pair.first.hexToBytes(), pair.second.hexToBytes(),
                ).use { it.refresh() }  // verifies the token/key authenticate
                true
            } catch (_: Exception) {
                false
            }
            if (authenticated) {
                return DeviceCredentials(
                    device.name, device.id, device.ip, device.port, device.version, pair.first, pair.second,
                )
            }
        }
        return null
    }
}
