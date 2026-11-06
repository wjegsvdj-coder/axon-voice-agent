package com.assistant.exon

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Natural voice engine: picks the best installed voice (Google TTS neural where available),
 * speaks sentence by sentence for human rhythm, and reports why sound is missing.
 */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {

    private val app = ctx.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private val prefs = app.getSharedPreferences("exon_prefs", Context.MODE_PRIVATE)

    var status: String = "voice: starting"
        private set

    init { tts = TextToSpeech(app, this) }

    override fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) { status = "voice: engine failed"; return }
        val engine = tts ?: return
        val best = pickBest(engine, prefs.getString("voice_name", null))
        if (best != null) {
            engine.voice = best
            status = "voice: " + shortName(best)
        } else {
            val hi = Locale("hi", "IN")
            val r = engine.setLanguage(if (engine.isLanguageAvailable(hi) >= 0) hi else Locale.US)
            status = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) "voice: data missing" else "voice: default"
        }
        engine.setSpeechRate(prefs.getFloat("voice_rate", 1.0f))
        engine.setPitch(prefs.getFloat("voice_pitch", 1.0f))
        ready = true
    }

    fun availableVoices(): List<Voice> {
        val engine = tts ?: return emptyList()
        return try {
            engine.voices.filter { it.locale.language == "en" || it.locale.language == "hi" }
                .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired })
        } catch (e: Exception) { emptyList() }
    }

    private fun pickBest(engine: TextToSpeech, wanted: String?): Voice? {
        val all = availableVoices()
        if (all.isEmpty()) return null
        if (wanted != null) all.firstOrNull { it.name == wanted }?.let { return it }
        val indian = all.filter { it.locale.country == "IN" && !it.isNetworkConnectionRequired }
        val local = all.filter { !it.isNetworkConnectionRequired }
        val pool = if (indian.isNotEmpty()) indian else local.ifEmpty { all }
        return pool.maxByOrNull { it.quality }
    }

    private fun shortName(v: Voice): String {
        val q = when (v.quality) {
            Voice.QUALITY_VERY_HIGH -> "natural"
            Voice.QUALITY_HIGH -> "high"
            Voice.QUALITY_NORMAL -> "normal"
            else -> "low"
        }
        return v.locale.toLanguageTag() + " " + q
    }

    /** Sentence-sized chunks give a human rhythm instead of one flat utterance. */
    fun say(text: String) {
        val engine = tts
        if (!ready || engine == null) return
        val parts = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        engine.stop()
        if (parts.isEmpty()) return
        parts.forEachIndexed { i, part ->
            engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "exon_" + i)
        }
    }

    fun setVoice(name: String) {
        prefs.edit().putString("voice_name", name).apply()
        onInit(TextToSpeech.SUCCESS)
    }

    fun setRate(v: Float) { prefs.edit().putFloat("voice_rate", v).apply(); tts?.setSpeechRate(v) }
    fun setPitch(v: Float) { prefs.edit().putFloat("voice_pitch", v).apply(); tts?.setPitch(v) }
    fun currentRate(): Float = prefs.getFloat("voice_rate", 1.0f)
    fun currentPitch(): Float = prefs.getFloat("voice_pitch", 1.0f)
    fun stop() { tts?.stop() }
    fun shutdown() { tts?.shutdown() }

    fun openTtsSettings() {
        app.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
