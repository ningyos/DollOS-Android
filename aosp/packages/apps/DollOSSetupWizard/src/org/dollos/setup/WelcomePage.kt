package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class WelcomePage : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_generic, container, false)
        view.findViewById<TextView>(R.id.title).text = "Welcome to DollOS"
        view.findViewById<TextView>(R.id.description).text =
            "Your AI companion lives here. Let's get started."
        view.findViewById<Button>(R.id.btn_next).setOnClickListener {
            (activity as SetupWizardActivity).nextPage()
        }
        return view
    }
}
