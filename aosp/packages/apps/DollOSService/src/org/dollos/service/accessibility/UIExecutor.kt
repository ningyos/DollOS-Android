package org.dollos.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject

class UIExecutor(private val service: DollOSAccessibilityService) {

    companion object {
        private const val TAG = "UIExecutor"
    }

    /**
     * Execute a UI action from JSON command.
     * Returns JSON result: {"success": bool, "message": string}
     */
    fun execute(actionJson: String): String {
        return try {
            val cmd = JSONObject(actionJson)
            val type = cmd.getString("type")
            // displayId defaults to 0 (physical screen) if not specified
            val displayId = cmd.optInt("displayId", 0)

            when (type) {
                "click" -> performClick(cmd, displayId)
                "long_press" -> performLongPress(cmd, displayId)
                "input_text" -> performInputText(cmd, displayId)
                "swipe" -> performSwipe(cmd)
                "drag" -> performDrag(cmd)
                "multi_gesture" -> performMultiGesture(cmd)
                "back" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK, "back")
                "home" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME, "home")
                "recents" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "recents")
                else -> result(false, "Unknown action type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Action execution failed", e)
            result(false, "Error: ${e.message}")
        }
    }

    private fun performClick(cmd: JSONObject, displayId: Int): String {
        val node = findNodeById(cmd.getString("node_id"), displayId) ?: return result(false, "Node not found")
        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return result(success, if (success) "Clicked" else "Click failed")
    }

    private fun performLongPress(cmd: JSONObject, displayId: Int): String {
        val node = findNodeById(cmd.getString("node_id"), displayId) ?: return result(false, "Node not found")
        val success = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        return result(success, if (success) "Long pressed" else "Long press failed")
    }

    private fun performInputText(cmd: JSONObject, displayId: Int): String {
        val node = findNodeById(cmd.getString("node_id"), displayId) ?: return result(false, "Node not found")
        val text = cmd.getString("text")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return result(success, if (success) "Text set" else "Set text failed")
    }

    private fun performSwipe(cmd: JSONObject): String {
        val startX = cmd.getDouble("start_x").toFloat()
        val startY = cmd.getDouble("start_y").toFloat()
        val endX = cmd.getDouble("end_x").toFloat()
        val endY = cmd.getDouble("end_y").toFloat()
        val durationMs = cmd.optLong("duration_ms", 300)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Swipe completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Swipe cancelled")
            }
        }, null)

        return result(true, "Swipe dispatched")
    }

    private fun performDrag(cmd: JSONObject): String {
        val startX = cmd.getDouble("start_x").toFloat()
        val startY = cmd.getDouble("start_y").toFloat()
        val endX = cmd.getDouble("end_x").toFloat()
        val endY = cmd.getDouble("end_y").toFloat()
        val durationMs = cmd.optLong("duration_ms", 1000)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, null, null)
        return result(true, "Drag dispatched")
    }

    private fun performMultiGesture(cmd: JSONObject): String {
        val strokes = cmd.getJSONArray("strokes")
        val builder = GestureDescription.Builder()

        for (i in 0 until strokes.length()) {
            val s = strokes.getJSONObject(i)
            val path = Path().apply {
                moveTo(s.getDouble("start_x").toFloat(), s.getDouble("start_y").toFloat())
                lineTo(s.getDouble("end_x").toFloat(), s.getDouble("end_y").toFloat())
            }
            val startTime = s.optLong("start_time", 0)
            val duration = s.optLong("duration_ms", 300)
            builder.addStroke(GestureDescription.StrokeDescription(path, startTime, duration))
        }

        service.dispatchGesture(builder.build(), null, null)
        return result(true, "Multi-gesture dispatched")
    }

    private fun performGlobalAction(action: Int, name: String): String {
        val success = service.performGlobalAction(action)
        return result(success, if (success) "$name executed" else "$name failed")
    }

    /**
     * Find a node by its "node_N" ID on a specific display.
     * Uses getWindows() filtered by displayId for multi-display support.
     */
    private fun findNodeById(nodeId: String, displayId: Int): AccessibilityNodeInfo? {
        val index = nodeId.removePrefix("node_").toIntOrNull() ?: return null

        val windows = service.windows
        val window = windows.firstOrNull {
            it.displayId == displayId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        } ?: windows.firstOrNull {
            it.displayId == displayId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } ?: return null

        val root = window.root ?: return null

        var counter = 0
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (counter == index) return node
            counter++
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = find(child)
                if (result != null) return result
            }
            return null
        }

        return find(root)
    }

    private fun result(success: Boolean, message: String): String {
        return JSONObject().apply {
            put("success", success)
            put("message", message)
        }.toString()
    }
}
