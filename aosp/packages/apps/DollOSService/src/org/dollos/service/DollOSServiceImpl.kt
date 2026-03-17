package org.dollos.service

import android.util.Log
import java.io.File
import org.json.JSONObject

class DollOSServiceImpl : IDollOSService.Stub() {

    companion object {
        private const val TAG = "DollOSServiceImpl"
        private const val KEYSTORE_ALIAS = "dollos_api_key"
    }

    override fun getVersion(): String {
        return DollOSService.VERSION
    }

    override fun isAiConfigured(): Boolean {
        val configFile = File("${DollOSService.DATA_DIR}/config/api_key.json")
        return configFile.exists() && configFile.length() > 0
    }

    override fun getDataDirectory(): String {
        return DollOSService.DATA_DIR
    }

    override fun setApiKey(provider: String, apiKey: String) {
        val encryptedKey = encryptWithKeyStore(apiKey)
        val config = JSONObject().apply {
            put("provider", provider)
            put("api_key_encrypted", encryptedKey)
        }
        val configFile = File("${DollOSService.DATA_DIR}/config/api_key.json")
        configFile.writeText(config.toString())
        Log.i(TAG, "API key saved for provider: $provider")
    }

    override fun setGmsOptIn(optIn: Boolean) {
        val configFile = File("${DollOSService.DATA_DIR}/config/gms_optin.json")
        val config = JSONObject().apply {
            put("opted_in", optIn)
        }
        configFile.writeText(config.toString())
        Log.i(TAG, "GMS opt-in set to: $optIn")
    }

    override fun isGmsOptedIn(): Boolean {
        val configFile = File("${DollOSService.DATA_DIR}/config/gms_optin.json")
        if (!configFile.exists()) return false
        val config = JSONObject(configFile.readText())
        return config.optBoolean("opted_in", false)
    }

    private fun encryptWithKeyStore(plaintext: String): String {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGenerator = javax.crypto.KeyGenerator.getInstance(
                javax.crypto.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            keyGenerator.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            keyGenerator.generateKey()
        }

        val key = keyStore.getKey(KEYSTORE_ALIAS, null) as javax.crypto.SecretKey
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray())
        val combined = iv + encrypted
        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }
}
