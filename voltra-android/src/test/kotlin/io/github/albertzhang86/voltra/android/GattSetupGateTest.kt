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

    @Test
    fun `knob reconnect waits for disable and enable before accepting renewed snapshots`() {
        // Each new app connection renews its session even if Android kept the physical bond/link.
        repeat(2) {
            val setup = GattSetupGate(renewSubscription = true)
            val writes = mutableListOf<String>()
            var readyEvents = 0
            assertFalse(setup.isReady)
            assertFalse(setup.finishSubscriptionReset())
            assertTrue(setup.beginDiscovery())
            if (setup.beginSubscription()) writes += "disable"
            assertFalse(setup.beginSubscription())
            assertFalse(setup.beginDiscovery())
            assertEquals(listOf("disable"), writes)
            // The cached subscription can still deliver old packets here. None may be admitted.
            assertFalse(setup.isReady)
            assertFalse(setup.complete())

            fun descriptorWritten() {
                if (setup.finishSubscriptionReset()) writes += "enable"
                else if (setup.complete()) readyEvents++
            }
            descriptorWritten()
            assertEquals(listOf("disable", "enable"), writes)
            assertEquals(0, readyEvents)
            assertFalse(setup.isReady)
            descriptorWritten()
            assertEquals(1, readyEvents)
            assertTrue(setup.isReady)
            descriptorWritten()
            assertEquals(listOf("disable", "enable"), writes)
            assertEquals(1, readyEvents)
            setup.close()
            assertFalse(setup.isReady)
        }
    }

    @Test
    fun `failed cancelled or timed out knob subscription cannot admit packets or continue setup`() {
        for (completedStages in 0..4) {
            val setup = GattSetupGate(renewSubscription = true)
            if (completedStages >= 1) assertTrue(setup.beginDiscovery())
            if (completedStages >= 2) assertTrue(setup.beginSubscription())
            if (completedStages >= 3) assertTrue(setup.finishSubscriptionReset())
            if (completedStages >= 4) assertTrue(setup.complete())
            // Descriptor failure/rejection and the encompassing connection timeout close the gate.
            setup.close()
            assertFalse(setup.isReady)
            assertFalse(setup.beginDiscovery())
            assertFalse(setup.beginSubscription())
            assertFalse(setup.finishSubscriptionReset())
            assertFalse(setup.complete())
        }
    }

    @Test
    fun `motor cancellation closes snapshot admission before the disable acknowledgement`() {
        val setup = GattSetupGate(renewSubscription = true)
        assertTrue(setup.beginDiscovery())
        assertTrue(setup.beginSubscription())
        assertTrue(setup.finishSubscriptionReset())
        assertTrue(setup.complete())

        assertTrue(setup.beginRevocation())
        assertTrue(setup.isRevoking)
        assertFalse(setup.isReady)
        assertFalse(setup.beginRevocation())
        // A descriptor callback now belongs to revocation, never to setup or re-enabling CCCD.
        assertFalse(setup.finishSubscriptionReset())
        assertFalse(setup.complete())
        assertFalse(setup.beginDiscovery())
        assertFalse(setup.beginSubscription())

        // ACK, failure and timeout all close the link; a late callback cannot resurrect it.
        setup.close()
        assertFalse(setup.isRevoking)
        assertFalse(setup.isReady)
        assertFalse(setup.beginRevocation())
        assertFalse(setup.complete())
    }

    @Test
    fun `revocation cannot enqueue another descriptor while knob setup is incomplete`() {
        for (completedStages in 0..3) {
            val setup = GattSetupGate(renewSubscription = true)
            if (completedStages >= 1) assertTrue(setup.beginDiscovery())
            if (completedStages >= 2) assertTrue(setup.beginSubscription())
            if (completedStages >= 3) assertTrue(setup.finishSubscriptionReset())
            assertFalse(setup.beginRevocation())
            assertFalse(setup.isRevoking)
            // The transport uses close as its fallback, without a competing descriptor write.
            setup.close()
            assertFalse(setup.isReady)
            assertFalse(setup.complete())
        }
    }

    @Test
    fun `direct trainer revocation never changes its subscription protocol`() {
        val setup = GattSetupGate()
        assertTrue(setup.beginDiscovery())
        assertTrue(setup.beginSubscription())
        assertTrue(setup.complete())
        assertFalse(setup.beginRevocation())
        assertTrue(setup.isReady)
        assertFalse(setup.isRevoking)
        setup.close()
        assertFalse(setup.isReady)
    }
}
