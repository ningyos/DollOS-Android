package org.dollos.service.notification

import java.time.LocalTime

data class QuietHoursConfig(
    val enabled: Boolean = true,
    val startTime: LocalTime = LocalTime.of(23, 0),
    val endTime: LocalTime = LocalTime.of(7, 0)
) {
    fun isQuietNow(): Boolean {
        if (!enabled) return false
        val now = LocalTime.now()
        return if (startTime <= endTime) {
            now in startTime..endTime
        } else {
            now >= startTime || now <= endTime
        }
    }
}
