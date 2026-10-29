package io.github.albertzhang86.voltra

import io.github.albertzhang86.voltra.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ResistanceLimitsTest {
    @Test fun `real device ceiling packet remains separate from a configured weight`() {
        val report = VoltraDecoder.decode(VoltraProtocol.hex("551408ce10aa002020000f0001001453e5007daa"))!!
        assertEquals(229, report.registers[VoltraProtocol.MAX_ALLOWED_FORCE])
        assertFalse(Parameter.WEIGHT.register in report.registers)
        for (maximum in listOf(100, 200, 229, 230, 250)) {
            val limits = ResistanceLimits(maximum, LimitSource.DEVICE)
            limits.validate(Parameter.WEIGHT, maximum)
            limits.validate(Parameter.BAND_MAXIMUM, maximum)
            assertThrows(IllegalArgumentException::class.java) { limits.validate(Parameter.WEIGHT, maximum + 1) }
            assertEquals(5..200, limits.range(Parameter.LIMIT))
            assertEquals(5..100, limits.range(Parameter.RETURN_WEIGHT))
        }
        assertEquals(5..200, ResistanceLimits().range(Parameter.WEIGHT))
    }
    @Test fun `length preference converts display only`() {
        assertEquals(10.0, LengthUnit.INCHES.displayCentimeters(25.4), 0.0001)
        assertEquals(10.0, LengthUnit.INCHES.displayMillimetersPerSecond(254), 0.0001)
        assertEquals(0.254, LengthUnit.CENTIMETERS.displayMillimetersPerSecond(254), 0.0001)
        assertNull(LengthUnit.fromWire(2))
    }
}
