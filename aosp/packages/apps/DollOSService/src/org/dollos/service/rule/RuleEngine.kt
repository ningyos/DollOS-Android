package org.dollos.service.rule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import org.dollos.service.accessibility.AppEventMonitor
import org.dollos.service.notification.NotificationRouter
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class RuleEngine(
    private val context: Context,
    private val ruleDao: RuleDao,
    private val notificationRouter: NotificationRouter
) : AppEventMonitor.AppEventListener {

    companion object {
        private const val TAG = "RuleEngine"
    }

    private var rules = listOf<Rule>()
    private val lastFired = ConcurrentHashMap<String, Long>()

    private var screenState = "on"
    private var chargingState = "discharging"
    private var wifiState = "disconnected"
    private var wifiSsid = ""
    private var bluetoothState = "disconnected"
    private var bluetoothDevice = ""
    private var batteryLevel = 100
    private var foregroundApp = ""

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenState = "on"
                    evaluate("SCREEN_STATE")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenState = "off"
                    evaluate("SCREEN_STATE")
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    chargingState = "charging"
                    evaluate("CHARGING_STATE")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    chargingState = "discharging"
                    evaluate("CHARGING_STATE")
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    wifiState = if (networkInfo?.isConnected == true) "connected" else "disconnected"

                    if (wifiState == "connected") {
                        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        wifiSsid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: ""
                    } else {
                        wifiSsid = ""
                    }

                    evaluate("WIFI_STATE")
                    if (wifiSsid.isNotEmpty()) evaluate("WIFI_SSID")
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryLevel = (level * 100) / scale
                        evaluate("BATTERY_LEVEL")
                    }
                }
                android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                    bluetoothState = if (state == android.bluetooth.BluetoothAdapter.STATE_CONNECTED) "connected" else "disconnected"
                    evaluate("BLUETOOTH_STATE")
                }
            }
        }
    }

    fun start() {
        rules = ruleDao.getEnabled()
        Log.i(TAG, "Loaded ${rules.size} enabled rules")

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(systemReceiver, filter)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        screenState = if (pm.isInteractive) "on" else "off"
    }

    fun stop() {
        try {
            context.unregisterReceiver(systemReceiver)
        } catch (_: Exception) {}
    }

    fun reloadRules() {
        rules = ruleDao.getEnabled()
        Log.d(TAG, "Reloaded ${rules.size} enabled rules")
    }

    override fun onAppChanged(packageName: String, eventType: String) {
        if (eventType == "opened") {
            foregroundApp = packageName
        }
        evaluate("APP_FOREGROUND")
    }

    private fun evaluate(triggerSource: String) {
        val now = System.currentTimeMillis()

        for (rule in rules) {
            if (!rule.enabled) continue

            val lastTime = lastFired[rule.id] ?: 0
            if (now - lastTime < rule.debouncePeriodMs) continue

            val relevant = rule.conditions.any { it.type.name == triggerSource }
            if (!relevant) continue

            val allMet = rule.conditions.all { checkCondition(it) }
            if (!allMet) continue

            lastFired[rule.id] = now
            triggerAction(rule)
        }
    }

    private fun checkCondition(condition: Condition): Boolean {
        val currentValue = when (condition.type) {
            ConditionType.SCREEN_STATE -> screenState
            ConditionType.CHARGING_STATE -> chargingState
            ConditionType.WIFI_STATE -> wifiState
            ConditionType.WIFI_SSID -> wifiSsid
            ConditionType.BLUETOOTH_STATE -> bluetoothState
            ConditionType.BLUETOOTH_DEVICE -> bluetoothDevice
            ConditionType.BATTERY_LEVEL -> batteryLevel.toString()
            ConditionType.APP_FOREGROUND -> foregroundApp
            ConditionType.TIME_RANGE -> "check_special"
            ConditionType.DAY_OF_WEEK -> "check_special"
        }

        if (condition.type == ConditionType.TIME_RANGE) {
            return checkTimeRange(condition.value)
        }
        if (condition.type == ConditionType.DAY_OF_WEEK) {
            return checkDayOfWeek(condition.value)
        }

        return when (condition.operator) {
            Operator.EQUALS -> currentValue == condition.value
            Operator.NOT_EQUALS -> currentValue != condition.value
            Operator.LESS_THAN -> {
                val a = currentValue.toIntOrNull() ?: return false
                val b = condition.value.toIntOrNull() ?: return false
                a < b
            }
            Operator.GREATER_THAN -> {
                val a = currentValue.toIntOrNull() ?: return false
                val b = condition.value.toIntOrNull() ?: return false
                a > b
            }
        }
    }

    private fun checkTimeRange(value: String): Boolean {
        val parts = value.split("-")
        if (parts.size != 2) return false
        val start = LocalTime.parse(parts[0].trim())
        val end = LocalTime.parse(parts[1].trim())
        val now = LocalTime.now()
        return if (start <= end) now in start..end else (now >= start || now <= end)
    }

    private fun checkDayOfWeek(value: String): Boolean {
        val days = value.split(",").map { it.trim().uppercase() }
        val today = DayOfWeek.from(java.time.LocalDate.now())
            .getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()
        return today in days
    }

    private fun triggerAction(rule: Rule) {
        Log.i(TAG, "Rule triggered: ${rule.name} (${rule.id})")

        when (rule.action) {
            RuleAction.NOTIFY -> {
                notificationRouter.route(
                    title = rule.name,
                    message = rule.naturalLanguage.ifEmpty { rule.name },
                    priority = "NORMAL",
                    eventType = "RULE"
                )
            }
            RuleAction.SPAWN_WORKER -> {
                Log.i(TAG, "SPAWN_WORKER action for rule ${rule.id}: ${rule.actionParams}")
            }
            RuleAction.SEND_EVENT -> {
                Log.i(TAG, "SEND_EVENT action for rule ${rule.id}: ${rule.actionParams}")
            }
        }
    }
}
