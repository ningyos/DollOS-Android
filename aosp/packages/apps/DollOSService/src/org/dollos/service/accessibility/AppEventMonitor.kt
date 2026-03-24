package org.dollos.service.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AppEventMonitor {

    companion object {
        private const val TAG = "AppEventMonitor"
    }

    var currentForegroundPackage: String = ""
        private set

    interface AppEventListener {
        fun onAppChanged(packageName: String, eventType: String)
    }

    private var listener: AppEventListener? = null

    fun setListener(listener: AppEventListener?) {
        this.listener = listener
    }

    fun onWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Filter out system UI and keyboard packages
        if (packageName == "com.android.systemui") return
        if (packageName.contains("inputmethod")) return

        if (packageName != currentForegroundPackage) {
            val previousPackage = currentForegroundPackage
            currentForegroundPackage = packageName

            if (previousPackage.isNotEmpty()) {
                listener?.onAppChanged(previousPackage, "closed")
                Log.d(TAG, "App closed: $previousPackage")
            }
            listener?.onAppChanged(packageName, "opened")
            Log.d(TAG, "App opened: $packageName")
        }
    }
}
