package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class VoicePage : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_generic, container, false)
        view.findViewById<TextView>(R.id.title).text = "Voice Settings"
        view.findViewById<TextView>(R.id.description).text =
            "Choose a voice for your AI companion and test your microphone."
        // TODO: TTS voice selection and mic test
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
