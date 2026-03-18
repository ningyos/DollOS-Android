package org.dollos.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.dollos.service.IDollOSService

class SetupWizardActivity : AppCompatActivity() {

    private val pageKeys = listOf(
        "welcome", "wifi", "gms", "model_download",
        "api_key", "personality", "voice", "complete"
    )

    private val skippablePages = setOf("model_download", "api_key")
    private val skipTargets = mapOf(
        "api_key" to "voice"
    )

    var dollOSService: IDollOSService? = null
        private set
    private var isBound = false

    private lateinit var viewPager: ViewPager2
    private lateinit var btnBack: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnSkip: TextView
    private lateinit var dotContainer: LinearLayout
    private val dots = mutableListOf<ImageView>()

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

        viewPager = findViewById(R.id.view_pager)
        btnBack = findViewById(R.id.btn_back)
        btnNext = findViewById(R.id.btn_next)
        btnSkip = findViewById(R.id.btn_skip)
        dotContainer = findViewById(R.id.dot_container)

        viewPager.adapter = SetupPagerAdapter(this)
        viewPager.isUserInputEnabled = false

        setupDots()
        updateUI(0)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateUI(position)
            }
        })

        btnNext.setOnClickListener {
            val current = viewPager.currentItem
            if (current < pageKeys.size - 1) {
                val fragment = supportFragmentManager.findFragmentByTag("f${current}")
                if (fragment is SetupPage) {
                    fragment.onNext()
                }
                viewPager.setCurrentItem(current + 1, true)
            } else {
                finishSetup()
            }
        }

        btnBack.setOnClickListener {
            val current = viewPager.currentItem
            if (current > 0) {
                viewPager.setCurrentItem(current - 1, true)
            }
        }

        btnSkip.setOnClickListener {
            val current = viewPager.currentItem
            val currentKey = pageKeys[current]
            val targetKey = skipTargets[currentKey]
            if (targetKey != null) {
                viewPager.setCurrentItem(pageKeys.indexOf(targetKey), true)
            } else {
                viewPager.setCurrentItem(current + 1, true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    fun getPageIndex(key: String): Int = pageKeys.indexOf(key)

    fun navigateTo(pageKey: String) {
        val index = pageKeys.indexOf(pageKey)
        if (index >= 0) {
            viewPager.setCurrentItem(index, true)
        }
    }

    private fun setupDots() {
        dotContainer.removeAllViews()
        dots.clear()
        for (i in pageKeys.indices) {
            val dot = ImageView(this)
            val params = LinearLayout.LayoutParams(24, 24)
            params.marginStart = if (i == 0) 0 else 12
            dot.layoutParams = params
            dot.setImageResource(R.drawable.dot_indicator)
            dotContainer.addView(dot)
            dots.add(dot)
        }
    }

    private fun updateUI(position: Int) {
        for (i in dots.indices) {
            dots[i].setImageResource(
                if (i == position) R.drawable.dot_indicator_active
                else R.drawable.dot_indicator
            )
        }

        btnBack.visibility = if (position > 0) View.VISIBLE else View.GONE

        val currentKey = pageKeys[position]
        btnSkip.visibility = if (currentKey in skippablePages) View.VISIBLE else View.GONE

        btnNext.text = if (position == pageKeys.size - 1) "Get Started" else "Continue"
    }

    private fun finishSetup() {
        Settings.Global.putInt(contentResolver, Settings.Global.DEVICE_PROVISIONED, 1)
        Settings.Secure.putInt(contentResolver, Settings.Secure.USER_SETUP_COMPLETE, 1)

        packageManager.setComponentEnabledSetting(
            ComponentName(this, SetupWizardActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private inner class SetupPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = pageKeys.size
        override fun createFragment(position: Int): Fragment {
            return when (pageKeys[position]) {
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
        }
    }
}

interface SetupPage {
    fun onNext()
}
