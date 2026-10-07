package com.aiagent.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        status = TextView(this).apply { textSize = 18f }

        val btnAccessibility = Button(this).apply {
            text = "Accessibility settings kholo"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val input = EditText(this).apply { hint = "Song er nam likho" }

        val btnPlay = Button(this).apply {
            text = "YouTube e play koro"
            setOnClickListener {
                val q = input.text.toString().trim()
                val svc = AgentService.instance
                when {
                    q.isEmpty() -> Toast.makeText(context, "Song er nam likho", Toast.LENGTH_SHORT).show()
                    svc == null -> Toast.makeText(context, "Age Accessibility service on koro", Toast.LENGTH_LONG).show()
                    else -> svc.playOnYoutube(q)
                }
            }
        }

        root.addView(status)
        root.addView(btnAccessibility)
        root.addView(input)
        root.addView(btnPlay)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (AgentService.instance != null) "Service: ON" else "Service: OFF"
    }
}
