package org.dollos.service.action

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject

class OpenAppAction : Action {
    override val id = "open_app"
    override val name = "Open App"
    override val description = "Open an application by package name or app name"
    override val parametersSchema = """{"type":"object","properties":{"package_name":{"type":"string","description":"The package name of the app to open (e.g. com.android.settings)"},"app_name":{"type":"string","description":"The display name of the app to open (e.g. Settings)"}},"required":[]}"""
    override val confirmRequired = false

    companion object {
        private const val TAG = "OpenAppAction"
    }

    override fun execute(context: Context, params: JSONObject): ActionResult {
        val packageName = params.optString("package_name", "")
        val appName = params.optString("app_name", "")

        val intent = if (packageName.isNotBlank()) {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } else if (appName.isNotBlank()) {
            findAppByName(context, appName)
        } else {
            return ActionResult(false, "No package name or app name provided")
        }

        if (intent == null) {
            return ActionResult(false, "App not found: ${packageName.ifBlank { appName }}")
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "Opened app: ${packageName.ifBlank { appName }}")
        return ActionResult(true, "App opened")
    }

    private fun findAppByName(context: Context, name: String): Intent? {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.find {
            pm.getApplicationLabel(it).toString().equals(name, ignoreCase = true)
        }
        return match?.let { pm.getLaunchIntentForPackage(it.packageName) }
    }
}
