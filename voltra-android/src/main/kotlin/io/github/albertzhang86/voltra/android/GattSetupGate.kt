package io.github.albertzhang86.voltra.android

import java.util.concurrent.atomic.AtomicReference

/**
 * Android may report both the cached MTU and the result of requestMtu on a bonded reconnect. Each
 * setup step must run once: another discoverServices call can clear the service cache while the
 * first discovery callback is consuming it.
 */
internal class GattSetupGate {
    private enum class Stage {
        WAITING_FOR_MTU,
        DISCOVERING,
        SUBSCRIBING,
        READY,
        CLOSED,
    }

    private val stage = AtomicReference(Stage.WAITING_FOR_MTU)

    fun beginDiscovery(): Boolean = stage.compareAndSet(Stage.WAITING_FOR_MTU, Stage.DISCOVERING)

    fun beginSubscription(): Boolean = stage.compareAndSet(Stage.DISCOVERING, Stage.SUBSCRIBING)

    fun complete(): Boolean = stage.compareAndSet(Stage.SUBSCRIBING, Stage.READY)

    fun close() {
        stage.set(Stage.CLOSED)
    }
}
