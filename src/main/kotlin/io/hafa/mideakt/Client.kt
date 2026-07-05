package io.hafa.mideakt

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level local control of one air conditioner over a persistent
 * authenticated connection. Blocking — call from a background thread or
 * coroutine, and don't interleave calls on one instance (not thread-safe).
 *
 * Construct it from the [DeviceCredentials] that [Setup] returns, then reuse the
 * instance. It is [AutoCloseable], so prefer `use { }`:
 *
 * Protocol V3 uses a token/key handshake; version-2 devices use an unauthenticated
 * `0x5A5A` transport with no handshake.
 *
 * ```kotlin
 * val credentials = Setup.run().first()
 * MideaClient(credentials).use { client ->
 *     val state = client.refresh()
 *     println("${state.targetTemperature}°C ${OperationalMode.fromRaw(state.mode)}")
 *
 *     client.update { set ->
 *         set.powerOn = true
 *         set.targetTemperature = 22.0
 *         set.mode = OperationalMode.COOL.raw
 *     }
 * }
 * ```
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
class MideaClient internal constructor(
    private val host: String,
    private val port: Int,
    private val deviceId: Long,
    private val version: Int,
    private val token: ByteArray,
    private val key: ByteArray,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : AutoCloseable {
    constructor(
        credentials: DeviceCredentials,
        connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ) : this(
        credentials.ip, credentials.port, credentials.id, credentials.version,
        credentials.token.hexToBytes(), credentials.key.hexToBytes(),
        connectTimeoutMs, readTimeoutMs,
    )

    private var connection: MideaConnection? = null
    private var authenticatedAt = 0L
    private val sessionLifetimeMs = 11L * 3600 * 1000

    // Guards against concurrent use of the single, non-thread-safe connection.
    private val inUse = AtomicBoolean(false)

    // A freshly authenticated device needs a brief delay before it answers queries.
    private val warmUpFloorMs = 200L
    private val warmUpPollMs = 40L
    private val warmUpCeilingMs = 1200L

    // The module frees its single connection slot lazily; reconnecting inside the same
    // second lands on a slot it still holds, so a retry waits this long first.
    private val reconnectCooldownMs = 1000L

    /** Closes the underlying connection; a later call reconnects automatically. */
    fun disconnect() {
        connection?.disconnect()
        connection = null
        authenticatedAt = 0
    }

    /** Queries and returns the device's current state. */
    fun refresh(): ACState = withConnection {
        it.sendApplicationFrame(Command.getState())
        readState(it)
    }

    /**
     * Toggles the LED display (beeping unless [beep] is false) and returns the
     * resulting state. This is a relative toggle, so — unlike the other calls — a
     * lost reply is not retried, since a retry would toggle it back.
     */
    fun toggleDisplay(beep: Boolean = true): ACState = withConnection(retry = false) {
        it.sendApplicationFrame(Command.toggleDisplay(beep))
        readState(it)
    }

    /**
     * Applies changes to the device: [change] mutates a [SetState] seeded from the
     * current state, which is then sent. Returns the resulting state. Only the fields
     * you touch change; the rest carry over from the current state.
     *
     * ```kotlin
     * client.update { set ->
     *     set.powerOn = true
     *     set.targetTemperature = 21.5
     *     set.fanSpeed = FanSpeed.AUTO.raw
     * }
     * ```
     */
    fun update(change: (SetState) -> Unit): ACState = withConnection {
        it.sendApplicationFrame(Command.getState())
        val current = readState(it)
        val set = SetState(current)
        change(set)
        it.sendApplicationFrame(set.encode())
        readState(it)
    }

    private fun ensureConnected() {
        if (connection != null && System.currentTimeMillis() - authenticatedAt < sessionLifetimeMs) return
        disconnect()
        val fresh = MideaConnection(host, port, deviceId, version)
        try {
            fresh.connect(connectTimeoutMs, readTimeoutMs)
            if (version >= 3) {
                // V2 has no handshake, so no post-auth warm-up either.
                fresh.authenticate(token, key)
                warmUp(fresh)
            }
        } catch (e: Exception) {
            fresh.disconnect()  // don't leak the half-open socket
            throw e
        }
        connection = fresh
        authenticatedAt = System.currentTimeMillis()
    }

    /**
     * Sends a throwaway getState after auth and waits up to [warmUpCeilingMs] for
     * the reply, consuming it, so the device is responsive before the first real
     * call. getState is idempotent, so the probe is harmless if the device drops it.
     */
    private fun warmUp(connection: MideaConnection) {
        Thread.sleep(warmUpFloorMs)
        try {
            connection.sendApplicationFrame(Command.getState())
        } catch (_: Exception) {
            return
        }
        val deadline = System.currentTimeMillis() + warmUpCeilingMs
        while (System.currentTimeMillis() < deadline) {
            if (connection.bytesAvailable() > 0) {
                runCatching { connection.readApplicationFrame() }  // consume the probe reply
                return
            }
            Thread.sleep(warmUpPollMs)
        }
    }

    /** Consumes and discards any complete frames already buffered, so each op reads its own reply. */
    private fun drainStaleFrames(connection: MideaConnection) {
        while (connection.bytesAvailable() > 0) {
            try {
                connection.readApplicationFrame()
            } catch (_: Exception) {
                return
            }
        }
    }

    /**
     * `retry` should be false for non-idempotent commands (relative toggle). Only transport
     * faults are retried; a timeout is surfaced immediately, since the device is present but
     * slow and a reconnect can't change the outcome. The retry reconnects after a short
     * cool-down so it doesn't land on the module's still-held slot.
     */
    @Suppress("ThrowsCount")
    private fun <T> withConnection(retry: Boolean = true, op: (MideaConnection) -> T): T {
        check(inUse.compareAndSet(false, true)) {
            "MideaClient accessed concurrently; it is not thread-safe — serialize access (e.g. behind a Mutex)."
        }
        try {
            ensureConnected()
            drainStaleFrames(connection!!)
            try {
                return op(connection!!)
            } catch (e: SocketTimeoutException) {
                // A timeout means the device is present but slow, so a reconnect can't change the
                // outcome — surface it (dropping the possibly mid-frame stream) and let the caller's
                // own retry ladder pace another try.
                disconnect()
                throw e
            } catch (e: IOException) {
                // Transport fault: reconnect and try once more, unless the caller opted out.
                disconnect()
                if (!retry) throw e
                // The module frees its single slot lazily, so reconnecting inside the same second
                // lands on a held slot; wait it out before trying again.
                Thread.sleep(reconnectCooldownMs)
                ensureConnected()
                drainStaleFrames(connection!!)
                return op(connection!!)
            }
        } catch (e: Exception) {
            // Any error escaping here — a protocol/auth desync, or a failed retry — may have left
            // the stream mid-frame; drop the connection so the next call reconnects on a clean one.
            disconnect()
            throw e
        } finally {
            inUse.set(false)
        }
    }

    private fun readState(connection: MideaConnection): ACState {
        repeat(4) {
            ACState.parse(connection.readApplicationFrame())?.let { return it }
        }
        throw ProtocolException("no state response")
    }

    /** Releases the persistent connection; [AutoCloseable] so callers can `use { }`. */
    override fun close() = disconnect()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 6000
    }
}
