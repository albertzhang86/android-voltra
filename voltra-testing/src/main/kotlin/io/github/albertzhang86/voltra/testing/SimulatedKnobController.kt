package io.github.albertzhang86.voltra.testing

import io.github.albertzhang86.voltra.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Explicit UI demo of one knob with one selected trainer, never a hardware fallback. */
class SimulatedKnobController(parentScope: CoroutineScope) : VoltraController {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val delegate = SimulatedVoltraController(scope)
    override val state =
        delegate.state
            .map {
                it.copy(
                    viaKnob = true,
                    twinSupported = false,
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
    }

    override fun disconnect(deviceId: String) =
        delegate.disconnect(SimulatedVoltraController.devices.first().id)

    override suspend fun setMode(deviceId: String, mode: TrainingMode) =
        delegate.setMode(deviceId, mode)

    override suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int) =
        delegate.setParameter(deviceId, parameter, value)

    override suspend fun createTwin(controllerId: String, peerId: String): Unit =
        error("Twin setup is not available through this knob.")

    override suspend fun exitTwin(): Unit = error("Exit Twin on the trainers.")

    override fun beginSet() = delegate.beginSet()

    override fun close() {
        delegate.close()
        job.cancel()
    }

    companion object {
        val DEVICE = VoltraDevice("demo-knob", "VOLTRA Remote · Demo")
    }
}
