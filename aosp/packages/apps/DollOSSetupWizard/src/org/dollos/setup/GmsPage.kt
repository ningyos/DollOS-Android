package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial

class GmsPage : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_gms, container, false)
        view.findViewById<TextView>(R.id.title).text = "Google Play Services"
        view.findViewById<TextView>(R.id.description).text =
            "Install sandboxed Google Play? Google will have no special system privileges."

        val gmsSwitch = view.findViewById<SwitchMaterial>(R.id.switch_gms)

        view.findViewById<Button>(R.id.btn_next).setOnClickListener {
            val wizard = activity as SetupWizardActivity
            wizard.dollOSService?.setGmsOptIn(gmsSwitch.isChecked)
            wizard.nextPage()
        }
        view.findViewById<Button>(R.id.btn_back).setOnClickListener {
            (activity as SetupWizardActivity).previousPage()
        }
        return view
    }
}
