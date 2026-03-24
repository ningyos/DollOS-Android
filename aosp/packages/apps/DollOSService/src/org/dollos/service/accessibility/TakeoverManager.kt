package org.dollos.service.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import org.dollos.service.R

class TakeoverManager(private val service: DollOSAccessibilityService) {

    companion object {
        private const val TAG = "TakeoverManager"
    }

    var isActive: Boolean = false
        private set

    private var taskDescription: String = ""
    private var barView: View? = null
    private var touchBlocker: View? = null
    private var edgeGlow: View? = null
    private var interruptModal: View? = null
    private var onCancelListener: (() -> Unit)? = null

    private val windowManager: WindowManager
        get() = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * Start takeover mode. Shows floating bar + edge glow + touch blocker.
     */
    fun start(description: String, onCancel: () -> Unit) {
        if (isActive) return
        isActive = true
        taskDescription = description
        onCancelListener = onCancel

        addTouchBlocker()
        addEdgeGlow()
        addBar(description)

        Log.i(TAG, "Takeover started: $description")
    }

    /**
     * Stop takeover mode. Remove all overlays.
     */
    fun stop() {
        if (!isActive) return
        isActive = false
        removeAll()
        Log.i(TAG, "Takeover stopped")
    }

    /**
     * Show interrupt modal (triggered by power button press).
     */
    fun showInterruptModal() {
        if (interruptModal != null) return

        val inflater = LayoutInflater.from(service)
        interruptModal = inflater.inflate(R.layout.takeover_interrupt_modal, null)

        interruptModal!!.findViewById<TextView>(R.id.interrupt_task_desc).text = taskDescription

        interruptModal!!.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            dismissInterruptModal()
            stop()
            onCancelListener?.invoke()
        }

        interruptModal!!.findViewById<Button>(R.id.btn_continue).setOnClickListener {
            dismissInterruptModal()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(interruptModal, params)
        Log.d(TAG, "Interrupt modal shown")
    }

    fun cleanup() {
        removeAll()
    }

    private fun addTouchBlocker() {
        touchBlocker = FrameLayout(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        // This transparent overlay consumes all touch events
        touchBlocker!!.setOnTouchListener { _, _ -> true }
        windowManager.addView(touchBlocker, params)
    }

    private fun addEdgeGlow() {
        edgeGlow = FrameLayout(service).apply {
            setBackgroundResource(R.drawable.edge_glow)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(edgeGlow, params)
    }

    private fun addBar(description: String) {
        val inflater = LayoutInflater.from(service)
        barView = inflater.inflate(R.layout.takeover_bar, null)
        barView!!.findViewById<TextView>(R.id.takeover_text).text =
            service.getString(R.string.takeover_bar_text, description)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP
        windowManager.addView(barView, params)
    }

    private fun dismissInterruptModal() {
        interruptModal?.let {
            windowManager.removeView(it)
            interruptModal = null
        }
    }

    private fun removeAll() {
        dismissInterruptModal()
        barView?.let { windowManager.removeView(it); barView = null }
        edgeGlow?.let { windowManager.removeView(it); edgeGlow = null }
        touchBlocker?.let { windowManager.removeView(it); touchBlocker = null }
    }
}
