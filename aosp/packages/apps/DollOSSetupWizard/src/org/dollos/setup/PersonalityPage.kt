package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment

class PersonalityPage : Fragment(), SetupPage {

    private lateinit var nameInput: EditText
    private lateinit var descriptionInput: EditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_personality, container, false)
        view.findViewById<TextView>(R.id.title).text = "Meet Your AI"
        view.findViewById<TextView>(R.id.description).text =
            "Give your AI companion a name\nand describe its personality."

        nameInput = view.findViewById(R.id.input_name)
        descriptionInput = view.findViewById(R.id.input_description)

        return view
    }

    override fun onNext() {
        val name = nameInput.text?.toString() ?: ""
        val description = descriptionInput.text?.toString() ?: ""
        if (name.isNotBlank()) {
            (activity as? SetupWizardActivity)?.dollOSService?.setPersonality(name, description)
        }
    }
}
