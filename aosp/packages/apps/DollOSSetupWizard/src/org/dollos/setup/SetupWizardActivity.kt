package org.dollos.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import org.dollos.service.IDollOSService

class SetupWizardActivity : AppCompatActivity() {

    private val pageKeys = listOf(
        "welcome", "wifi", "gms", "model_download",
        "api_key", "personality", "voice", "complete"
    )

    private var currentPageIndex = 0
    var dollOSService: IDollOSService? = null
        private set
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            dollOSService = IDollOSService.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            dollOSService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        val intent = Intent("org.dollos.service.IDollOSService")
        intent.setPackage("org.dollos.service")
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        showPage(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    fun nextPage() {
        if (currentPageIndex < pageKeys.size - 1) {
            currentPageIndex++
            showPage(currentPageIndex)
        } else {
            finishSetup()
        }
    }

    fun previousPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--
            showPage(currentPageIndex)
        }
    }

    fun skipToPage(pageIndex: Int) {
        currentPageIndex = pageIndex
        showPage(currentPageIndex)
    }

    fun getPageIndex(key: String): Int = pageKeys.indexOf(key)

    private fun showPage(index: Int) {
        val fragment = when (pageKeys[index]) {
            "welcome" -> WelcomePage()
            "wifi" -> WifiPage()
            "gms" -> GmsPage()
            "model_download" -> ModelDownloadPage()
            "api_key" -> ApiKeyPage()
            "personality" -> PersonalityPage()
            "voice" -> VoicePage()
            "complete" -> CompletePage()
            else -> WelcomePage()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.page_container, fragment)
            .commit()
    }

    private fun finishSetup() {
        Settings.Global.putInt(contentResolver, Settings.Global.DEVICE_PROVISIONED, 1)
        Settings.Secure.putInt(contentResolver, Settings.Secure.USER_SETUP_COMPLETE, 1)

        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
