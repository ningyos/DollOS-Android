package org.dollos.service

import android.util.Log

class DollOSServiceImpl : IDollOSService.Stub() {

    companion object {
        private const val TAG = "DollOSServiceImpl"
        private const val KEY_PROVIDER = "api_provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_GMS_OPTIN = "gms_opted_in"
    }

    override fun getVersion(): String {
        return DollOSService.VERSION
    }

    override fun isAiConfigured(): Boolean {
        return DollOSService.prefs.contains(KEY_API_KEY)
    }

    override fun getDataDirectory(): String {
        return DollOSService.prefs.all.toString()
    }

    override fun setApiKey(provider: String, apiKey: String) {
        DollOSService.prefs.edit()
            .putString(KEY_PROVIDER, provider)
            .putString(KEY_API_KEY, apiKey)
            .apply()
        Log.i(TAG, "API key saved for provider: $provider")
    }

    override fun setGmsOptIn(optIn: Boolean) {
        DollOSService.prefs.edit()
            .putBoolean(KEY_GMS_OPTIN, optIn)
            .apply()
        Log.i(TAG, "GMS opt-in set to: $optIn")
    }

    override fun isGmsOptedIn(): Boolean {
        return DollOSService.prefs.getBoolean(KEY_GMS_OPTIN, false)
    }
}
