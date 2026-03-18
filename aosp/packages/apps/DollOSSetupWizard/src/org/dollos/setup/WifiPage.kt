package org.dollos.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class WifiPage : Fragment() {

    private lateinit var wifiStatus: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_wifi, container, false)
        view.findViewById<TextView>(R.id.title).text = "Connect to Wi-Fi"
        view.findViewById<TextView>(R.id.description).text =
            "An internet connection is needed to\ndownload models and verify your API key."

        wifiStatus = view.findViewById(R.id.wifi_status)

        view.findViewById<TextView>(R.id.btn_open_wifi).setOnClickListener {
            val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            startActivity(intent)
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        updateWifiStatus()
    }

    private fun updateWifiStatus() {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        wifiStatus.text = if (connected) "Connected" else "Not connected"
    }
}
