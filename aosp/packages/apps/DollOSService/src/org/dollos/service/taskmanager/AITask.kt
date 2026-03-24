package org.dollos.service.taskmanager

import org.json.JSONArray
import org.json.JSONObject

data class AITask(
    val id: String,
    val name: String,
    val description: String,
    val startTime: Long,
    val tokenUsage: Long,
    val estimatedCost: Double,
    val conversationContext: String,
    val status: Status
) {
    enum class Status {
        RUNNING, PAUSED, PENDING_CONFIRM
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("startTime", startTime)
            put("tokenUsage", tokenUsage)
            put("estimatedCost", estimatedCost)
            put("conversationContext", conversationContext)
            put("status", status.name)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): AITask {
            return AITask(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                startTime = json.optLong("startTime", 0),
                tokenUsage = json.optLong("tokenUsage", 0),
                estimatedCost = json.optDouble("estimatedCost", 0.0),
                conversationContext = json.optString("conversationContext", ""),
                status = Status.valueOf(json.optString("status", "RUNNING"))
            )
        }

        fun listFromJson(jsonStr: String): List<AITask> {
            val arr = JSONArray(jsonStr)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        fun listToJson(tasks: List<AITask>): String {
            val arr = JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
