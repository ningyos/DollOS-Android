package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class WifiPage : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_generic, container, false)
        view.findViewById<TextView>(R.id.title).text = "Connect to Wi-Fi"
        view.findViewById<TextView>(R.id.description).text =
            "Wi-Fi is required to download AI models and verify your API key."
        // TODO: integrate system Wi-Fi picker
        view.findViewById<Button>(R.id.btn_next).setOnClickListener {
            (activity as SetupWizardActivity).nextPage()
        }
        view.findViewById<Button>(R.id.btn_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { (activity as SetupWizardActivity).previousPage() }
        }
        return view
    }
}
