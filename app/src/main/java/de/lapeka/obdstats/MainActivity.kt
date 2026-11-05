package de.lapeka.obdstats

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.lapeka.obdstats.core.Format
import de.lapeka.obdstats.core.FuelType
import de.lapeka.obdstats.core.Pid
import de.lapeka.obdstats.core.PidNames
import de.lapeka.obdstats.core.Trip
import de.lapeka.obdstats.core.TripCsv
import de.lapeka.obdstats.core.TripStats
import de.lapeka.obdstats.data.ConnectionType
import de.lapeka.obdstats.data.LiveRepository
import de.lapeka.obdstats.data.LiveState
import de.lapeka.obdstats.data.SettingsStore
import de.lapeka.obdstats.data.TripStore
import de.lapeka.obdstats.obd.ObdService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private lateinit var statusText: TextView
    private lateinit var startStop: Button
    private lateinit var liveText: TextView
    private lateinit var adapterText: TextView
    private lateinit var statsText: TextView
    private lateinit var tripsBox: LinearLayout
    private lateinit var dtcText: TextView
    private lateinit var supportedText: TextView
    private lateinit var fuelGroup: RadioGroup
    private lateinit var displacementEdit: EditText
    private lateinit var correctionEdit: EditText
    private lateinit var wifiEdit: EditText

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) pickAdapter() else toast("Ohne Bluetooth-Berechtigung geht es nicht")
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri == null) return@registerForActivityResult
            val csv = TripCsv.toCsv(TripStore.trips.value, zone)
            runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // BOM für Excel
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess { toast("Export gespeichert") }.onFailure { toast("Export fehlgeschlagen: ${it.message}") }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ObdService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TripStore.ensureLoaded(this)
        setContentView(buildUi())
        loadSettingsIntoUi()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveRepository.flow.combine(TripStore.trips) { s, t -> s to t }.collect { (s, t) -> render(s, t) }
            }
        }
    }

    // ---------- Oberfläche ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        statusText = TextView(this).apply { textSize = 16f }
        startStop = Button(this).apply { setOnClickListener { toggle() } }
        root.addView(statusText)
        root.addView(startStop)

        root.addView(header("Live"))
        liveText = mono()
        root.addView(liveText)

        root.addView(header("Statistik"))
        statsText = mono()
        root.addView(statsText)
        root.addView(Button(this).apply {
            text = "Fahrten als CSV exportieren"
            setOnClickListener { exportLauncher.launch("fahrten-${LocalDate.now()}.csv") }
        })

        root.addView(header("Letzte Fahrten"))
        tripsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tripsBox)

        root.addView(header("Fehlerspeicher"))
        dtcText = TextView(this)
        root.addView(dtcText)
        root.addView(Button(this).apply {
            text = "Fehlercodes auslesen"
            setOnClickListener {
                if (!LiveRepository.flow.value.connected) toast("Erst mit dem Auto verbinden")
                else {
                    LiveRepository.dtcRequested = true
                    LiveRepository.update { it.copy(dtcStatus = "Lese Fehlerspeicher …") }
                }
            }
        })

        root.addView(header("Einstellungen"))
        adapterText = TextView(this)
        root.addView(adapterText)
        root.addView(Button(this).apply {
            text = "Bluetooth-Adapter wählen"
            setOnClickListener { requestBtAndPick() }
        })
        wifiEdit = EditText(this).apply {
            hint = "WLAN-Adapter, z. B. 192.168.0.10:35000"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(wifiEdit)
        root.addView(Button(this).apply {
            text = "WLAN-Adapter verwenden"
            setOnClickListener { useWifi() }
        })

        fuelGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        FuelType.entries.forEachIndexed { i, f ->
            fuelGroup.addView(RadioButton(this).apply { id = 100 + i; text = f.label })
        }
        root.addView(label("Kraftstoff"))
        root.addView(fuelGroup)

        root.addView(label("Hubraum in Litern (nur nötig, wenn das Auto keinen Luftmassenmesser hat)"))
        displacementEdit = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        root.addView(displacementEdit)

        root.addView(label("Korrekturfaktor Verbrauch (echt getankt ÷ angezeigt, Standard 1,0)"))
        correctionEdit = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        root.addView(correctionEdit)
        root.addView(Button(this).apply {
            text = "Einstellungen speichern"
            setOnClickListener { saveSettings() }
        })

        root.addView(header("Was dein Auto liefert"))
        supportedText = TextView(this)
        root.addView(supportedText)

        return ScrollView(this).apply { addView(root) }
    }

    private fun render(s: LiveState, trips: List<Trip>) {
        statusText.text = buildString {
            append(s.status)
            if (s.adapterVersion.isNotBlank()) append("  ·  ").append(s.adapterVersion)
        }
        startStop.text = if (s.running) "Aufzeichnung stoppen" else "Aufzeichnung starten"

        liveText.text = if (!s.connected) "Nicht verbunden" else buildString {
            appendLine("Verbrauch jetzt: " + (s.litersPer100Km?.let { Format.l100(it) } ?: Format.lph(s.litersPerHour)))
            appendLine("Quelle:          " + s.fuelSource.label)
            s.currentTrip?.let {
                appendLine("Diese Fahrt:     ${Format.km(it.distanceKm)}, ${Format.duration(it.durationSeconds)}")
                appendLine("Ø Verbrauch:     ${Format.l100(it.avgLitersPer100Km)}")
            }
            appendLine()
            for ((pid, v) in s.values.toSortedMap(compareBy { it.code })) {
                val decimals = when (pid) {
                    Pid.VOLTAGE, Pid.MAF, Pid.FUEL_RATE -> 1
                    Pid.LAMBDA_O2_A, Pid.LAMBDA_O2_B, Pid.LAMBDA_CMD -> 2
                    else -> 0
                }
                appendLine("%-16s %s".format(pid.label + ":", Format.num(v, pid.unit, decimals)))
            }
        }.trimEnd()

        val today = LocalDate.now(zone)
        statsText.text = buildString {
            for ((name, list) in listOf(
                "Diese Woche" to TripStats.thisWeek(trips, today, zone),
                "Dieser Monat" to TripStats.thisMonth(trips, today, zone),
                "Gesamt" to trips
            )) {
                val sum = TripStats.summarize(list)
                appendLine("$name: ${sum.trips} Fahrten, ${Format.km(sum.distanceKm)}, ${Format.duration(sum.durationSeconds)}")
                appendLine("   Ø ${Format.l100(sum.avgLitersPer100Km)}" + if (sum.fuelL > 0) ", ${Format.liters(sum.fuelL)}" else "")
            }
        }.trimEnd()

        tripsBox.removeAllViews()
        if (trips.isEmpty()) tripsBox.addView(TextView(this).apply { text = "Noch keine Fahrten" })
        trips.sortedByDescending { it.startMs }.take(30).forEach { t ->
            tripsBox.addView(TextView(this).apply {
                text = "${Format.dateTime(t.startMs, zone)}  ·  ${Format.km(t.distanceKm)}  ·  " +
                    "${Format.duration(t.durationSeconds)}  ·  ${Format.l100(t.avgLitersPer100Km)}"
                setPadding(0, dp(6), 0, dp(6))
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Diese Fahrt löschen?")
                        .setPositiveButton("Löschen") { _, _ -> TripStore.delete(this@MainActivity, t) }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                    true
                }
            })
        }

        dtcText.text = buildString {
            append(s.dtcStatus.ifBlank { "Noch nicht ausgelesen" })
            s.dtcs?.takeIf { it.isNotEmpty() }?.let { append("\n").append(it.joinToString(", ")) }
        }

        supportedText.text = if (s.supportedPids.isEmpty()) "Wird beim ersten Verbinden ermittelt." else
            s.supportedPids.filterNot { PidNames.isRangeMarker(it) }.sorted()
                .joinToString("\n") { "• " + PidNames.describe(it) }
    }

    // ---------- Aktionen ----------

    private fun toggle() {
        if (LiveRepository.flow.value.running) {
            ObdService.stop(this)
            return
        }
        val s = SettingsStore.load(this)
        if (s.connection == ConnectionType.BLUETOOTH && s.btAddress.isBlank()) {
            toast("Bitte zuerst den Adapter wählen")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ObdService.start(this)
        }
    }

    private fun requestBtAndPick() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else pickAdapter()
    }

    @SuppressLint("MissingPermission")
    private fun pickAdapter() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Bitte Bluetooth einschalten")
            return
        }
        val devices = adapter.bondedDevices.sortedBy { it.name ?: "" }
        if (devices.isEmpty()) {
            toast("Kein gekoppeltes Gerät. Adapter erst in den Bluetooth-Einstellungen koppeln (PIN meist 1234 oder 0000).")
            return
        }
        val names = devices.map { "${it.name ?: "Unbekannt"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Adapter wählen")
            .setItems(names) { _, i ->
                val d = devices[i]
                SettingsStore.save(
                    this,
                    SettingsStore.load(this).copy(connection = ConnectionType.BLUETOOTH, btAddress = d.address, btName = d.name ?: d.address)
                )
                loadSettingsIntoUi()
            }
            .show()
    }

    private fun useWifi() {
        val text = wifiEdit.text.toString().trim().ifBlank { "192.168.0.10:35000" }
        val host = text.substringBefore(':')
        val port = text.substringAfter(':', "35000").toIntOrNull() ?: 35000
        SettingsStore.save(this, SettingsStore.load(this).copy(connection = ConnectionType.WIFI, wifiHost = host, wifiPort = port))
        loadSettingsIntoUi()
        toast("WLAN-Adapter gespeichert. Handy vorher mit dem WLAN des Adapters verbinden.")
    }

    private fun saveSettings() {
        val fuel = FuelType.entries.getOrElse(fuelGroup.checkedRadioButtonId - 100) { FuelType.BENZIN }
        val displacement = displacementEdit.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.5..8.0 }
        val correction = correctionEdit.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.5..2.0 }
        if (displacement == null || correction == null) {
            toast("Hubraum 0,5 bis 8 l, Korrekturfaktor 0,5 bis 2")
            return
        }
        SettingsStore.save(this, SettingsStore.load(this).copy(fuelType = fuel, displacementL = displacement, correction = correction))
        toast("Gespeichert. Gilt ab der nächsten Verbindung.")
    }

    private fun loadSettingsIntoUi() {
        val s = SettingsStore.load(this)
        adapterText.text = when (s.connection) {
            ConnectionType.BLUETOOTH -> if (s.btAddress.isBlank()) "Adapter: noch keiner gewählt" else "Adapter: ${s.btName} (Bluetooth)"
            ConnectionType.WIFI -> "Adapter: WLAN ${s.wifiHost}:${s.wifiPort}"
        }
        wifiEdit.setText("${s.wifiHost}:${s.wifiPort}")
        fuelGroup.check(100 + s.fuelType.ordinal)
        displacementEdit.setText(s.displacementL.toString().replace('.', ','))
        correctionEdit.setText(s.correction.toString().replace('.', ','))
    }

    // ---------- Helfer ----------

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(12), 0, 0)
    }

    private fun mono() = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
