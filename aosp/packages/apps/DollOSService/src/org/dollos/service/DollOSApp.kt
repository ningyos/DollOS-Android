package org.dollos.service

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class DollOSApp : Application() {

    companion object {
        private const val TAG = "DollOSApp"
        const val VERSION = "0.1.0"
        const val PREFS_NAME = "dollos_config"
        lateinit var prefs: SharedPreferences
            private set
    }

    override fun onCreate() {
        super.onCreate()
        val deContext = createDeviceProtectedStorageContext()
        prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "DollOS Application initialized, version $VERSION")
        Log.i(TAG, "SharedPreferences: ${prefs.all.size} entries")
    }
}
