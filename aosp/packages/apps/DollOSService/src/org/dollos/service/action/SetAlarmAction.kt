package org.dollos.service.action

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import org.json.JSONObject

class SetAlarmAction : Action {
    override val id = "set_alarm"
    override val name = "Set Alarm"
    override val description = "Set an alarm with specified time and optional label"
    override val parametersSchema = """{"type":"object","properties":{"hour":{"type":"integer","description":"Hour in 24-hour format (0-23)"},"minute":{"type":"integer","description":"Minute (0-59)"},"label":{"type":"string","description":"Optional label for the alarm"}},"required":["hour"]}"""
    override val confirmRequired = true

    companion object {
        private const val TAG = "SetAlarmAction"
    }

    override fun execute(context: Context, params: JSONObject): ActionResult {
        val hour = params.optInt("hour", -1)
        val minute = params.optInt("minute", 0)
        val label = params.optString("label", "DollOS Alarm")

        if (hour < 0 || hour > 23) {
            return ActionResult(false, "Invalid hour: $hour")
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        Log.i(TAG, "Alarm set for $hour:${minute.toString().padStart(2, '0')} - $label")
        return ActionResult(true, "Alarm set for $hour:${minute.toString().padStart(2, '0')}")
    }
}
