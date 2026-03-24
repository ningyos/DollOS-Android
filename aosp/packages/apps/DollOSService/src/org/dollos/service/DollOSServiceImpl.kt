package org.dollos.service

import android.content.Intent
import android.util.Log
import org.dollos.service.taskmanager.TaskManagerActivity
import org.json.JSONObject

class DollOSServiceImpl : IDollOSService.Stub() {

    companion object {
        private const val TAG = "DollOSServiceImpl"
        private const val KEY_PROVIDER = "api_provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_GMS_OPTIN = "gms_opted_in"
        private const val KEY_AI_NAME = "ai_name"
        private const val KEY_AI_DESCRIPTION = "ai_description"
    }

    override fun getVersion(): String {
        return DollOSApp.VERSION
    }

    override fun isAiConfigured(): Boolean {
        return DollOSApp.prefs.contains(KEY_API_KEY)
    }

    override fun getDataDirectory(): String {
        return DollOSApp.prefs.all.toString()
    }

    override fun setApiKey(provider: String, apiKey: String) {
        DollOSApp.prefs.edit()
            .putString(KEY_PROVIDER, provider)
            .putString(KEY_API_KEY, apiKey)
            .apply()
        Log.i(TAG, "API key saved for provider: $provider")
    }

    override fun setGmsOptIn(optIn: Boolean) {
        DollOSApp.prefs.edit()
            .putBoolean(KEY_GMS_OPTIN, optIn)
            .apply()
        Log.i(TAG, "GMS opt-in set to: $optIn")
    }

    override fun isGmsOptedIn(): Boolean {
        return DollOSApp.prefs.getBoolean(KEY_GMS_OPTIN, false)
    }

    override fun setPersonality(name: String, description: String) {
        DollOSApp.prefs.edit()
            .putString(KEY_AI_NAME, name)
            .putString(KEY_AI_DESCRIPTION, description)
            .apply()
        Log.i(TAG, "AI personality set: $name")
    }

    override fun getPersonalityName(): String {
        return DollOSApp.prefs.getString(KEY_AI_NAME, "") ?: ""
    }

    override fun executeSystemAction(actionId: String, paramsJson: String): String {
        val action = DollOSApp.actionRegistry.get(actionId)
            ?: return """{"success":false,"message":"Unknown action: $actionId"}"""

        val params = JSONObject(paramsJson)
        val result = action.execute(DollOSApp.instance, params)
        return """{"success":${result.success},"message":"${result.message}"}"""
    }

    override fun getAvailableActions(): String {
        return DollOSApp.actionRegistry.toToolDescriptions()
    }

    override fun showTaskManager() {
        val intent = Intent(DollOSApp.instance, TaskManagerActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        DollOSApp.instance.startActivity(intent)
    }
}
