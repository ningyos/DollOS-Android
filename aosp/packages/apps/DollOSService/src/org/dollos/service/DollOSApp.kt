package org.dollos.service

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.dollos.service.action.ActionRegistry
import org.dollos.service.action.OpenAppAction
import org.dollos.service.action.SetAlarmAction
import org.dollos.service.action.ToggleWifiAction
import org.dollos.service.action.ToggleBluetoothAction

class DollOSApp : Application() {

    companion object {
        private const val TAG = "DollOSApp"
        const val VERSION = "0.1.0"
        const val PREFS_NAME = "dollos_config"
        lateinit var prefs: SharedPreferences
            private set
        lateinit var instance: DollOSApp
            private set
        lateinit var actionRegistry: ActionRegistry
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val deContext = createDeviceProtectedStorageContext()
        prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "DollOS Application initialized, version $VERSION")
        Log.i(TAG, "SharedPreferences: ${prefs.all.size} entries")

        actionRegistry = ActionRegistry()
        actionRegistry.register(OpenAppAction())
        actionRegistry.register(SetAlarmAction())
        actionRegistry.register(ToggleWifiAction())
        actionRegistry.register(ToggleBluetoothAction())
        Log.i(TAG, "Registered ${actionRegistry.getAll().size} actions")
    }
}
