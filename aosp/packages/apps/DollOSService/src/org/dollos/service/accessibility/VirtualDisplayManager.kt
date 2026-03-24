package org.dollos.service.accessibility

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.DisplayMetrics
import android.util.Log

class VirtualDisplayManager(private val context: Context) {

    companion object {
        private const val TAG = "VirtualDisplayManager"
        private const val DISPLAY_NAME = "DollOS-AI-Display"
    }

    data class ManagedDisplay(
        val virtualDisplay: VirtualDisplay,
        val imageReader: ImageReader,
        val displayId: Int
    )

    private val displays = mutableMapOf<Int, ManagedDisplay>()
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /**
     * Create a new VirtualDisplay with the same resolution as the physical screen.
     * Returns the displayId.
     */
    fun create(width: Int, height: Int): Int {
        val metrics = DisplayMetrics()
        val defaultDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        defaultDisplay.getRealMetrics(metrics)

        val w = if (width > 0) width else metrics.widthPixels
        val h = if (height > 0) height else metrics.heightPixels
        val dpi = metrics.densityDpi

        val imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)

        val virtualDisplay = displayManager.createVirtualDisplay(
            DISPLAY_NAME,
            w, h, dpi,
            imageReader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )

        val displayId = virtualDisplay.display.displayId
        displays[displayId] = ManagedDisplay(virtualDisplay, imageReader, displayId)

        Log.i(TAG, "Created VirtualDisplay $displayId (${w}x${h})")
        return displayId
    }

    /**
     * Launch an app on the specified VirtualDisplay.
     */
    fun launchApp(packageName: String, displayId: Int) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "No launch intent for package: $packageName")
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = displayId

        context.startActivity(launchIntent, options.toBundle())
        Log.i(TAG, "Launched $packageName on display $displayId")
    }

    /**
     * Get the ImageReader for a managed display (for screenshot capture).
     */
    fun getImageReader(displayId: Int): ImageReader? = displays[displayId]?.imageReader

    /**
     * Destroy a VirtualDisplay and clean up resources.
     */
    fun destroy(displayId: Int) {
        val managed = displays.remove(displayId)
        if (managed != null) {
            managed.virtualDisplay.release()
            managed.imageReader.close()
            Log.i(TAG, "Destroyed VirtualDisplay $displayId")
        }
    }

    /**
     * Destroy all managed VirtualDisplays.
     */
    fun destroyAll() {
        displays.keys.toList().forEach { destroy(it) }
    }
}
