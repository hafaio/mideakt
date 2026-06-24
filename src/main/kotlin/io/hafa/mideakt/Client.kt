package io.hafa.mideakt

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level local control of one air conditioner over a persistent
 * authenticated connection. Blocking — call from a background thread or
 * coroutine, and don't interleave calls on one instance (not thread-safe).
 *
 * Construct it from the [DeviceCredentials] that [Setup] returns, then reuse the
 * instance. It is [AutoCloseable], so prefer `use { }`:
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
        credentials.ip, credentials.port, credentials.id,
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
        val fresh = MideaConnection(host, port, deviceId)
        try {
            fresh.connect(connectTimeoutMs, readTimeoutMs)
            fresh.authenticate(token, key)
            warmUp(fresh)
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

    /** `retry` should be false for non-idempotent commands (relative toggle). */
    private fun <T> withConnection(retry: Boolean = true, op: (MideaConnection) -> T): T {
        check(inUse.compareAndSet(false, true)) {
            "MideaClient accessed concurrently; it is not thread-safe — serialize access (e.g. behind a Mutex)."
        }
        try {
            ensureConnected()
            return try {
                op(connection!!)
            } catch (e: IOException) {
                // Only transport faults are worth a reconnect; protocol/auth errors rethrow as-is.
                disconnect()
                if (!retry) throw e
                ensureConnected()
                op(connection!!)
            }
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
