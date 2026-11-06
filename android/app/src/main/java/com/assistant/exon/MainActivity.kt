package com.assistant.exon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var router: CommandRouter
    private lateinit var speaker: Speaker
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var reply: TextView

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "Exon"

        router = CommandRouter(this)
        speaker = Speaker(this)
        status = findViewById(R.id.status)
        heard = findViewById(R.id.heard)
        reply = findViewById(R.id.reply)

        findViewById<Button>(R.id.btnMic).setOnClickListener { if (!listening) startListening() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnWriteSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 11)
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val acc = if (ExonAccessibility.isRunning()) "accessibility ON" else "accessibility OFF"
        val sec = try {
            if (Settings.System.canWrite(this)) "system writes ON" else "system writes OFF"
        } catch (_: Exception) { "system writes ?" }
        status.text = "$acc | $sec | mic ${if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "ON" else "OFF"}"
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition available nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; heard.text = "Sun raha hoon..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                listening = false
                heard.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Kuch samajh nahi aaya."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch suna nahi."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission do."
                    else -> "Mic error: $error"
                }
                recognizer?.destroy()
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                val said = texts.firstOrNull() ?: ""
                heard.text = "Suna: $said"
                if (said.isBlank()) return
                run(said)
                recognizer?.destroy()
            }
        })
        recognizer?.startListening(intent)
    }

    private fun run(text: String) {
        val cleaned = stripWake(text)
        val answer = router.handle(cleaned)
        if (answer != null) {
            reply.text = answer
            speaker.say(answer)
        } else {
            val msg = "Yeh command mere on-device router mein nahi hai. Settings in Exon: 'whatsapp kal milte hain', 'alarm 6:30', 'screenshot', 'battery'."
            reply.text = msg
            speaker.say("Yeh wala command abhi set nahi hai.")
        }
        refreshStatus()
    }

    private fun stripWake(text: String): String {
        var t = text.lowercase()
        val wakes = listOf("hey exon", "hi exon", "hello exon", "morning exon", "ok exon",
            "hey axon", "hi axon", "hello axon", "morning axon", "ok axon", "axon", "exon")
        for (w in wakes.sortedByDescending { it.length }) t = t.replace(w, " ")
        return t.trim()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        speaker.shutdown()
        super.onDestroy()
    }
}
