package de.lapeka.obdstats.core

enum class FuelType(val label: String, val afr: Double, val densityGPerL: Double) {
    BENZIN("Benzin", 14.7, 745.0),
    DIESEL("Diesel", 14.5, 832.0)
}

/** Woher der Verbrauch kommt, in der Reihenfolge der Genauigkeit. */
enum class FuelSource(val label: String) {
    FUEL_RATE("Kraftstoffrate vom Steuergerät"),
    MAF_LAMBDA("Luftmasse und Lambda"),
    TORQUE("Drehmoment (geschätzt)"),
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

    /**
     * Liter pro Stunde aus Luftmasse und gemessenem Lambda. Beim Diesel ist Lambda je nach Last
     * 1,2 bis über 4; ohne diese Korrektur wäre der Verbrauch um ein Vielfaches zu hoch.
     */
    fun litersPerHourFromMafLambda(mafGs: Double, lambda: Double, fuel: FuelType): Double =
        litersPerHourFromMaf(mafGs, fuel) / lambda.coerceIn(0.7, 10.0)

    /**
     * Liter pro Stunde aus abgegebener Leistung: Drehmoment × Drehzahl × spezifischer Verbrauch.
     * Im Schubbetrieb (Moment ≤ 0, Drehzahl über Leerlauf) 0, sonst mindestens der Leerlaufverbrauch.
     */
    fun litersPerHourFromTorque(
        torquePercent: Double,
        referenceTorqueNm: Double,
        rpm: Double,
        displacementL: Double,
        fuel: FuelType
    ): Double {
        if (rpm < 300) return 0.0
        if (torquePercent <= 0 && rpm > 1100) return 0.0
        val torqueNm = torquePercent.coerceAtLeast(0.0) / 100.0 * referenceTorqueNm
        val powerKw = torqueNm * rpm * 2 * Math.PI / 60.0 / 1000.0
        val bsfc = if (fuel == FuelType.DIESEL) 215.0 else 260.0 // g/kWh, guter Betriebspunkt
        val lph = powerKw * bsfc / fuel.densityGPerL
        val idle = displacementL * 0.3 // ca. 0,6 l/h bei 2,0 l
        return maxOf(lph, idle)
    }

    /** Wählt die genaueste Verbrauchsquelle, die das Auto anbietet. */
    fun chooseSource(fuel: FuelType, supported: Set<Int>): FuelSource {
        val has = { p: Pid -> p.code in supported }
        val lambda = lambdaPid(supported) != null
        val torque = has(Pid.TORQUE_ACTUAL) && has(Pid.TORQUE_REF) && has(Pid.RPM)
        return when {
            has(Pid.FUEL_RATE) -> FuelSource.FUEL_RATE
            has(Pid.MAF) && lambda -> FuelSource.MAF_LAMBDA
            fuel == FuelType.DIESEL && torque -> FuelSource.TORQUE
            has(Pid.MAF) -> FuelSource.MAF
            has(Pid.MAP) && has(Pid.RPM) -> FuelSource.MAP
            torque -> FuelSource.TORQUE
            else -> FuelSource.NONE
        }
    }

    /** Gemessenes Lambda bevorzugt, beim Benziner reicht auch der Sollwert. */
    fun lambdaPid(supported: Set<Int>): Pid? = listOf(Pid.LAMBDA_O2_B, Pid.LAMBDA_O2_A, Pid.LAMBDA_CMD)
        .firstOrNull { it.code in supported }

    /** Momentanverbrauch in l/100 km, oder null unter 5 km/h (dann ist l/h aussagekräftiger). */
    fun litersPer100Km(litersPerHour: Double, speedKmh: Double): Double? =
        if (speedKmh < 5.0) null else litersPerHour / speedKmh * 100.0
}
