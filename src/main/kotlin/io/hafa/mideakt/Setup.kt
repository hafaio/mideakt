package io.hafa.mideakt

/** Thrown when [Setup.run] cannot complete, e.g. no devices are found on the LAN. */
class SetupException(message: String) : Exception(message)

/**
 * One-shot setup: discover devices, fetch each V3 device's token/key from the
 * cloud, verify by authenticating locally, and return credentials. The only step
 * that contacts the cloud.
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
    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements")
    fun run(cloud: NetHomePlusCloud): List<DeviceCredentials> {
        val devices = Discovery.discover()
        if (devices.isEmpty()) throw SetupException("No devices found")

        cloud.login()

        val results = mutableListOf<DeviceCredentials>()
        for (device in devices) {
            if (device.version != 3) {
                results.add(DeviceCredentials(device.name, device.id, device.ip, device.port, device.version, "", ""))
                continue
            }
            for (bigEndian in listOf(false, true)) {
                val udpid = UDPID.compute(device.id, bigEndian)
                val pair = try { cloud.getToken(udpid) } catch (_: Exception) { continue }
                val authenticated = try {
                    MideaClient(
                        device.ip, device.port, device.id,
                        pair.first.hexToBytes(), pair.second.hexToBytes(),
                    ).use { it.refresh() }  // verifies the token/key authenticate
                    true
                } catch (_: Exception) {
                    false
                }
                if (authenticated) {
                    results.add(
                        DeviceCredentials(
                            device.name, device.id, device.ip, device.port,
                            device.version, pair.first, pair.second,
                        ),
                    )
                    break
                }
            }
        }
        if (results.isEmpty()) throw SetupException("Devices found, but none could be provisioned")
        return results
    }
}
