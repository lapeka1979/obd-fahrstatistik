package de.lapeka.obdstats.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import de.lapeka.obdstats.core.Format
import de.lapeka.obdstats.core.Pid
import de.lapeka.obdstats.core.TripStats
import de.lapeka.obdstats.data.LiveRepository
import de.lapeka.obdstats.data.LiveState
import de.lapeka.obdstats.data.TripStore
import de.lapeka.obdstats.obd.ObdService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Schlichte Anzeige für Android Auto. Der Host erlaubt nur gelegentliche Aktualisierungen,
 * daher wird höchstens alle 5 Sekunden neu gezeichnet.
 */
class LiveScreen(carContext: CarContext) : Screen(carContext) {

    private var refreshJob: Job? = null

    init {
        TripStore.ensureLoaded(carContext)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refreshJob = lifecycleScope.launch {
                    while (true) {
                        delay(5_000)
                        invalidate()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                refreshJob?.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val s = LiveRepository.flow.value
        val pane = Pane.Builder()

        if (s.connected) {
            pane.addRow(row("Verbrauch jetzt", s.litersPer100Km?.let { Format.l100(it) } ?: Format.lph(s.litersPerHour)))
            val trip = s.currentTrip
            if (trip != null) {
                pane.addRow(row("Diese Fahrt", "${Format.km(trip.distanceKm)} · ${Format.duration(trip.durationSeconds)}"))
                pane.addRow(row("Ø Verbrauch Fahrt", Format.l100(trip.avgLitersPer100Km)))
            }
            pane.addRow(row("Motor", engineLine(s)))
        } else {
            pane.addRow(row("Status", s.status))
            val zone = ZoneId.systemDefault()
            val week = TripStats.summarize(TripStats.thisWeek(TripStore.trips.value, LocalDate.now(zone), zone))
            pane.addRow(row("Diese Woche", "${week.trips} Fahrten · ${Format.km(week.distanceKm)}"))
            pane.addRow(row("Ø Verbrauch Woche", Format.l100(week.avgLitersPer100Km)))
        }

        pane.addAction(
            Action.Builder()
                .setTitle(if (s.running) "Stopp" else "Start")
                .setOnClickListener { toggle(s) }
                .build()
        )

        return PaneTemplate.Builder(pane.build())
            .setTitle("Fahrstatistik")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun engineLine(s: LiveState): String {
        val parts = mutableListOf<String>()
        s.values[Pid.COOLANT_TEMP]?.let { parts += "Wasser ${Format.num(it, "°C")}" }
        s.values[Pid.FUEL_LEVEL]?.let { parts += "Tank ${Format.num(it, "%")}" }
        s.values[Pid.VOLTAGE]?.let { parts += Format.num(it, "V", 1) }
        return parts.joinToString(" · ").ifBlank { "–" }
    }

    private fun toggle(s: LiveState) {
        runCatching {
            if (s.running) ObdService.stop(carContext) else ObdService.start(carContext)
        }.onFailure {
            CarToast.makeText(carContext, "Bitte am Handy starten", CarToast.LENGTH_LONG).show()
        }
        invalidate()
    }

    private fun row(title: String, text: String): Row =
        Row.Builder().setTitle(title).addText(text).build()
}
