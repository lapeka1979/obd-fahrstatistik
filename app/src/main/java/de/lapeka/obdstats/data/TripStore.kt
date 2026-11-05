package de.lapeka.obdstats.data

import android.content.Context
import de.lapeka.obdstats.core.Trip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Speichert Fahrten als JSON-Datei im App-Speicher. */
object TripStore {
    private const val FILE = "trips.json"
    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips
    private var loaded = false

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        val f = File(ctx.filesDir, FILE)
        _trips.value = if (f.exists()) runCatching { parse(f.readText()) }.getOrDefault(emptyList()) else emptyList()
        loaded = true
    }

    @Synchronized
    fun add(ctx: Context, trip: Trip) {
        ensureLoaded(ctx)
        val list = _trips.value + trip
        write(ctx, list)
        _trips.value = list
    }

    @Synchronized
    fun delete(ctx: Context, trip: Trip) {
        ensureLoaded(ctx)
        val list = _trips.value - trip
        write(ctx, list)
        _trips.value = list
    }

    private fun write(ctx: Context, list: List<Trip>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("start", t.startMs)
                put("end", t.endMs)
                put("km", t.distanceKm)
                if (t.fuelL != null) put("fuel", t.fuelL)
                put("vmax", t.maxSpeedKmh)
                put("idle", t.idleSeconds)
            })
        }
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(ctx.filesDir, FILE))
    }

    private fun parse(text: String): List<Trip> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Trip(
                startMs = o.getLong("start"),
                endMs = o.getLong("end"),
                distanceKm = o.getDouble("km"),
                fuelL = if (o.has("fuel")) o.getDouble("fuel") else null,
                maxSpeedKmh = o.optDouble("vmax", 0.0),
                idleSeconds = o.optLong("idle", 0)
            )
        }
    }
}
