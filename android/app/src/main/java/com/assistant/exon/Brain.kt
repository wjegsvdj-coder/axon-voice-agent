package com.assistant.exon

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional remote brain. No key is hardcoded: the user pastes their own endpoint + key in Settings.
 * Expects JSON in: {"message": "...", "context": "..."} and JSON out: {"reply": "..."} (or plain text).
 */
object Brain {

    fun endpoint(ctx: Context): String =
        ctx.getSharedPreferences("exon_brain", Context.MODE_PRIVATE).getString("endpoint", "").orEmpty()

    fun apiKey(ctx: Context): String =
        ctx.getSharedPreferences("exon_brain", Context.MODE_PRIVATE).getString("key", "").orEmpty()

    fun isConfigured(ctx: Context) = endpoint(ctx).isNotBlank()

    fun save(ctx: Context, endpoint: String, key: String) {
        ctx.getSharedPreferences("exon_brain", Context.MODE_PRIVATE).edit()
            .putString("endpoint", endpoint.trim())
            .putString("key", key.trim())
            .apply()
    }

    fun reply(ctx: Context, message: String, memory: Memory): String? {
        val url = endpoint(ctx)
        if (url.isBlank()) return null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val key = apiKey(ctx)
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            }
            val body = JSONObject().apply {
                put("message", message)
                put("context", memory.asContext())
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream)).readText()
            if (code !in 200..299) return "Brain error $code: " + text.take(120)
            val parsed = try { JSONObject(text).optString("reply") } catch (e: Exception) { "" }
            parsed.ifBlank { text.take(600) }
        } catch (e: Exception) {
            "Brain connect nahi hua: ${e.message}"
        }
    }
}
