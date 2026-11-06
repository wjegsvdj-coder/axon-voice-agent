package com.assistant.exon

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Device TTS: instant, offline, no API key. Hinglish text is spoken with the Hindi/Indian voice. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val hindi = Locale("hi", "IN")
            val res = tts.setLanguage(if (tts.isLanguageAvailable(hindi) >= 0) hindi else Locale.US)
            ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            tts.setSpeechRate(1.02f)
        }
    }

    fun say(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "exon")
    }

    fun shutdown() { tts.shutdown() }
}
