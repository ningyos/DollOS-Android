package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment

class ApiKeyPage : Fragment(), SetupPage {

    private lateinit var providerSpinner: Spinner
    private lateinit var apiKeyInput: EditText

    private val providers = listOf("Claude", "Grok", "OpenAI", "Custom")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_api_key, container, false)
        view.findViewById<TextView>(R.id.title).text = "AI Configuration"
        view.findViewById<TextView>(R.id.description).text =
            "Connect your AI provider.\nYou can configure this later in Settings."

        providerSpinner = view.findViewById(R.id.spinner_provider)
        apiKeyInput = view.findViewById(R.id.input_api_key)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, providers)
        providerSpinner.adapter = adapter

        return view
    }

    override fun onNext() {
        val provider = providerSpinner.selectedItem?.toString() ?: ""
        val apiKey = apiKeyInput.text?.toString() ?: ""
        if (provider.isNotBlank() && apiKey.isNotBlank()) {
            (activity as? SetupWizardActivity)?.dollOSService?.setApiKey(provider, apiKey)
        }
    }
}
