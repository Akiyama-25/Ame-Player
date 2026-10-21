package Akari.NCM.player

import android.app.Application
import android.util.Log
import Akari.NCM.player.core.AppLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AmeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i("AME_DEBUG", "AmeApp onCreate started")
    }
}
