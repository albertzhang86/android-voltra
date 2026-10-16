package io.github.albertzhang86.voltra.testing

import io.github.albertzhang86.voltra.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Explicit Twin UI demo through a knob. Never evidence of native hardware support. */
class SimulatedKnobController(
    parentScope: CoroutineScope,
    private val initialTrainers: List<VoltraDevice> = SimulatedVoltraController.devices,
) : VoltraController {
    init {
        require(initialTrainers.isNotEmpty() && initialTrainers.size <= 2)
        require(initialTrainers.distinct().size == initialTrainers.size)
        require(initialTrainers.all { it in SimulatedVoltraController.devices })
    }

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val delegate = SimulatedVoltraController(scope)
    private var topologyRevision = 0L
    override val state =
        delegate.state
            .map {
                it.copy(
                    viaKnob = true,
                    topologyRevision = ++topologyRevision,
                    knob =
                        if (it.trainers.isEmpty()) null
                        else
                            KnobState(
                                DEVICE,
                                ConnectionStatus.READY,
                                78,
                                twinIdentityGuarded = true,
                                twinCandidate =
                                    if (it.twin == null && it.trainers.size == 1)
                                        SimulatedVoltraController.devices.firstOrNull { candidate ->
                                            it.trainers.none { trainer ->
                                                trainer.device.id == candidate.id
                                            }
                                        }
                                    else null,
                            ),
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, VoltraState(viaKnob = true, isSimulated = true))

    override fun scan() = flowOf(listOf(DEVICE))

    override suspend fun connect(device: VoltraDevice) {
        require(device == DEVICE)
        initialTrainers.forEach { delegate.connect(it) }
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

    override suspend fun createTwin(controllerId: String, peerId: String) {
        check(delegate.state.value.trainers.any { it.device.id == controllerId })
        if (delegate.state.value.trainers.none { it.device.id == peerId }) {
            val peer = requireNotNull(SimulatedVoltraController.devices.find { it.id == peerId })
            delegate.connect(peer)
        }
        delegate.createTwin(controllerId, peerId)
    }

    override suspend fun exitTwin() {
        val peerId = delegate.state.value.twin?.peerId
        delegate.exitTwin()
        // A single-trainer knob follows its selected controller again after Twin ends.
        if (initialTrainers.size == 1 && peerId != null) delegate.disconnect(peerId)
    }

    override fun beginSet() = delegate.beginSet()

    override fun endSet() = delegate.endSet()

    override fun close() {
        delegate.close()
        job.cancel()
    }

    companion object {
        val DEVICE = VoltraDevice("demo-knob", "VOLTRA Remote · Demo")
    }
}
