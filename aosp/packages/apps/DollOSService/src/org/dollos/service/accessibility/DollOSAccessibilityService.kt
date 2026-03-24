package org.dollos.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class DollOSAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DollOSA11yService"
        var instance: DollOSAccessibilityService? = null
            private set
    }

    lateinit var nodeReader: NodeReader
        private set
    lateinit var uiExecutor: UIExecutor
        private set
    lateinit var screenCapture: ScreenCapture
        private set
    lateinit var takeoverManager: TakeoverManager
        private set
    lateinit var appEventMonitor: AppEventMonitor
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        nodeReader = NodeReader(this)
        uiExecutor = UIExecutor(this)
        screenCapture = ScreenCapture(this)
        takeoverManager = TakeoverManager(this)
        appEventMonitor = AppEventMonitor()
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                appEventMonitor.onWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Used by AI operation loop to detect screen updates
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_POWER
            && event.action == KeyEvent.ACTION_DOWN
            && takeoverManager.isActive
        ) {
            takeoverManager.showInterruptModal()
            return true // consume the key event
        }
        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        takeoverManager.cleanup()
        Log.i(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }
}
