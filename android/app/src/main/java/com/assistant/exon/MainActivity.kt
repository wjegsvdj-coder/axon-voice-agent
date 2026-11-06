package com.assistant.exon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var speaker: Speaker
    private lateinit var memory: Memory
    private lateinit var router: CommandRouter
    private lateinit var status: TextView
    private lateinit var diag: TextView
    private lateinit var heard: TextView
    private lateinit var replyView: TextView
    private lateinit var latencyView: TextView
    private lateinit var input: EditText
    private lateinit var micBtn: Button

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var t0 = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speaker = Speaker(this)
        memory = Memory(this)
        router = CommandRouter(this)

        status = findViewById(R.id.status)
        diag = findViewById(R.id.diag)
        heard = findViewById(R.id.heard)
        replyView = findViewById(R.id.reply)
        latencyView = findViewById(R.id.latency)
        input = findViewById(R.id.input)
        micBtn = findViewById(R.id.btnMic)

        findViewById<TextView>(R.id.title).setOnLongClickListener {
            val adv = findViewById<LinearLayout>(R.id.advanced)
            adv.visibility = if (adv.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }

        micBtn.setOnClickListener { if (listening) stopListening() else startListening() }
        findViewById<Button>(R.id.btnService).setOnClickListener { toggleService() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { speaker.stop(); say("Ruk gaya.") }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Exon dhoondo aur on karo", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnWriteSettings).setOnClickListener { askWriteSettings() }
        findViewById<Button>(R.id.btnDnd).setOnClickListener { askDnd() }
        findViewById<Button>(R.id.btnMemory).setOnClickListener { showMemory() }
        findViewById<Button>(R.id.btnVoice).setOnClickListener { showVoiceDialog() }
        findViewById<Button>(R.id.btnBrain).setOnClickListener { showBrainDialog() }
        findViewById<Button>(R.id.btnRun).setOnClickListener { runCommand(input.text.toString()) }

        askPermissions()
        setupRecognizer()
    }

    // ---------- permissions ----------

    private fun askPermissions() {
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 11)
    }

    private fun askWriteSettings() {
        if (!Settings.System.canWrite(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")))
            say("System settings ki permission do, phir brightness aur timeout badal paunga.")
        } else say("System write permission already hai.")
    }

    private fun askDnd() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            say("DND access do, phir quiet mode chala paunga.")
        } else say("DND access hai.")
    }

    // ---------- voice in ----------

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            diag.text = "mic: speech service missing"
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { status.text = "sun raha hoon..." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(r: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "samajh raha hoon..." }
                override fun onError(e: Int) {
                    status.text = "mic error $e"
                    if (listening) micBtn.postDelayed({ if (listening) startListening() }, 700)
                }
                override fun onResults(r: Bundle?) {
                    val said = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (said.isNotBlank()) runCommand(said)
                    if (listening) micBtn.postDelayed({ if (listening) startListening() }, 300)
                }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); say("Pehle microphone ki permission do."); return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        listening = true
        micBtn.text = "Listening... (tap to stop)"
        status.text = "mic chalu"
        try { recognizer?.startListening(i) } catch (e: Exception) { status.text = "mic start fail: ${e.message}" }
    }

    private fun stopListening() {
        listening = false
        micBtn.text = "Talk"
        status.text = "mic band"
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    // ---------- commands ----------

    private fun runCommand(text: String) {
        val cmd = text.trim()
        if (cmd.isEmpty()) return
        t0 = System.currentTimeMillis()
        heard.text = "You: $cmd"
        input.setText("")

        when {
            cmd.startsWith("yaad rakho", true) || cmd.startsWith("remember", true) -> {
                val fact = cmd.removePrefix("yaad rakho").removePrefix("remember").trim(' ', ':', '-')
                if (fact.isBlank()) return say("Kya yaad rakhna hai?")
                memory.remember(fact); return say("Yaad rakh liya: $fact")
            }
            cmd.startsWith("bhool jao", true) -> {
                val n = memory.forget(cmd.removePrefix("bhool jao").trim())
                return say(if (n > 0) "$n baat bhool gaya." else "Aisa kuch yaad nahi tha.")
            }
            cmd.contains("kya yaad", true) -> {
                val f = memory.facts().takeLast(5).joinToString("; ")
                return say(if (f.isBlank()) "Abhi kuch yaad nahi hai." else "Mujhe yaad hai: $f")
            }
        }

        val contentKeys = listOf("script", "hook", "title", "caption", "content pack", "shot list")
        if (contentKeys.any { cmd.lowercase().contains(it) }) {
            val topic = cmd.replace(Regex("(?i)\\b(script|hooks?|titles?|caption|content|pack|shot list|banao|bana do|likho)\\b"), " ").trim()
            return say(ContentKit.pack(topic.ifBlank { "personal finance" }))
        }

        val direct = router.handle(cmd)
        if (direct != null) return say(direct)

        val brainReply = Brain.reply(this, cmd, memory)
        say(brainReply ?: "Ye mujhe samajh nahi aaya. Brain settings me endpoint daalo, phir ye sawaal bhi handle ho jayega.")
    }

    private fun say(text: String) {
        replyView.text = text
        speaker.say(text)
        if (t0 > 0L) {
            val ms = System.currentTimeMillis() - t0
            latencyView.text = if (ms < 400) "local · $ms ms" else "brain · $ms ms"
            t0 = 0L
        }
        refreshDiag()
    }

    // ---------- service + status ----------

    private fun toggleService() {
        val intent = Intent(this, ExonService::class.java)
        if (isServiceRunning()) {
            getSharedPreferences("exon_prefs", MODE_PRIVATE).edit().putBoolean("always_listen", false).apply()
            stopService(intent)
            Toast.makeText(this, "Listener band", Toast.LENGTH_SHORT).show()
        } else {
            getSharedPreferences("exon_prefs", MODE_PRIVATE).edit().putBoolean("always_listen", true).apply()
            askBatteryExemption()
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Always listening ON - 'Exon' bolo", Toast.LENGTH_SHORT).show()
        }
        status.postDelayed({ refreshDiag() }, 500)
    }

    /** Android kills background mic services unless the app is exempt from battery optimisation. */
    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(80).any { it.service.className == ExonService::class.java.name }
    }

    private fun refreshDiag() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val acc = isAccessibilityOn()
        val rec = SpeechRecognizer.isRecognitionAvailable(this)
        diag.text = "mic ${if (mic) "ok" else "NO"}   ·   accessibility ${if (acc) "ok" else "NO"}   ·   speech ${if (rec) "ok" else "NO"}\n${speaker.status}"
    }

    private fun isAccessibilityOn(): Boolean {
        val want = "$packageName/${ExonAccessibility::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(want, true) }
    }

    private fun showVoiceDialog() {
        val voices = speaker.availableVoices()
        if (voices.isEmpty()) {
            say("Is phone par koi natural voice nahi mili. Google TTS install karo.")
            speaker.openTtsSettings()
            return
        }
        val labels = voices.map { v ->
            val q = when (v.quality) {
                android.speech.tts.Voice.QUALITY_VERY_HIGH -> "natural"
                android.speech.tts.Voice.QUALITY_HIGH -> "high"
                android.speech.tts.Voice.QUALITY_NORMAL -> "normal"
                else -> "low"
            }
            v.locale.toLanguageTag() + "  ·  " + q + (if (v.isNetworkConnectionRequired) "  ·  net" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Voice chuno (" + voices.size + ")")
            .setItems(labels) { _, which ->
                speaker.setVoice(voices[which].name)
                speaker.say("Yeh meri nayi awaaz hai. Kaisi lagi?")
                refreshDiag()
            }
            .setPositiveButton("TTS settings") { _, _ -> speaker.openTtsSettings() }
            .setNegativeButton("Band") { _, _ -> }
            .show()
    }

    private fun showMemory() {
        val f = memory.facts()
        replyView.text = if (f.isEmpty()) "Memory khali hai." else f.joinToString("\n") { "• $it" }
        speaker.say(if (f.isEmpty()) "Memory khali hai." else "Mere paas ${f.size} baatein yaad hain.")
    }

    private fun showBrainDialog() {
        val prefs = getSharedPreferences("exon_brain", MODE_PRIVATE)
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val url = EditText(this).apply { hint = "endpoint URL"; setText(prefs.getString("url", "")) }
        val key = EditText(this).apply { hint = "key (optional)"; setText(prefs.getString("key", "")) }
        box.addView(url); box.addView(key)
        builder.setTitle("Brain (advanced)").setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("url", url.text.toString().trim()).putString("key", key.text.toString().trim()).apply()
                say("Brain settings save ho gayi.")
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onResume() { super.onResume(); refreshDiag() }

    override fun onDestroy() {
        super.onDestroy()
        listening = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        speaker.shutdown()
    }
}
