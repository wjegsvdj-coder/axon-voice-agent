package com.assistant.exon

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Device TTS: instant, offline, no API key.
 * Status is exposed so the UI can show WHY sound is silent instead of failing quietly.
 */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val app = ctx.applicationContext
    private val tts = TextToSpeech(app, this)
    private var initDone = false
    @Volatile private var ready = false
    private val pending = ArrayDeque<String>()
    @Volatile var status: String = "voice: initialising"
        private set

    override fun onInit(code: Int) {
        initDone = true
        if (code != TextToSpeech.SUCCESS) { status = "voice: engine failed ($code)"; return }
        val hindi = Locale("hi", "IN")
        val pick = if (tts.isLanguageAvailable(hindi) >= 0) hindi else Locale.US
        val r = tts.setLanguage(pick)
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setSpeechRate(1.03f)
        status = when {
            !ready && r == TextToSpeech.LANG_MISSING_DATA -> "voice: data missing (install TTS data)"
            !ready -> "voice: ${pick.language} not supported"
            else -> "voice: ${pick.language}-${pick.country} ready"
        }
        flushPending()
    }

    private fun flushPending() {
        while (ready && pending.isNotEmpty()) {
            tts.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, "exon")
        }
    }

    fun say(text: String) {
        val clean = text.replace(Regex("[*_#`]"), "").take(600)
        if (ready) { tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "exon"); return }
        if (pending.size < 3) pending.addLast(clean)
        if (!initDone) status = "voice: starting"
    }

    fun stop() { tts.stop() }

    fun installDataIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)

    fun shutdown() { try { tts.shutdown() } catch (_: Exception) {} }
}
