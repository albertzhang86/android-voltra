package io.github.albertzhang86.voltra.android

import org.junit.Assert.*
import org.junit.Test

class GattSetupGateTest {
    @Test
    fun `bonded reconnect with cached and requested MTU discovers and subscribes only once`() {
        val setup = GattSetupGate()
        var discoveries = 0
        var subscriptions = 0
        var readyEvents = 0

        // The cached-MTU callback can arrive before our requested-MTU callback.
        if (setup.beginDiscovery()) discoveries++
        if (setup.beginDiscovery()) discoveries++
        assertEquals(1, discoveries)

        if (setup.beginSubscription()) subscriptions++
        // Duplicate discovery callbacks must not write the CCCD a second time.
        if (setup.beginSubscription()) subscriptions++
        if (setup.beginDiscovery()) discoveries++
        if (setup.complete()) readyEvents++
        if (setup.complete()) readyEvents++

        assertEquals(1, discoveries)
        assertEquals(1, subscriptions)
        assertEquals(1, readyEvents)
    }

    @Test
    fun `late callbacks cannot reopen a cancelled or disconnected GATT setup`() {
        for (completedStages in 0..3) {
            val setup = GattSetupGate()
            if (completedStages >= 1) assertTrue(setup.beginDiscovery())
            if (completedStages >= 2) assertTrue(setup.beginSubscription())
            if (completedStages >= 3) assertTrue(setup.complete())
            setup.close()
            assertFalse(setup.beginDiscovery())
            assertFalse(setup.beginSubscription())
            assertFalse(setup.complete())
        }
    }
}
