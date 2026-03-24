package org.dollos.service.action

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject

class ToggleWifiAction : Action {
    override val id = "toggle_wifi"
    override val name = "Toggle WiFi"
    override val description = "Turn WiFi on or off"
    override val parametersSchema = """{"type":"object","properties":{"enable":{"type":"boolean","description":"true to turn WiFi on, false to turn off"}},"required":["enable"]}"""
    override val confirmRequired = true

    companion object {
        private const val TAG = "ToggleWifiAction"
    }

    override fun execute(context: Context, params: JSONObject): ActionResult {
        val enable = params.optBoolean("enable", true)
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = enable

        val state = if (enable) "on" else "off"
        Log.i(TAG, "WiFi turned $state")
        return ActionResult(true, "WiFi turned $state")
    }
}
