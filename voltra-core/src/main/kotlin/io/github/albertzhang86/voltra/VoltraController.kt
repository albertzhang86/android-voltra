package io.github.albertzhang86.voltra

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-free owner of up to two trainers. Setting changes suspend until device confirmation or throw.
 * connect starts a connection; observe state for readiness. Lifecycle calls use the owner
 * dispatcher.
 */
interface VoltraController : AutoCloseable {
    val state: StateFlow<VoltraState>

    /** Display one operation across its reconnects. Close on success, failure or cancellation. */
    fun beginSetupActivity(activity: SetupActivity): AutoCloseable = AutoCloseable {}

    fun scan(): Flow<List<VoltraDevice>>

    suspend fun connect(device: VoltraDevice)

    fun disconnect(deviceId: String)

    /**
     * Disconnect and discard this client's remembered device and readings. Does not change load,
     * hardware Twin mode, or the operating system's Bluetooth bond. A knob route can forget the
     * knob itself; trainer identities owned by the knob must be managed on the knob.
     */
    fun forget(deviceId: String) = disconnect(deviceId)

    suspend fun setMode(deviceId: String, mode: TrainingMode)

    suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int)

    /**
     * Explicit motor operation. Returns only after fresh device confirmation; never retried or
     * implied by beginSet/endSet. Unsupported connectors reject actual motor changes.
     */
    suspend fun setLoaded(deviceId: String, loaded: Boolean) {
        throw UnsupportedOperationException(
            "This connector does not support confirmed load control."
        )
    }

    suspend fun createTwin(controllerId: String, peerId: String)

    suspend fun exitTwin()

    /** Explicit user-authorized Twin action: confirm required unloading, then change topology. */
    suspend fun createTwinAfterUnloading(
        controllerId: String,
        peerId: String,
        stillRelevant: () -> Boolean = { true },
    ) = prepareTwinChange(this, controllerId, peerId, create = true, stillRelevant)

    /** Never loads either trainer. Unknown state or a failed STOP prevents the Twin command. */
    suspend fun exitTwinAfterUnloading(stillRelevant: () -> Boolean = { true }) =
        prepareTwinChange(this, null, null, create = false, stillRelevant)

    /** Establish an app set boundary without sending a load/unload command to the motor. */
    fun beginSet()

    /**
     * Notify the connector that the app set finished. This is a lifecycle hook, never a motor
     * load/unload command. Real connectors need no action; demos may stop their simulated set.
     */
    fun endSet() = Unit

    override fun close()
}

/** Injectable transport boundary for Android, replay tests, and future knob bridges. */
interface VoltraTransport {
    fun scan(): Flow<List<VoltraDevice>>

    suspend fun connect(device: VoltraDevice): VoltraLink
}

interface VoltraLink : AutoCloseable {
    val packets: Flow<ByteArray>

    suspend fun write(frame: ByteArray)

    /** Revoke an authenticated command session where supported, then close the connection. */
    suspend fun revoke() = close()

    override fun close()
}
