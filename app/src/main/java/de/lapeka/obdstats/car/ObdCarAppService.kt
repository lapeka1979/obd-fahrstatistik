package de.lapeka.obdstats.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class ObdCarAppService : CarAppService() {
    // Private App zum Selbst-Installieren: alle Android-Auto-Hosts zulassen.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = LiveScreen(carContext)
    }
}
