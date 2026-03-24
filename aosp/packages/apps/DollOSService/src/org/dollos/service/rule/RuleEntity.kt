package org.dollos.service.rule

import android.content.ContentValues
import android.database.Cursor

object RuleTable {
    const val TABLE_NAME = "rules"

    const val COL_ID = "id"
    const val COL_NAME = "name"
    const val COL_ENABLED = "enabled"
    const val COL_CONDITIONS_JSON = "conditions_json"
    const val COL_ACTION = "action"
    const val COL_ACTION_PARAMS = "action_params"
    const val COL_CREATED_BY = "created_by"
    const val COL_NATURAL_LANGUAGE = "natural_language"
    const val COL_DEBOUNCE_MS = "debounce_period_ms"
    const val COL_CREATED_AT = "created_at"

    const val CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS $TABLE_NAME (
            $COL_ID TEXT PRIMARY KEY,
            $COL_NAME TEXT NOT NULL,
            $COL_ENABLED INTEGER NOT NULL DEFAULT 1,
            $COL_CONDITIONS_JSON TEXT NOT NULL,
            $COL_ACTION TEXT NOT NULL,
            $COL_ACTION_PARAMS TEXT NOT NULL DEFAULT '{}',
            $COL_CREATED_BY TEXT NOT NULL DEFAULT 'user',
            $COL_NATURAL_LANGUAGE TEXT NOT NULL DEFAULT '',
            $COL_DEBOUNCE_MS INTEGER NOT NULL DEFAULT 60000,
            $COL_CREATED_AT INTEGER NOT NULL
        )
    """

    fun toContentValues(rule: Rule): ContentValues = ContentValues().apply {
        put(COL_ID, rule.id)
        put(COL_NAME, rule.name)
        put(COL_ENABLED, if (rule.enabled) 1 else 0)
        put(COL_CONDITIONS_JSON, org.json.JSONArray().apply { rule.conditions.forEach { put(it.toJson()) } }.toString())
        put(COL_ACTION, rule.action.name)
        put(COL_ACTION_PARAMS, rule.actionParams)
        put(COL_CREATED_BY, rule.createdBy)
        put(COL_NATURAL_LANGUAGE, rule.naturalLanguage)
        put(COL_DEBOUNCE_MS, rule.debouncePeriodMs)
        put(COL_CREATED_AT, rule.createdAt)
    }

    fun fromCursor(cursor: Cursor): Rule {
        val conditionsRaw = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONDITIONS_JSON))
        val conditions = if (conditionsRaw.isEmpty() || conditionsRaw == "[]") emptyList() else {
            val arr = org.json.JSONArray(conditionsRaw)
            (0 until arr.length()).map { Condition.fromJson(arr.getJSONObject(it)) }
        }
        return Rule(
            id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)),
            enabled = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ENABLED)) == 1,
            conditions = conditions,
            action = RuleAction.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_ACTION))),
            actionParams = cursor.getString(cursor.getColumnIndexOrThrow(COL_ACTION_PARAMS)),
            createdBy = cursor.getString(cursor.getColumnIndexOrThrow(COL_CREATED_BY)),
            naturalLanguage = cursor.getString(cursor.getColumnIndexOrThrow(COL_NATURAL_LANGUAGE)),
            debouncePeriodMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DEBOUNCE_MS)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT))
        )
    }
}
