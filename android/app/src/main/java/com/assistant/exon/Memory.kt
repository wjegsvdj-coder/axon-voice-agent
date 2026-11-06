package com.assistant.exon

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local self-updating memory. Everything stays on the device.
 * The agent can add facts while you talk ("yaad rakho ...") and uses them later.
 */
class Memory(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("exon_memory", Context.MODE_PRIVATE)

    private fun load(): JSONArray =
        try { JSONArray(prefs.getString("facts", "[]")) } catch (e: Exception) { JSONArray() }

    private fun save(arr: JSONArray) = prefs.edit().putString("facts", arr.toString()).apply()

    fun remember(fact: String) {
        val clean = fact.trim()
        if (clean.length < 3) return
        val arr = load()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("fact").equals(clean, ignoreCase = true)) return
        }
        val obj = JSONObject().apply {
            put("fact", clean)
            put("ts", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        }
        arr.put(obj)
        // keep the newest 200 facts
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - 200)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        save(trimmed)
    }

    fun facts(): List<String> {
        val arr = load()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("fact") }
    }

    fun forget(needle: String): Int {
        val arr = load()
        val keep = JSONArray()
        var removed = 0
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i).optString("fact")
            if (f.contains(needle, ignoreCase = true)) removed++ else keep.put(arr.get(i))
        }
        save(keep)
        return removed
    }

    fun clear() = prefs.edit().putString("facts", "[]").apply()

    /** Compact context block for the brain prompt. */
    fun asContext(): String {
        val f = facts()
        return if (f.isEmpty()) "" else "About the user:\n" + f.joinToString("\n") { "- $it" }
    }

    fun count(): Int = load().length()
}
