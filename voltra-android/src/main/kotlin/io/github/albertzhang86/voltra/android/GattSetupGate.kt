package io.github.albertzhang86.voltra.android

import java.util.concurrent.atomic.AtomicReference

/**
 * Android may report both the cached MTU and the result of requestMtu on a bonded reconnect. Each
 * setup step must run once: another discoverServices call can clear the service cache while the
 * first discovery callback is consuming it.
 */
internal class GattSetupGate(private val renewSubscription: Boolean = false) {
    private enum class Stage {
        WAITING_FOR_MTU,
        DISCOVERING,
        RESETTING_SUBSCRIPTION,
        SUBSCRIBING,
        READY,
        REVOKING,
        CLOSED,
    }

    private val stage = AtomicReference(Stage.WAITING_FOR_MTU)

    fun beginDiscovery(): Boolean = stage.compareAndSet(Stage.WAITING_FOR_MTU, Stage.DISCOVERING)

    fun beginSubscription(): Boolean =
        stage.compareAndSet(
            Stage.DISCOVERING,
            if (renewSubscription) Stage.RESETTING_SUBSCRIPTION else Stage.SUBSCRIBING,
        )

    /** The successful disable callback permits exactly one following enable write. */
    fun finishSubscriptionReset(): Boolean =
        stage.compareAndSet(Stage.RESETTING_SUBSCRIPTION, Stage.SUBSCRIBING)

    fun complete(): Boolean = stage.compareAndSet(Stage.SUBSCRIBING, Stage.READY)

    val isReady: Boolean
        get() = stage.get() == Stage.READY

    /** Block command writes and snapshots before sending the knob's authenticated CCCD disable. */
    fun beginRevocation(): Boolean =
        renewSubscription && stage.compareAndSet(Stage.READY, Stage.REVOKING)

    val isRevoking: Boolean
        get() = stage.get() == Stage.REVOKING

    fun close() {
        stage.set(Stage.CLOSED)
    }
}
