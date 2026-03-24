package org.dollos.service.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

class NotificationRouter(private val context: Context) {

    companion object {
        private const val TAG = "NotificationRouter"
        const val CHANNEL_SILENT = "dollos_silent"
        const val CHANNEL_NORMAL = "dollos_normal"
        const val CHANNEL_URGENT = "dollos_urgent"
    }

    var quietHours = QuietHoursConfig()
    var tts: TTSInterface = NoOpTTS()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val perEventOverrides = mutableMapOf<String, NotificationLevel>()
    private var notificationId = 1000

    init {
        createChannels()
    }

    fun setOverride(eventType: String, level: NotificationLevel) {
        perEventOverrides[eventType] = level
    }

    fun removeOverride(eventType: String) {
        perEventOverrides.remove(eventType)
    }

    fun route(title: String, message: String, priority: String, eventType: String) {
        val level = decide(priority, eventType)
        dispatch(title, message, level)
        Log.d(TAG, "Routed notification: level=$level, priority=$priority, eventType=$eventType")
    }

    private fun decide(priority: String, eventType: String): NotificationLevel {
        if (isDndOn()) return NotificationLevel.SILENT
        if (quietHours.isQuietNow()) return NotificationLevel.SILENT
        perEventOverrides[eventType]?.let { return it }

        return when (priority) {
            "HIGH" -> if (isScreenOff()) NotificationLevel.URGENT else NotificationLevel.NORMAL
            "NORMAL" -> NotificationLevel.NORMAL
            "LOW" -> NotificationLevel.SILENT
            else -> NotificationLevel.NORMAL
        }
    }

    private fun dispatch(title: String, message: String, level: NotificationLevel) {
        val channelId = when (level) {
            NotificationLevel.SILENT -> CHANNEL_SILENT
            NotificationLevel.NORMAL -> CHANNEL_NORMAL
            NotificationLevel.URGENT -> CHANNEL_URGENT
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId++, notification)

        if (level == NotificationLevel.URGENT && tts.isAvailable) {
            tts.speak("$title. $message", 1)
        }
    }

    private fun isDndOn(): Boolean {
        return notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun isScreenOff(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return !pm.isInteractive
    }

    private fun createChannels() {
        val silent = NotificationChannel(CHANNEL_SILENT, "DollOS Silent", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }

        val normal = NotificationChannel(CHANNEL_NORMAL, "DollOS Normal", NotificationManager.IMPORTANCE_DEFAULT)

        val urgent = NotificationChannel(CHANNEL_URGENT, "DollOS Urgent", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        }

        notificationManager.createNotificationChannel(silent)
        notificationManager.createNotificationChannel(normal)
        notificationManager.createNotificationChannel(urgent)

        Log.i(TAG, "Notification channels created")
    }
}
