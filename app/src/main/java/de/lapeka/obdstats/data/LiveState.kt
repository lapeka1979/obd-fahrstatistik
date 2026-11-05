package de.lapeka.obdstats.data

import de.lapeka.obdstats.core.FuelSource
import de.lapeka.obdstats.core.Pid
import de.lapeka.obdstats.core.Trip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Gestoppt",
    val adapterVersion: String = "",
    val values: Map<Pid, Double> = emptyMap(),
    val litersPerHour: Double? = null,
    val litersPer100Km: Double? = null,
    val fuelSource: FuelSource = FuelSource.NONE,
    val currentTrip: Trip? = null,
    val supportedPids: Set<Int> = emptySet(),
    val dtcs: List<String>? = null,
    val dtcStatus: String = ""
)

/** Gemeinsamer Zustand für Handy-Oberfläche, Android Auto und Dienst. */
object LiveRepository {
    val state = MutableStateFlow(LiveState())
    val flow: StateFlow<LiveState> get() = state

    fun update(block: (LiveState) -> LiveState) {
        synchronized(this) { state.value = block(state.value) }
    }

    @Volatile var dtcRequested = false
}
