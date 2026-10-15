package io.github.albertzhang86.voltra.testing

import io.github.albertzhang86.voltra.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Explicit Twin UI demo through a knob. Never evidence of native hardware support. */
class SimulatedKnobController(parentScope: CoroutineScope) : VoltraController {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val delegate = SimulatedVoltraController(scope)
    override val state =
        delegate.state
            .map {
                it.copy(
                    viaKnob = true,
                    knob =
                        if (it.trainers.isEmpty()) null
                        else KnobState(DEVICE, ConnectionStatus.READY, 78),
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, VoltraState(viaKnob = true, isSimulated = true))

    override fun scan() = flowOf(listOf(DEVICE))

    override suspend fun connect(device: VoltraDevice) {
        require(device == DEVICE)
        delegate.connect(SimulatedVoltraController.devices.first())
        delegate.connect(SimulatedVoltraController.devices.last())
    }

    override fun disconnect(deviceId: String) {
        if (deviceId == DEVICE.id)
            SimulatedVoltraController.devices.forEach { delegate.disconnect(it.id) }
        else delegate.disconnect(deviceId)
    }

    override fun forget(deviceId: String) {
        require(deviceId == DEVICE.id) { "Manage trainers on the knob." }
        SimulatedVoltraController.devices.forEach { delegate.forget(it.id) }
    }

    override suspend fun setMode(deviceId: String, mode: TrainingMode) =
        delegate.setMode(deviceId, mode)

    override suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int) =
        delegate.setParameter(deviceId, parameter, value)

    override suspend fun createTwin(controllerId: String, peerId: String) =
        delegate.createTwin(controllerId, peerId)

    override suspend fun exitTwin() = delegate.exitTwin()

    override fun beginSet() = delegate.beginSet()

    override fun close() {
        delegate.close()
        job.cancel()
    }

    companion object {
        val DEVICE = VoltraDevice("demo-knob", "VOLTRA Remote · Demo")
    }
}
