package de.lapeka.obdstats.data

import android.content.Context
import de.lapeka.obdstats.core.FuelType

enum class ConnectionType { BLUETOOTH, WIFI }

data class Settings(
    val connection: ConnectionType = ConnectionType.BLUETOOTH,
    val btAddress: String = "",
    val btName: String = "",
    val wifiHost: String = "192.168.0.10",
    val wifiPort: Int = 35000,
    val fuelType: FuelType = FuelType.BENZIN,
    val displacementL: Double = 1.6,
    /** Mit Tankquittungen abgleichen: echter Verbrauch / angezeigter Verbrauch. */
    val correction: Double = 1.0
)

object SettingsStore {
    private const val NAME = "settings"

    fun load(ctx: Context): Settings {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return Settings(
            connection = runCatching { ConnectionType.valueOf(p.getString("connection", "BLUETOOTH")!!) }.getOrDefault(ConnectionType.BLUETOOTH),
            btAddress = p.getString("btAddress", "")!!,
            btName = p.getString("btName", "")!!,
            wifiHost = p.getString("wifiHost", "192.168.0.10")!!,
            wifiPort = p.getInt("wifiPort", 35000),
            fuelType = runCatching { FuelType.valueOf(p.getString("fuelType", "BENZIN")!!) }.getOrDefault(FuelType.BENZIN),
            displacementL = p.getFloat("displacementL", 1.6f).toDouble(),
            correction = p.getFloat("correction", 1.0f).toDouble()
        )
    }

    fun save(ctx: Context, s: Settings) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("connection", s.connection.name)
            .putString("btAddress", s.btAddress)
            .putString("btName", s.btName)
            .putString("wifiHost", s.wifiHost)
            .putInt("wifiPort", s.wifiPort)
            .putString("fuelType", s.fuelType.name)
            .putFloat("displacementL", s.displacementL.toFloat())
            .putFloat("correction", s.correction.toFloat())
            .apply()
    }
}
