package Akari.NCM.player

import android.app.Application
import Akari.NCM.player.core.AppLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AmeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        Akari.NCM.player.data.ThemePrefs.init(this)
        AppLogger.i("AME_DEBUG", "AmeApp onCreate started")
    }
}
