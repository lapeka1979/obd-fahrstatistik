package de.lapeka.obdstats.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {
    @Test fun speedWithoutSpaces() {
        assertEquals(60.0, Pid.SPEED.parse("410D3C\r\r>")!!, 0.001)
    }

    @Test fun rpmWithSpacesAndSearching() {
        assertEquals(1726.0, Pid.RPM.parse("SEARCHING...\r41 0C 1A F8 \r\r>")!!, 0.001)
    }

    @Test fun noDataGivesNull() {
        assertNull(Pid.MAF.parse("NO DATA\r\r>"))
        assertNull(Pid.MAF.parse("?\r>"))
    }

    @Test fun mafAndFuelRate() {
        assertEquals(10.5, Pid.MAF.parse("4110041A")!!, 0.001)
        assertEquals(3.2, Pid.FUEL_RATE.parse("415E0040")!!, 0.001)
    }

    @Test fun temperaturesAndVoltage() {
        assertEquals(90.0, Pid.COOLANT_TEMP.parse("410582")!!, 0.001)
        assertEquals(14.2, Pid.VOLTAGE.parse("41423778")!!, 0.001)
    }

    @Test fun firstEcuWinsWhenSeveralAnswer() {
        assertEquals(50.0, Pid.SPEED.parse("410D32\r410D32\r>")!!, 0.001)
    }

    @Test fun supportedPidsBitmap() {
        // BE 1F A8 13 ist eine typische Antwort auf 0100
        val pids = ObdParser.supportedPids(0, ObdParser.mode01Data("4100BE1FA813", 0)!!)
        assertTrue(0x01 in pids)
        assertTrue(0x0C in pids)
        assertTrue(0x0D in pids)
        assertTrue(0x20 in pids)
        assertTrue(0x02 !in pids)
    }

    @Test fun dtcCanWithCount() {
        assertEquals(listOf("P0133", "P0221"), ObdParser.parseDtcs("4302013302 21\r>"))
    }

    @Test fun dtcCanNone() {
        assertEquals(emptyList<String>(), ObdParser.parseDtcs("4300\r>"))
    }

    @Test fun dtcLegacyPairs() {
        assertEquals(listOf("P0133", "P0300"), ObdParser.parseDtcs("43 01 33 03 00 00 00\r>"))
    }
}
