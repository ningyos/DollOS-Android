package org.dollos.service.rule

import org.json.JSONArray
import org.json.JSONObject

enum class ConditionType {
    SCREEN_STATE,
    CHARGING_STATE,
    WIFI_STATE,
    WIFI_SSID,
    BLUETOOTH_STATE,
    BLUETOOTH_DEVICE,
    BATTERY_LEVEL,
    APP_FOREGROUND,
    TIME_RANGE,
    DAY_OF_WEEK
}

enum class Operator {
    EQUALS,
    NOT_EQUALS,
    LESS_THAN,
    GREATER_THAN
}

enum class RuleAction {
    NOTIFY,
    SPAWN_WORKER,
    SEND_EVENT
}

data class Condition(
    val type: ConditionType,
    val operator: Operator,
    val value: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("operator", operator.name)
        put("value", value)
    }

    companion object {
        fun fromJson(json: JSONObject): Condition = Condition(
            type = ConditionType.valueOf(json.getString("type")),
            operator = Operator.valueOf(json.getString("operator")),
            value = json.getString("value")
        )
    }
}

data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val conditions: List<Condition>,
    val action: RuleAction,
    val actionParams: String = "{}",
    val createdBy: String = "user",
    val naturalLanguage: String = "",
    val debouncePeriodMs: Long = 60_000,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("conditions", JSONArray().apply {
            conditions.forEach { put(it.toJson()) }
        })
        put("action", action.name)
        put("actionParams", actionParams)
        put("createdBy", createdBy)
        put("naturalLanguage", naturalLanguage)
        put("debouncePeriodMs", debouncePeriodMs)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): Rule {
            val conditionsArray = json.getJSONArray("conditions")
            val conditions = (0 until conditionsArray.length()).map {
                Condition.fromJson(conditionsArray.getJSONObject(it))
            }
            return Rule(
                id = json.getString("id"),
                name = json.getString("name"),
                enabled = json.optBoolean("enabled", true),
                conditions = conditions,
                action = RuleAction.valueOf(json.getString("action")),
                actionParams = json.optString("actionParams", "{}"),
                createdBy = json.optString("createdBy", "user"),
                naturalLanguage = json.optString("naturalLanguage", ""),
                debouncePeriodMs = json.optLong("debouncePeriodMs", 60_000),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
