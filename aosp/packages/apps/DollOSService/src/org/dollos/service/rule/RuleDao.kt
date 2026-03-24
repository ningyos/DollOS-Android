package org.dollos.service.rule

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class RuleDao(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "RuleDao"
        private const val DB_NAME = "dollos_rules.db"
        private const val DB_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(RuleTable.CREATE_TABLE)
        Log.i(TAG, "Rules database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(rule: Rule) {
        writableDatabase.insertWithOnConflict(
            RuleTable.TABLE_NAME,
            null,
            RuleTable.toContentValues(rule),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun update(rule: Rule) {
        writableDatabase.update(
            RuleTable.TABLE_NAME,
            RuleTable.toContentValues(rule),
            "${RuleTable.COL_ID} = ?",
            arrayOf(rule.id)
        )
    }

    fun delete(ruleId: String) {
        writableDatabase.delete(
            RuleTable.TABLE_NAME,
            "${RuleTable.COL_ID} = ?",
            arrayOf(ruleId)
        )
    }

    fun setEnabled(ruleId: String, enabled: Boolean) {
        val cv = android.content.ContentValues().apply {
            put(RuleTable.COL_ENABLED, if (enabled) 1 else 0)
        }
        writableDatabase.update(
            RuleTable.TABLE_NAME,
            cv,
            "${RuleTable.COL_ID} = ?",
            arrayOf(ruleId)
        )
    }

    fun getAll(): List<Rule> {
        val rules = mutableListOf<Rule>()
        val cursor = readableDatabase.query(
            RuleTable.TABLE_NAME, null, null, null, null, null,
            "${RuleTable.COL_CREATED_AT} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                rules.add(RuleTable.fromCursor(it))
            }
        }
        return rules
    }

    fun getEnabled(): List<Rule> {
        val rules = mutableListOf<Rule>()
        val cursor = readableDatabase.query(
            RuleTable.TABLE_NAME, null,
            "${RuleTable.COL_ENABLED} = 1",
            null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                rules.add(RuleTable.fromCursor(it))
            }
        }
        return rules
    }
}
