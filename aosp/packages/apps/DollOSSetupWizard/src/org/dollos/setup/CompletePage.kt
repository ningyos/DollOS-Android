package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class CompletePage : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_generic, container, false)
        view.findViewById<TextView>(R.id.title).text = "You're All Set"
        view.findViewById<TextView>(R.id.description).text =
            "DollOS is ready.\nYour AI companion awaits."
        return view
    }
}
