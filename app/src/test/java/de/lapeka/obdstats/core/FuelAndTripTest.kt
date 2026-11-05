package de.lapeka.obdstats.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FuelAndTripTest {
    @Test fun mafToLitersPerHour() {
        // 10 g/s Luft bei Benzin: 10*3600/(14,7*745) = 3,29 l/h
        assertEquals(3.287, FuelCalc.litersPerHourFromMaf(10.0, FuelType.BENZIN), 0.01)
    }

    @Test fun speedDensityPlausible() {
        val maf = FuelCalc.mafFromMap(100.0, 26.85, 2000.0, 2.0)
        assertEquals(32.9, maf, 0.5)
    }

    @Test fun consumptionPer100Km() {
        assertEquals(6.0, FuelCalc.litersPer100Km(6.0, 100.0)!!, 0.001)
        assertNull(FuelCalc.litersPer100Km(1.0, 0.0))
    }

    @Test fun oneHourAt100() {
        val r = TripRecorder(0, maxGapMs = 15_000)
        var t = 0L
        while (t <= 3_600_000) {
            r.add(t, 100.0, 6.0)
            t += 1000
        }
        val trip = r.snapshot()
        assertEquals(100.0, trip.distanceKm, 0.01)
        assertEquals(6.0, trip.fuelL!!, 0.01)
        assertEquals(6.0, trip.avgLitersPer100Km!!, 0.01)
        assertEquals(100.0, trip.avgSpeedKmh, 0.1)
    }

    @Test fun gapsAreNotIntegrated() {
        val r = TripRecorder(0)
        r.add(0, 50.0, null)
        r.add(60_000, 50.0, null) // 60 s Lücke
        assertEquals(0.0, r.snapshot().distanceKm, 0.0001)
        assertNull(r.snapshot().fuelL)
    }

    @Test fun idleTime() {
        val r = TripRecorder(0)
        for (i in 0..10) r.add(i * 1000L, 0.0, 0.8)
        assertEquals(10, r.snapshot().idleSeconds)
    }

    @Test fun summaryOnlyAveragesTripsWithFuel() {
        val trips = listOf(
            Trip(0, 600_000, 10.0, 0.7, 80.0, 0),
            Trip(0, 600_000, 20.0, null, 80.0, 0)
        )
        val s = TripStats.summarize(trips)
        assertEquals(30.0, s.distanceKm, 0.001)
        assertEquals(7.0, s.avgLitersPer100Km!!, 0.001)
    }

    @Test fun weekStartsMonday() {
        val zone = ZoneId.of("Europe/Berlin")
        val thursday = LocalDate.of(2026, 11, 5)
        val monday = LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val trips = listOf(Trip(monday + 1000, monday + 2000, 1.0, null, 0.0, 0), Trip(monday - 1000, monday, 1.0, null, 0.0, 0))
        assertEquals(1, TripStats.thisWeek(trips, thursday, zone).size)
        assertEquals(2, TripStats.thisMonth(trips, thursday, zone).size)
    }

    @Test fun csvUsesGermanFormat() {
        val zone = ZoneId.of("Europe/Berlin")
        val csv = TripCsv.toCsv(listOf(Trip(0, 1_800_000, 25.5, 1.8, 120.0, 60)), zone)
        val line = csv.lines()[1]
        assertEquals("01.01.1970 01:00;01.01.1970 01:30;30,0;25,50;1,80;7,1;51,0;120;1,0", line)
    }
}
