package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class ModelDownloadPage : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_generic, container, false)
        view.findViewById<TextView>(R.id.title).text = "AI Models"
        view.findViewById<TextView>(R.id.description).text =
            "Speech and voice models will be\navailable in a future update."
        return view
    }
}
