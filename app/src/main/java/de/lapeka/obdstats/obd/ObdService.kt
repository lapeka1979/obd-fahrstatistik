package de.lapeka.obdstats.obd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.lapeka.obdstats.MainActivity
import de.lapeka.obdstats.R
import de.lapeka.obdstats.core.FuelCalc
import de.lapeka.obdstats.core.FuelSource
import de.lapeka.obdstats.core.Format
import de.lapeka.obdstats.core.ObdParser
import de.lapeka.obdstats.core.Pid
import de.lapeka.obdstats.core.TripRecorder
import de.lapeka.obdstats.data.ConnectionType
import de.lapeka.obdstats.data.LiveRepository
import de.lapeka.obdstats.data.Settings
import de.lapeka.obdstats.data.SettingsStore
import de.lapeka.obdstats.data.TripStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Vordergrund-Dienst: hält die Verbindung zum Adapter, fragt Werte ab und zeichnet Fahrten auf.
 * Bricht die Verbindung ab (Zündung aus), wird die Fahrt gespeichert und alle 10 s neu verbunden.
 * So entsteht pro Fahrt automatisch ein Eintrag, solange der Dienst läuft.
 */
class ObdService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var recorder: TripRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            else -> startRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Verbinde mit Adapter …"), type)
        if (job?.isActive == true) return
        TripStore.ensureLoaded(this)
        LiveRepository.update { it.copy(running = true, status = "Verbinde …") }
        job = scope.launch { runLoop() }
    }

    private fun stopRecording() {
        job?.cancel()
        job = null
        saveTrip()
        LiveRepository.update { it.copy(running = false, connected = false, status = "Gestoppt", currentTrip = null) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        saveTrip()
        scope.cancel()
        LiveRepository.update { it.copy(running = false, connected = false) }
        super.onDestroy()
    }

    private suspend fun CoroutineScope.runLoop() {
        while (isActive) {
            val settings = SettingsStore.load(this@ObdService)
            var elm: Elm327? = null
            try {
                elm = connect(settings)
                val version = elm.initialize()
                LiveRepository.update { it.copy(status = "Suche Protokoll …", adapterVersion = version) }
                val supported = readSupportedPids(elm)
                if (supported.isEmpty()) throw IOException("Auto antwortet nicht (Zündung an?)")
                LiveRepository.update { it.copy(connected = true, status = "Verbunden", supportedPids = supported) }
                updateNotification("Aufzeichnung läuft")
                recorder = TripRecorder(System.currentTimeMillis())
                poll(elm, settings, supported)
            } catch (e: IOException) {
                saveTrip()
                LiveRepository.update {
                    it.copy(connected = false, status = "${e.message ?: "Verbindungsfehler"} · neuer Versuch in 10 s", currentTrip = null)
                }
                updateNotification("Warte auf Adapter …")
            } catch (e: SecurityException) {
                LiveRepository.update { it.copy(status = "Bluetooth-Berechtigung fehlt") }
            } finally {
                elm?.close()
            }
            delay(10_000)
        }
    }

    private fun connect(s: Settings): Elm327 = when (s.connection) {
        ConnectionType.BLUETOOTH -> {
            if (s.btAddress.isBlank()) throw IOException("Kein Adapter ausgewählt")
            Elm327.connectBluetooth(this, s.btAddress)
        }
        ConnectionType.WIFI -> Elm327.connectWifi(this, s.wifiHost, s.wifiPort)
    }

    private fun readSupportedPids(elm: Elm327): Set<Int> {
        val result = mutableSetOf<Int>()
        var base = 0
        while (base <= 0xA0) {
            val raw = elm.send("01%02X".format(base), if (base == 0) 15_000 else 3_000)
            val data = ObdParser.mode01Data(raw, base) ?: break
            val pids = ObdParser.supportedPids(base, data)
            result += pids
            if ((base + 0x20) !in pids) break
            base += 0x20
        }
        return result
    }

    private suspend fun CoroutineScope.poll(elm: Elm327, s: Settings, supported: Set<Int>) {
        val slowPids = listOf(Pid.COOLANT_TEMP, Pid.FUEL_LEVEL, Pid.VOLTAGE, Pid.AMBIENT_TEMP, Pid.OIL_TEMP, Pid.INTAKE_TEMP, Pid.ENGINE_LOAD)
            .filter { it.code in supported }
        val source = when {
            Pid.FUEL_RATE.code in supported -> FuelSource.FUEL_RATE
            Pid.MAF.code in supported -> FuelSource.MAF
            Pid.MAP.code in supported && Pid.RPM.code in supported -> FuelSource.MAP
            else -> FuelSource.NONE
        }
        val values = mutableMapOf<Pid, Double>()
        var cycle = 0
        var failures = 0

        fun read(pid: Pid): Double? {
            val raw = elm.send(pid.command)
            val v = pid.parse(raw)
            if (v != null) values[pid] = v
            return v
        }

        while (isActive) {
            val now = System.currentTimeMillis()
            val speed = read(Pid.SPEED)
            val rpm = if (Pid.RPM.code in supported) read(Pid.RPM) else null

            val lph: Double? = when (source) {
                FuelSource.FUEL_RATE -> read(Pid.FUEL_RATE)
                FuelSource.MAF -> read(Pid.MAF)?.let { FuelCalc.litersPerHourFromMaf(it, s.fuelType) }
                FuelSource.MAP -> {
                    val map = read(Pid.MAP)
                    val iat = values[Pid.INTAKE_TEMP] ?: 20.0
                    if (map != null && rpm != null)
                        FuelCalc.litersPerHourFromMaf(FuelCalc.mafFromMap(map, iat, rpm, s.displacementL), s.fuelType)
                    else null
                }
                FuelSource.NONE -> null
            }?.times(s.correction)

            // Langsame Werte: einer pro Durchlauf, reihum
            if (slowPids.isNotEmpty()) read(slowPids[cycle % slowPids.size])

            if (LiveRepository.dtcRequested) {
                LiveRepository.dtcRequested = false
                val dtcs = ObdParser.parseDtcs(elm.send("03", 5_000))
                LiveRepository.update {
                    it.copy(dtcs = dtcs, dtcStatus = if (dtcs.isEmpty()) "Keine Fehler gespeichert" else "${dtcs.size} Fehler gespeichert")
                }
            }

            // Ohne Geschwindigkeit mehrmals hintereinander: Auto ist aus
            failures = if (speed == null && rpm == null) failures + 1 else 0
            if (failures >= 5) throw IOException("Auto antwortet nicht mehr")

            val rec = recorder
            if (rec != null) {
                rec.add(now, speed, lph)
                val trip = rec.snapshot(now)
                LiveRepository.update {
                    it.copy(
                        values = values.toMap(),
                        litersPerHour = lph,
                        litersPer100Km = if (lph != null && speed != null) FuelCalc.litersPer100Km(lph, speed) else null,
                        fuelSource = source,
                        currentTrip = trip
                    )
                }
                if (cycle % 10 == 0) {
                    updateNotification("${Format.km(trip.distanceKm)} · ${Format.l100(trip.avgLitersPer100Km)}")
                }
            }
            cycle++
            delay(250)
        }
    }

    @Synchronized
    private fun saveTrip() {
        val rec = recorder ?: return
        recorder = null
        val trip = rec.snapshot()
        if (trip.distanceKm >= 0.1) TripStore.add(this, trip)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ObdService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Fahrstatistik")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stopp", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    companion object {
        private const val CHANNEL = "obd"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "de.lapeka.obdstats.START"
        const val ACTION_STOP = "de.lapeka.obdstats.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, ObdService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ObdService::class.java).setAction(ACTION_STOP))
        }
    }
}
