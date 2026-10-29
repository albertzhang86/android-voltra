package io.github.albertzhang86.voltra

import org.junit.Assert.*
import org.junit.Test

class TrainerTelemetryEpochsTest {
    @Test
    fun `bound epoch lookup uses exact case insensitive identities and legacy fallback`() {
        val state = VoltraState(
            telemetryEpoch = 90,
            trainerTelemetryEpochs = mapOf("aa:bb" to 3, "cc:dd" to 8),
        )
        assertEquals(3L, state.telemetryEpochFor("AA:BB"))
        assertEquals(8L, state.telemetryEpochFor("AA:BB", "CC:DD"))
        assertEquals(90L, state.telemetryEpochFor("AA:BC"))
        assertEquals(90L, state.telemetryEpochFor("AA:BB", "missing"))
        assertEquals(90L, state.copy(trainerTelemetryEpochs = emptyMap()).telemetryEpochFor("AA:BB"))
    }

    @Test
    fun `removed source keeps a tombstone and cannot revive an earlier revision`() {
        val tracker = TrainerTelemetryEpochs<String>()
        val original = tracker.update(mapOf("AA:BB" to "first", "CC:DD" to "other"))
        val missing = tracker.update(mapOf("cc:dd" to "other"))
        val restored = tracker.update(mapOf("aa:bb" to "first", "cc:dd" to "other"))
        assertTrue(missing.getValue("aa:bb") > original.getValue("aa:bb"))
        assertTrue(restored.getValue("aa:bb") > missing.getValue("aa:bb"))
        assertEquals(original.getValue("cc:dd"), restored.getValue("cc:dd"))
        assertEquals(1L, original.getValue("aa:bb"))
        assertEquals(restored, tracker.update(mapOf("AA:BB" to "first", "CC:DD" to "other")))
    }
}
