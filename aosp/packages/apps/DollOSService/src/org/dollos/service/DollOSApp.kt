package org.dollos.service

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
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
        lateinit var virtualDisplayManager: org.dollos.service.accessibility.VirtualDisplayManager
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

        virtualDisplayManager = org.dollos.service.accessibility.VirtualDisplayManager(this)
        enableAccessibilityService()
    }

    private fun enableAccessibilityService() {
        val componentName = ComponentName(
            this,
            "org.dollos.service.accessibility.DollOSAccessibilityService"
        ).flattenToString()

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        if (!enabledServices.contains(componentName)) {
            val newValue = if (enabledServices.isEmpty()) {
                componentName
            } else {
                "$enabledServices:$componentName"
            }
            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newValue
            )
            Settings.Secure.putInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.i(TAG, "Auto-enabled AccessibilityService: $componentName")
        } else {
            Log.d(TAG, "AccessibilityService already enabled")
        }
    }
}
