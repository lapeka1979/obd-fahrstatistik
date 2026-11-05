# Fahrstatistik per OBD2

Android-App, die über einen ELM327-kompatiblen OBD2-Adapter Fahrten aufzeichnet:
Strecke, Dauer, Verbrauch, Höchstgeschwindigkeit, Standzeit. Dazu Live-Werte, Fehlerspeicher,
Wochen- und Monatsstatistik und CSV-Export. In Android Auto gibt es eine schlichte Anzeige.

## Was die App kann

- **Verbindung:** Bluetooth-Classic-Adapter (die meisten ELM327, vLinker FS, OBDLink MX+) oder WLAN-Adapter.
  Reine Bluetooth-LE-Adapter werden noch nicht unterstützt.
- **Verbrauch:** aus der Kraftstoffrate des Steuergeräts (PID 5E), sonst aus der Luftmasse (MAF),
  sonst geschätzt aus dem Ansaugdruck. Mit dem Korrekturfaktor lässt sich das an echte Tankfüllungen anpassen.
  Bei Diesel ohne PID 5E ist die MAF-Rechnung nur grob, weil Diesel mager läuft.
- **Fahrten:** Solange die Aufzeichnung läuft, verbindet sich die App alle 10 s neu. Zündung aus beendet die Fahrt,
  Zündung an startet automatisch eine neue. Fahrten unter 100 m werden verworfen. Langer Druck auf eine Fahrt löscht sie.
- **Fehlerspeicher:** Fehlercodes (Mode 03) lesen. Löschen ist bewusst nicht eingebaut.
- **Was dein Auto liefert:** Die App fragt beim Verbinden die unterstützten Standardwerte ab und listet sie auf.

## APK bekommen

Jeder Push baut über GitHub Actions eine Debug-APK: im Repo unter **Actions → APK bauen → letzter Lauf → Artifacts**
`obd-fahrstatistik-apk` herunterladen, entpacken und auf dem Handy installieren.

## In Android Auto anzeigen

Eine Fahrstatistik gehört zu keiner Kategorie, die Google im Play Store für Android Auto zulässt.
Die App meldet sich deshalb als IoT-App an und wird nur angezeigt, wenn unbekannte Quellen erlaubt sind:

1. Android-Auto-Einstellungen am Handy öffnen, ganz unten zehnmal auf **Version** tippen → Entwicklermodus.
2. Oben rechts im Menü **Entwicklereinstellungen** → **Unbekannte Quellen** einschalten.
3. Android Auto neu verbinden, dann taucht **Fahrstatistik** im App-Launcher auf.

## Erste Schritte

1. Adapter einstecken, Zündung an, Adapter in den Bluetooth-Einstellungen des Handys koppeln (PIN meist 1234 oder 0000).
2. App öffnen → **Bluetooth-Adapter wählen** → Kraftstoff und Hubraum einstellen → **Einstellungen speichern**.
3. **Aufzeichnung starten**. Die erste Verbindung dauert ein paar Sekunden, weil der Adapter das Protokoll sucht.

## Entwicklung

- Kotlin, Android Car App Library 1.4, minSdk 26.
- Reine Logik (Parser, Verbrauchsrechnung, Statistik, CSV) liegt in `core/` und hat Unit-Tests:
  `./gradlew testDebugUnitTest`.
