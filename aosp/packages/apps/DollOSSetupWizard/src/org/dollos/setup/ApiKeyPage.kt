package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText

class ApiKeyPage : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_api_key, container, false)
        view.findViewById<TextView>(R.id.title).text = "AI Configuration"
        view.findViewById<TextView>(R.id.description).text =
            "Enter your LLM API key. You can skip this and configure later in Settings."

        val providerInput = view.findViewById<TextInputEditText>(R.id.input_provider)
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.input_api_key)
        val wizard = activity as SetupWizardActivity

        view.findViewById<Button>(R.id.btn_next).setOnClickListener {
            val provider = providerInput.text?.toString() ?: ""
            val apiKey = apiKeyInput.text?.toString() ?: ""
            if (provider.isNotBlank() && apiKey.isNotBlank()) {
                wizard.dollOSService?.setApiKey(provider, apiKey)
            }
            wizard.nextPage()
        }
        view.findViewById<Button>(R.id.btn_skip).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                // Skip personality page too (depends on AI being configured)
                wizard.skipToPage(wizard.getPageIndex("voice"))
            }
        }
        view.findViewById<Button>(R.id.btn_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { wizard.previousPage() }
        }
        return view
    }
}
