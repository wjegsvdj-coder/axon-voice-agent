package com.assistant.exon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Always-on listening service: 5 wake words, offline command routing, memory, optional brain.
 * No API keys required for device commands or memory.
 */
class ExonService : Service() {

    companion object {
        @Volatile var running = false
        const val CHANNEL_ID = "Exon_Core"
        const val NOTIF_ID = 303
        val WAKE_WORDS = listOf(
            "hey exon", "hi exon", "hello exon", "morning exon", "ok exon",
            "hey axon", "hi axon", "hello axon", "morning axon", "axon", "exon"
        )
    }

    private var recognizer: SpeechRecognizer? = null
    private var intentProto: Intent? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStart = 0L
    private var backoffMs = 400L
    private var awaitingCommand = false

    private lateinit var router: CommandRouter
    private lateinit var memory: Memory
    private lateinit var speaker: Speaker

    override fun onCreate() {
        super.onCreate()
        running = true
        router = CommandRouter(this)
        memory = Memory(this)
        speaker = Speaker(this)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Exon::listening")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(60 * 60 * 1000L)   // refreshed on every restart

        createChannel()
        startForeground(NOTIF_ID, notification("Listening for 'Exon'"))
        initRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_STICKY
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            notify("Speech recognition missing on this device")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        intentProto = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        }
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                backoffMs = 400L
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (said.isNotBlank()) handle(said)
                restartSoon(250)
            }

            override fun onError(error: Int) {
                // ERROR_NO_MATCH / SPEECH_TIMEOUT are normal in a continuous loop
                backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                restartSoon(backoffMs)
            }
        })
    }

    private fun restartSoon(delayMs: Long) {
        android.os.Handler(mainLooper).postDelayed({ startListening() }, delayMs)
    }

    private fun startListening() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStart < 300) return
        lastStart = now
        try {
            recognizer?.cancel()
            recognizer?.startListening(intentProto)
        } catch (_: Exception) {
        }
    }

    // ---------------- command handling ----------------
    private fun handle(raw: String) {
        val text = raw.lowercase(Locale.ROOT).trim()
        val wake = WAKE_WORDS.firstOrNull { text == it || text.startsWith("$it ") }

        if (wake == null && !awaitingCommand) return
        var command = if (wake != null) text.substring(wake.length).trim() else text
        awaitingCommand = false

        if (command.isEmpty()) {
            awaitingCommand = true
            speak("Haan bhai, bolo.")
            notify("Listening for your command")
            return
        }

        // ---- self-updating memory ----
        if (command.startsWith("yaad rakho") || command.startsWith("remember")) {
            val fact = command.removePrefix("yaad rakho").removePrefix("remember").trim(' ', ':', '-')
            if (fact.isNotBlank()) {
                memory.remember(fact)
                speak("Yaad rakh liya: $fact")
            } else speak("Kya yaad rakhna hai?")
            return
        }
        if (command.startsWith("bhool jao") || command.startsWith("forget")) {
            val needle = command.removePrefix("bhool jao").removePrefix("forget").trim(' ', ':', '-')
            val n = memory.forget(needle)
            speak(if (n > 0) "$n baat bhool gaya." else "Aisa kuch yaad nahi tha.")
            return
        }
        if (command.contains("kya yaad hai") || command.contains("memory")) {
            val f = memory.facts()
            speak(if (f.isEmpty()) "Abhi kuch yaad nahi hai." else "Mujhe yaad hai: " + f.takeLast(5).joinToString("; "))
            return
        }

        // ---- offline content creation ----
        val contentKey = listOf("script", "hook", "title", "caption", "content", "video idea", "shot list")
        if (contentKey.any { command.contains(it) }) {
            val topic = command
                .replace(Regex("\\b(script|hook|hooks|title|titles|caption|content|video idea|shot list|banao|bana do|likho|do|ka|ke liye)\\b"), " ")
                .trim()
            val pack = if (topic.isBlank()) ContentKit.pack("personal finance") else ContentKit.pack(topic)
            speak("Content pack ready hai, screen pe dekh lo.")
            notify("Content pack: " + (topic.ifBlank { "personal finance" }))
            sendToUi(pack)
            return
        }

        // ---- device actions through the local router ----
        val direct = router.handle(command)
        if (direct != null) {
            speak(direct)
            return
        }

        // ---- optional brain ----
        val brainReply = Brain.reply(this, command, memory)
        val answer = brainReply ?: "Yeh command abhi set nahi hai. Settings mein brain endpoint daal do, ya simple commands bolo."
        speak(answer)
        sendToUi(answer)
    }

    private fun speak(text: String) {
        speaker.say(text)
        notify(text.take(60))
    }

    private fun sendToUi(text: String) {
        val i = Intent("com.assistant.exon.UI_UPDATE").setPackage(packageName)
        i.putExtra("text", text)
        sendBroadcast(i)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "Exon Core", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Exon voice assistant"
            nm.createNotificationChannel(ch)
        }
    }

    private fun notification(text: String): Notification {
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exon active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification(text))
    }

    override fun onDestroy() {
        running = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        speaker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
