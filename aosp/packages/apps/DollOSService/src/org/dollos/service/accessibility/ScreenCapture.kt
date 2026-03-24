package org.dollos.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream

class ScreenCapture(private val service: DollOSAccessibilityService) {

    companion object {
        private const val TAG = "ScreenCapture"
    }

    interface CaptureCallback {
        fun onCaptureResult(displayId: Int, pngBytes: ByteArray?)
    }

    /**
     * Capture screenshot from physical display using AccessibilityService.takeScreenshot().
     * API 30+ only. Async — result delivered via callback.
     */
    fun capturePhysicalScreen(displayId: Int, callback: CaptureCallback) {
        service.takeScreenshot(
            displayId,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback() {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )
                    if (bitmap != null) {
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                        callback.onCaptureResult(displayId, stream.toByteArray())
                        bitmap.recycle()
                        screenshot.hardwareBuffer.close()
                    } else {
                        Log.e(TAG, "Failed to create bitmap from screenshot")
                        callback.onCaptureResult(displayId, null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    callback.onCaptureResult(displayId, null)
                }
            }
        )
    }

    /**
     * Capture screenshot from a VirtualDisplay's ImageReader.
     * VirtualDisplayManager provides the ImageReader for each display.
     */
    fun captureVirtualDisplay(displayId: Int, imageReader: ImageReader, callback: CaptureCallback) {
        val image = imageReader.acquireLatestImage()
        if (image == null) {
            Log.w(TAG, "No image available from VirtualDisplay $displayId")
            callback.onCaptureResult(displayId, null)
            return
        }

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to actual width (remove padding)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()

            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 90, stream)
            callback.onCaptureResult(displayId, stream.toByteArray())
            cropped.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay capture failed", e)
            callback.onCaptureResult(displayId, null)
        } finally {
            image.close()
        }
    }
}
