package org.dollos.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log

class DollOSService : Service() {

    companion object {
        private const val TAG = "DollOSService"
        const val VERSION = "0.1.0"
        const val PREFS_NAME = "dollos_config"
        lateinit var prefs: SharedPreferences
            private set
    }

    private lateinit var binder: DollOSServiceImpl

    override fun onCreate() {
        super.onCreate()
        // Use device-protected storage for SharedPreferences (works with directBootAware)
        val deContext = createDeviceProtectedStorageContext()
        prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binder = DollOSServiceImpl()
        Log.i(TAG, "DollOS Service starting, version $VERSION")
        Log.i(TAG, "SharedPreferences initialized: ${prefs.all.size} entries")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
