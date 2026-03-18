package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial

class GmsPage : Fragment(), SetupPage {

    private lateinit var gmsSwitch: SwitchMaterial

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_gms, container, false)
        view.findViewById<TextView>(R.id.title).text = "Google Play"
        view.findViewById<TextView>(R.id.description).text =
            "Google Play runs in a sandbox with\nno special system privileges."
        gmsSwitch = view.findViewById(R.id.switch_gms)
        return view
    }

    override fun onNext() {
        (activity as? SetupWizardActivity)?.dollOSService?.setGmsOptIn(gmsSwitch.isChecked)
    }
}
