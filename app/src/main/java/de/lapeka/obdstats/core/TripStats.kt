package de.lapeka.obdstats.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class Summary(
    val trips: Int,
    val distanceKm: Double,
    val durationSeconds: Long,
    /** Liter nur über Fahrten mit Verbrauchsdaten. */
    val fuelL: Double,
    val distanceWithFuelKm: Double
) {
    val avgLitersPer100Km: Double?
        get() = if (distanceWithFuelKm >= 0.1) fuelL / distanceWithFuelKm * 100.0 else null
}

object TripStats {
    fun summarize(trips: List<Trip>): Summary {
        val withFuel = trips.filter { it.fuelL != null }
        return Summary(
            trips = trips.size,
            distanceKm = trips.sumOf { it.distanceKm },
            durationSeconds = trips.sumOf { it.durationSeconds },
            fuelL = withFuel.sumOf { it.fuelL ?: 0.0 },
            distanceWithFuelKm = withFuel.sumOf { it.distanceKm }
        )
    }

    fun thisWeek(trips: List<Trip>, today: LocalDate, zone: ZoneId): List<Trip> {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
        return trips.filter { it.startMs >= start }
    }

    fun thisMonth(trips: List<Trip>, today: LocalDate, zone: ZoneId): List<Trip> {
        val start = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return trips.filter { it.startMs >= start }
    }
}

object Format {
    private val de = Locale.GERMANY

    fun km(v: Double) = String.format(de, "%.1f km", v)
    fun liters(v: Double) = String.format(de, "%.2f l", v)
    fun l100(v: Double?) = v?.let { String.format(de, "%.1f l/100 km", it) } ?: "–"
    fun lph(v: Double?) = v?.let { String.format(de, "%.1f l/h", it) } ?: "–"
    fun num(v: Double?, unit: String, decimals: Int = 0) =
        v?.let { String.format(de, "%.${decimals}f %s", it, unit) } ?: "–"

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    fun dateTime(ms: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", de).format(Instant.ofEpochMilli(ms).atZone(zone))
}

/** CSV mit Semikolon und Dezimalkomma, damit Excel es in deutscher Einstellung direkt öffnet. */
object TripCsv {
    private val de = Locale.GERMANY
    const val HEADER = "Start;Ende;Dauer (min);Strecke (km);Verbrauch (l);Ø l/100 km;Ø km/h;Max km/h;Standzeit (min)"

    fun toCsv(trips: List<Trip>, zone: ZoneId): String = buildString {
        append(HEADER).append("\r\n")
        for (t in trips.sortedBy { it.startMs }) {
            append(Format.dateTime(t.startMs, zone)).append(';')
            append(Format.dateTime(t.endMs, zone)).append(';')
            append(String.format(de, "%.1f", t.durationSeconds / 60.0)).append(';')
            append(String.format(de, "%.2f", t.distanceKm)).append(';')
            append(t.fuelL?.let { String.format(de, "%.2f", it) } ?: "").append(';')
            append(t.avgLitersPer100Km?.let { String.format(de, "%.1f", it) } ?: "").append(';')
            append(String.format(de, "%.1f", t.avgSpeedKmh)).append(';')
            append(String.format(de, "%.0f", t.maxSpeedKmh)).append(';')
            append(String.format(de, "%.1f", t.idleSeconds / 60.0)).append("\r\n")
        }
    }
}
