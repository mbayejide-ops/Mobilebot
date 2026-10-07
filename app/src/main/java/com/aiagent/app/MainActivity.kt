package com.aiagent.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_VOICE = 101

    private lateinit var status: TextView
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etCmd: EditText
    private lateinit var btnLang: Button
    private var lang = "bn-BD"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        status = TextView(this).apply { textSize = 18f }

        val btnAccessibility = Button(this).apply {
            text = "Accessibility settings kholo"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        etUrl = EditText(this).apply {
            hint = "API base URL"
            setText(prefs.getString("url", "https://openrouter.ai/api/v1"))
        }
        etKey = EditText(this).apply {
            hint = "API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        etModel = EditText(this).apply {
            hint = "Model nam"
            setText(prefs.getString("model", "openai/gpt-4o-mini"))
        }
        etCmd = EditText(this).apply { hint = "Command likho ba mic chapo" }

        val btnVoice = Button(this).apply {
            text = "Mic: bolo"
            setOnClickListener { startVoice() }
        }
        btnLang = Button(this).apply {
            text = "Bhasha: Bangla"
            setOnClickListener {
                if (lang == "bn-BD") {
                    lang = "en-US"
                    text = "Bhasha: English"
                } else {
                    lang = "bn-BD"
                    text = "Bhasha: Bangla"
                }
            }
        }
        val btnRun = Button(this).apply {
            text = "Command chalao"
            setOnClickListener { runCommand() }
        }

        root.addView(status)
        root.addView(btnAccessibility)
        root.addView(etUrl)
        root.addView(etKey)
        root.addView(etModel)
        root.addView(etCmd)
        root.addView(btnVoice)
        root.addView(btnLang)
        root.addView(btnRun)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (AgentService.instance != null) "Service: ON" else "Service: OFF"
    }

    override fun onPause() {
        super.onPause()
        saveConfig()
    }

    private fun saveConfig() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("url", etUrl.text.toString().trim())
            .putString("key", etKey.text.toString().trim())
            .putString("model", etModel.text.toString().trim())
            .apply()
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Command bolo")
        try {
            startActivityForResult(i, REQ_VOICE)
        } catch (e: Exception) {
            toast("Voice dialog pawa jay nai. Keyboard er mic use koro")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                etCmd.setText(text)
                runCommand()
            }
        }
    }

    private fun runCommand() {
        saveConfig()
        val cmd = etCmd.text.toString().trim()
        val key = etKey.text.toString().trim()
        val svc = AgentService.instance
        when {
            cmd.isEmpty() -> toast("Command likho")
            key.isEmpty() -> toast("API key dao")
            svc == null -> toast("Age Accessibility service on koro")
            else -> {
                svc.runAgent(cmd, etUrl.text.toString(), key, etModel.text.toString())
                moveTaskToBack(true)
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
