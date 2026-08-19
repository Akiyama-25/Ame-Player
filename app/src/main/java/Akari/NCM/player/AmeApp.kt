package Akari.NCM.player

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AmeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("AME_DEBUG", "AmeApp onCreate started")
    }
}
