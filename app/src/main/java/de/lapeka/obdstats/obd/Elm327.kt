package de.lapeka.obdstats.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Spricht mit einem ELM327-kompatiblen Adapter über einen beliebigen Datenstrom. */
class Elm327(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeable: Closeable
) : Closeable {

    /** Sendet einen Befehl und liest bis zum Prompt ">". */
    @Throws(IOException::class)
    fun send(command: String, timeoutMs: Long = 3_000): String {
        while (input.available() > 0) input.read()
        output.write((command + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()

        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("Verbindung beendet")
                if (c == '>'.code) return sb.toString()
                sb.append(c.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        throw IOException("Keine Antwort auf $command")
    }

    /** Standard-Initialisierung; gibt die Adapter-Version zurück. */
    @Throws(IOException::class)
    fun initialize(): String {
        val version = send("ATZ", 5_000).trim()
        Thread.sleep(300)
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) send(cmd)
        return version.lines().lastOrNull { it.isNotBlank() }?.trim() ?: version
    }

    override fun close() {
        runCatching { closeable.close() }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Bluetooth-Classic-Adapter (die meisten ELM327, vLinker FS, OBDLink MX+). */
        @SuppressLint("MissingPermission")
        @Throws(IOException::class)
        fun connectBluetooth(context: Context, address: String): Elm327 {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
                ?: throw IOException("Kein Bluetooth vorhanden")
            if (!adapter.isEnabled) throw IOException("Bluetooth ist ausgeschaltet")
            val device = adapter.getRemoteDevice(address)
            adapter.cancelDiscovery()
            var socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
            } catch (e: IOException) {
                runCatching { socket.close() }
                // Manche Klone akzeptieren nur unsichere Verbindungen
                socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
            }
            return Elm327(socket.inputStream, socket.outputStream, socket)
        }

        /** WLAN-Adapter (meist 192.168.0.10:35000). Bindet an das WLAN, auch wenn es kein Internet hat. */
        @Throws(IOException::class)
        fun connectWifi(context: Context, host: String, port: Int): Elm327 {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: throw IOException("Nicht mit dem WLAN des Adapters verbunden")
            val socket: Socket = wifi.socketFactory.createSocket()
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 5_000
            return Elm327(socket.getInputStream(), socket.getOutputStream(), socket)
        }

    }
}
