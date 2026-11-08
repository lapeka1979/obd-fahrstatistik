package de.lapeka.obdstats.core

data class Trip(
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    /** Verbrauchte Liter, null wenn das Auto keine Verbrauchsdaten liefert. */
    val fuelL: Double?,
    val maxSpeedKmh: Double,
    val idleSeconds: Long
) {
    val durationSeconds: Long get() = (endMs - startMs) / 1000

    val avgSpeedKmh: Double
        get() = if (durationSeconds > 0) distanceKm / (durationSeconds / 3600.0) else 0.0

    val avgLitersPer100Km: Double?
        get() = if (fuelL != null && distanceKm >= 0.1) fuelL / distanceKm * 100.0 else null
}

/**
 * Integriert Messwerte zu einer Fahrt. Lücken über [maxGapMs] (z. B. Verbindungsabbruch)
 * werden nicht hochgerechnet.
 */
class TripRecorder(private val startMs: Long, private val maxGapMs: Long = 15_000) {
    var distanceKm = 0.0; private set
    var fuelL = 0.0; private set
    var hasFuelData = false; private set
    var maxSpeedKmh = 0.0; private set
    private var idleMs = 0L
    private var lastMs: Long? = null
    private var lastSpeed: Double? = null
    private var lastFlow: Double? = null
    var lastSampleMs: Long = startMs; private set

    fun add(timeMs: Long, speedKmh: Double?, fuelLph: Double?) {
        val prev = lastMs
        if (prev != null) {
            val dtMs = timeMs - prev
            if (dtMs in 1..maxGapMs) {
                val dtH = dtMs / 3_600_000.0
                val s0 = lastSpeed
                if (speedKmh != null && s0 != null) {
                    distanceKm += (s0 + speedKmh) / 2.0 * dtH
                    if (speedKmh < 1.0 && s0 < 1.0) idleMs += dtMs
                }
                val f0 = lastFlow
                if (fuelLph != null && f0 != null) {
                    fuelL += (f0 + fuelLph) / 2.0 * dtH
                    hasFuelData = true
                }
            }
        }
        if (speedKmh != null && speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
        lastMs = timeMs
        lastSampleMs = timeMs
        if (speedKmh != null) lastSpeed = speedKmh
        if (fuelLph != null) lastFlow = fuelLph
    }

    fun snapshot(nowMs: Long = lastSampleMs): Trip = Trip(
        startMs = startMs,
        endMs = nowMs,
        distanceKm = distanceKm,
        fuelL = if (hasFuelData) fuelL else null,
        maxSpeedKmh = maxSpeedKmh,
        idleSeconds = idleMs / 1000
    )
}
