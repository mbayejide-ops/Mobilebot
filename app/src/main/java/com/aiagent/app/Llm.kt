package com.aiagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Llm {

    fun chat(baseUrl: String, apiKey: String, model: String, system: String, user: String): String {
        val url = URL(baseUrl.trim().trimEnd('/') + "/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim())

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", system))
            messages.put(JSONObject().put("role", "user").put("content", user))
            val body = JSONObject()
            body.put("model", model.trim())
            body.put("temperature", 0)
            body.put("messages", messages)

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw RuntimeException("API error " + code + ": " + text.take(200))
            }
            return JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }

    fun extractJson(s: String): JSONObject {
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw RuntimeException("AI thik JSON dey nai: " + s.take(100))
        return JSONObject(s.substring(a, b + 1))
    }
}
