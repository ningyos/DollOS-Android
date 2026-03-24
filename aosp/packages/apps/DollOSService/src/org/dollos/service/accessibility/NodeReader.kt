package org.dollos.service.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

class NodeReader(private val service: DollOSAccessibilityService) {

    companion object {
        private const val TAG = "NodeReader"
        private const val MAX_DEPTH = 30
    }

    /**
     * Read the node tree from a specific display.
     * Returns JSON string matching the spec format.
     */
    fun readScreen(displayId: Int): String {
        val windows = service.windows
        val targetWindows = windows.filter { it.displayId == displayId }

        if (targetWindows.isEmpty()) {
            Log.w(TAG, "No windows found on display $displayId")
            return JSONObject().apply {
                put("package", "")
                put("displayId", displayId)
                put("nodes", JSONArray())
            }.toString()
        }

        // Use the focused window, or first app window
        val window = targetWindows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        } ?: targetWindows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } ?: targetWindows.first()

        val root = window.root ?: return JSONObject().apply {
            put("package", "")
            put("displayId", displayId)
            put("nodes", JSONArray())
        }.toString()

        val result = JSONObject()
        result.put("package", root.packageName?.toString() ?: "")
        result.put("displayId", displayId)

        val nodes = JSONArray()
        var nodeCounter = 0
        val nodeMap = mutableMapOf<AccessibilityNodeInfo, String>()

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return

            val nodeId = "node_$nodeCounter"
            nodeCounter++
            nodeMap[node] = nodeId

            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)

            val childIds = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val childId = "node_$nodeCounter"
                childIds.put(childId)
                traverse(child, depth + 1)
            }

            val obj = JSONObject()
            obj.put("id", nodeId)
            obj.put("class", node.className?.toString() ?: "")
            obj.put("text", node.text?.toString() ?: "")
            obj.put("resourceId", node.viewIdResourceName ?: "")
            obj.put("contentDescription", node.contentDescription?.toString() ?: "")
            obj.put("bounds", JSONArray().apply {
                put(rect.left); put(rect.top); put(rect.right); put(rect.bottom)
            })
            obj.put("clickable", node.isClickable)
            obj.put("enabled", node.isEnabled)
            obj.put("focused", node.isFocused)
            obj.put("scrollable", node.isScrollable)
            obj.put("children", childIds)

            nodes.put(obj)
        }

        traverse(root, 0)
        result.put("nodes", nodes)

        Log.d(TAG, "Read ${nodeCounter} nodes from display $displayId")
        return result.toString()
    }
}
