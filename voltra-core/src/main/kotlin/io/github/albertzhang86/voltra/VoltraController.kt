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

    suspend fun createTwin(controllerId: String, peerId: String)

    suspend fun exitTwin()

    /** Establish an app set boundary without sending a load/unload command to the motor. */
    fun beginSet()

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

    override fun close()
}
