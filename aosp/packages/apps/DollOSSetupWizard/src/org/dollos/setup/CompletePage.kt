package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class CompletePage : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_generic, container, false)
        view.findViewById<TextView>(R.id.title).text = "Setup Complete"
        view.findViewById<TextView>(R.id.description).text =
            "DollOS is ready. Your AI companion awaits."
        view.findViewById<Button>(R.id.btn_next).apply {
            text = "Get Started"
            setOnClickListener {
                (activity as SetupWizardActivity).nextPage() // triggers finishSetup()
            }
        }
        view.findViewById<Button>(R.id.btn_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { (activity as SetupWizardActivity).previousPage() }
        }
        return view
    }
}
