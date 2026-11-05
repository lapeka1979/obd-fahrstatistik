package de.lapeka.obdstats.core

enum class FuelType(val label: String, val afr: Double, val densityGPerL: Double) {
    BENZIN("Benzin", 14.7, 745.0),
    DIESEL("Diesel", 14.5, 832.0)
}

/** Woher der Verbrauch kommt, in der Reihenfolge der Genauigkeit. */
enum class FuelSource(val label: String) {
    FUEL_RATE("Kraftstoffrate vom Steuergerät"),
    MAF("Luftmasse (MAF)"),
    MAP("Ansaugdruck (geschätzt)"),
    NONE("nicht verfügbar")
}

object FuelCalc {
    private const val AIR_MOLAR_MASS = 28.97 // g/mol
    private const val GAS_CONSTANT = 8.314 // J/(mol·K)

    /** Liter pro Stunde aus Luftmasse in g/s (stöchiometrisch, bei Diesel nur grob). */
    fun litersPerHourFromMaf(mafGs: Double, fuel: FuelType): Double =
        mafGs * 3600.0 / (fuel.afr * fuel.densityGPerL)

    /**
     * Luftmasse in g/s aus Ansaugdruck (Speed-Density-Verfahren) für Autos ohne MAF-Sensor.
     * [volumetricEfficiency] liegt bei Saugmotoren typischerweise um 0,8 bis 0,85.
     */
    fun mafFromMap(
        mapKpa: Double,
        intakeTempC: Double,
        rpm: Double,
        displacementL: Double,
        volumetricEfficiency: Double = 0.85
    ): Double {
        val tempK = intakeTempC + 273.15
        val imap = rpm * mapKpa / tempK / 2.0
        return imap / 60.0 * volumetricEfficiency * displacementL * AIR_MOLAR_MASS / GAS_CONSTANT
    }

    /** Momentanverbrauch in l/100 km, oder null unter 5 km/h (dann ist l/h aussagekräftiger). */
    fun litersPer100Km(litersPerHour: Double, speedKmh: Double): Double? =
        if (speedKmh < 5.0) null else litersPerHour / speedKmh * 100.0
}
