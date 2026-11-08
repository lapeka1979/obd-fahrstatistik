package de.lapeka.obdstats.core

/**
 * Wertet Antworten eines ELM327-Adapters aus.
 * Erwartet die Einstellungen ATE0 (kein Echo), ATS0 (keine Leerzeichen) und ATH0 (keine Header),
 * kommt aber auch mit Leerzeichen zurecht.
 */
object ObdParser {

    private val errorMarkers = listOf(
        "NODATA", "ERROR", "UNABLETOCONNECT", "STOPPED", "BUFFERFULL", "CANERROR", "BUSBUSY", "?"
    )

    /** Bereinigte Zeilen: ohne Prompt, Leerzeichen, "SEARCHING..." und Zeilennummern wie "0:". */
    fun cleanLines(raw: String): List<String> =
        raw.replace(">", "")
            .split('\r', '\n')
            .map { it.replace(" ", "").trim().uppercase() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && !it.startsWith("BUSINIT") }
            .map { it.replace(Regex("^[0-9A-F]:"), "") }

    fun isError(raw: String): Boolean {
        val compact = raw.replace(" ", "").uppercase()
        return errorMarkers.any { compact.contains(it) }
    }

    /** Datenbytes einer Mode-01-Antwort für [pid], oder null. */
    fun mode01Data(raw: String, pid: Int): IntArray? {
        if (isError(raw)) return null
        val prefix = "41" + "%02X".format(pid)
        for (line in cleanLines(raw)) {
            if (line.startsWith(prefix)) {
                return hexBytes(line.substring(prefix.length))
            }
        }
        return null
    }

    fun hexBytes(hex: String): IntArray =
        hex.chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }.toIntArray()

    /**
     * Antwort auf 0100, 0120, 0140 ... (4 Byte Bitmaske) in die Liste unterstützter PIDs umrechnen.
     * Bit 7 des ersten Bytes steht für PID base+1.
     */
    fun supportedPids(base: Int, data: IntArray): Set<Int> {
        val result = mutableSetOf<Int>()
        for (i in 0 until minOf(32, data.size * 8)) {
            val byte = data[i / 8]
            if ((byte shr (7 - i % 8)) and 1 == 1) result += base + i + 1
        }
        return result
    }

    /**
     * Fehlercodes aus einer Mode-03-Antwort. Bei CAN steht nach "43" ein Zählbyte (ungerade Bytezahl),
     * bei älteren Protokollen folgen direkt Paare.
     */
    fun parseDtcs(raw: String): List<String> {
        if (isError(raw)) return emptyList()
        val codes = mutableListOf<String>()
        for (line in cleanLines(raw)) {
            val idx = line.indexOf("43")
            if (idx != 0) continue
            var bytes = hexBytes(line.substring(2)).toList()
            if (bytes.size % 2 == 1) bytes = bytes.drop(1)
            bytes.chunked(2).forEach { pair ->
                if (pair.size == 2 && (pair[0] != 0 || pair[1] != 0)) codes += decodeDtc(pair[0], pair[1])
            }
        }
        return codes.distinct()
    }

    fun decodeDtc(a: Int, b: Int): String {
        val system = "PCBU"[(a shr 6) and 0x3]
        val d1 = (a shr 4) and 0x3
        val d2 = a and 0xF
        return "%c%d%X%02X".format(system, d1, d2, b)
    }
}

/** Die PIDs, die die App nutzt, mit Umrechnung in physikalische Werte. */
enum class Pid(val code: Int, val label: String, val unit: String, private val decode: (IntArray) -> Double?) {
    ENGINE_LOAD(0x04, "Motorlast", "%", { d -> d.getOrNull(0)?.let { it * 100.0 / 255 } }),
    COOLANT_TEMP(0x05, "Kühlwasser", "°C", { d -> d.getOrNull(0)?.let { it - 40.0 } }),
    MAP(0x0B, "Ansaugdruck", "kPa", { d -> d.getOrNull(0)?.toDouble() }),
    RPM(0x0C, "Drehzahl", "U/min", { d -> if (d.size >= 2) (d[0] * 256 + d[1]) / 4.0 else null }),
    SPEED(0x0D, "Geschwindigkeit", "km/h", { d -> d.getOrNull(0)?.toDouble() }),
    INTAKE_TEMP(0x0F, "Ansauglufttemperatur", "°C", { d -> d.getOrNull(0)?.let { it - 40.0 } }),
    MAF(0x10, "Luftmasse", "g/s", { d -> if (d.size >= 2) (d[0] * 256 + d[1]) / 100.0 else null }),
    THROTTLE(0x11, "Drosselklappe", "%", { d -> d.getOrNull(0)?.let { it * 100.0 / 255 } }),
    RUNTIME(0x1F, "Laufzeit seit Start", "s", { d -> if (d.size >= 2) (d[0] * 256 + d[1]).toDouble() else null }),
    FUEL_LEVEL(0x2F, "Tankfüllstand", "%", { d -> d.getOrNull(0)?.let { it * 100.0 / 255 } }),
    DIST_SINCE_CLEAR(0x31, "km seit Fehlerlöschung", "km", { d -> if (d.size >= 2) (d[0] * 256 + d[1]).toDouble() else null }),
    VOLTAGE(0x42, "Bordspannung", "V", { d -> if (d.size >= 2) (d[0] * 256 + d[1]) / 1000.0 else null }),
    AMBIENT_TEMP(0x46, "Außentemperatur", "°C", { d -> d.getOrNull(0)?.let { it - 40.0 } }),
    OIL_TEMP(0x5C, "Öltemperatur", "°C", { d -> d.getOrNull(0)?.let { it - 40.0 } }),
    FUEL_RATE(0x5E, "Kraftstoffrate", "l/h", { d -> if (d.size >= 2) (d[0] * 256 + d[1]) / 20.0 else null }),
    /** Lambda aus der Breitband-Lambdasonde (ältere Nummerierung). */
    LAMBDA_O2_A(0x24, "Lambda Sonde 1", "", { d -> lambda(d) }),
    /** Lambda aus der Breitband-Lambdasonde, bei neueren Dieseln üblich. */
    LAMBDA_O2_B(0x34, "Lambda Sonde 1", "", { d -> lambda(d) }),
    LAMBDA_CMD(0x44, "Lambda Soll", "", { d -> lambda(d) }),
    TORQUE_ACTUAL(0x62, "Motormoment", "%", { d -> d.getOrNull(0)?.let { it - 125.0 } }),
    TORQUE_REF(0x63, "Referenz-Drehmoment", "Nm", { d -> if (d.size >= 2) (d[0] * 256 + d[1]).toDouble() else null });

    val command: String get() = "01%02X".format(code)

    fun parse(raw: String): Double? = ObdParser.mode01Data(raw, code)?.let(decode)

    companion object {
        private fun lambda(d: IntArray): Double? =
            if (d.size >= 2) (d[0] * 256 + d[1]) * 2.0 / 65536.0 else null

        fun byCode(code: Int): Pid? = entries.firstOrNull { it.code == code }
    }
}

/** Klartextnamen für häufige Standard-PIDs, damit die App zeigen kann, was das Auto liefert. */
object PidNames {
    private val names = mapOf(
        0x01 to "Status Fehlerspeicher / Abgasprüfungen",
        0x03 to "Status Kraftstoffsystem",
        0x04 to "Motorlast",
        0x05 to "Kühlwassertemperatur",
        0x06 to "Gemischkorrektur kurz (Bank 1)",
        0x07 to "Gemischkorrektur lang (Bank 1)",
        0x0A to "Kraftstoffdruck",
        0x0B to "Ansaugdruck",
        0x0C to "Drehzahl",
        0x0D to "Geschwindigkeit",
        0x0E to "Zündzeitpunkt",
        0x0F to "Ansauglufttemperatur",
        0x10 to "Luftmasse (MAF)",
        0x11 to "Drosselklappe",
        0x1C to "OBD-Norm des Fahrzeugs",
        0x1F to "Laufzeit seit Motorstart",
        0x21 to "km mit Motorkontrollleuchte",
        0x24 to "Lambda Sonde 1 (Breitband)",
        0x2C to "AGR Soll",
        0x2F to "Tankfüllstand",
        0x30 to "Warmlaufzyklen seit Fehlerlöschung",
        0x31 to "km seit Fehlerlöschung",
        0x33 to "Luftdruck",
        0x34 to "Lambda Sonde 1 (Breitband)",
        0x42 to "Bordspannung",
        0x43 to "Absolute Last",
        0x44 to "Lambda Soll",
        0x45 to "Relative Drosselklappe",
        0x46 to "Außentemperatur",
        0x49 to "Gaspedal D",
        0x4A to "Gaspedal E",
        0x4C to "Drosselklappe Soll",
        0x4D to "Minuten mit Motorkontrollleuchte",
        0x4E to "Minuten seit Fehlerlöschung",
        0x51 to "Kraftstoffart",
        0x5A to "Gaspedal relativ",
        0x5C to "Öltemperatur",
        0x5E to "Kraftstoffrate",
        0x61 to "Motormoment Soll",
        0x62 to "Motormoment Ist",
        0x63 to "Referenz-Drehmoment",
        0x7C to "Partikelfilter-Temperatur",
        0x83 to "NOx-Sensor",
        0xA6 to "Kilometerstand"
    )

    fun describe(pid: Int): String = names[pid] ?: "PID %02X".format(pid)

    /** PIDs 0x20, 0x40, ... sind nur "weitere PIDs unterstützt"-Marker. */
    fun isRangeMarker(pid: Int): Boolean = pid % 0x20 == 0
}
