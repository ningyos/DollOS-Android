package org.dollos.service.action

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import org.json.JSONObject

class ToggleBluetoothAction : Action {
    override val id = "toggle_bluetooth"
    override val name = "Toggle Bluetooth"
    override val description = "Turn Bluetooth on or off"
    override val parametersSchema = """{"type":"object","properties":{"enable":{"type":"boolean","description":"true to turn Bluetooth on, false to turn off"}},"required":["enable"]}"""
    override val confirmRequired = true

    companion object {
        private const val TAG = "ToggleBluetoothAction"
    }

    override fun execute(context: Context, params: JSONObject): ActionResult {
        val enable = params.optBoolean("enable", true)
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return ActionResult(false, "Bluetooth not available")

        @Suppress("DEPRECATION")
        val result = if (enable) adapter.enable() else adapter.disable()

        val state = if (enable) "on" else "off"
        if (result) {
            Log.i(TAG, "Bluetooth turned $state")
            return ActionResult(true, "Bluetooth turned $state")
        } else {
            return ActionResult(false, "Failed to turn Bluetooth $state")
        }
    }
}
