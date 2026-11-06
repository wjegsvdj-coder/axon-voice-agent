package com.assistant.exon

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var router: CommandRouter
    private lateinit var memory: Memory
    private lateinit var speaker: Speaker
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var replyView: TextView
    private lateinit var latencyView: TextView
    private var t0 = 0L
    private lateinit var input: EditText

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text") ?: return
            replyView.text = text
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        router = CommandRouter(this)
        memory = Memory(this)
        speaker = Speaker(this)

        status = findViewById(R.id.status)
        heard = findViewById(R.id.heard)
        replyView = findViewById(R.id.reply)
        latencyView = findViewById(R.id.latency)
        findViewById<TextView>(R.id.title).setOnLongClickListener {
            val adv = findViewById<LinearLayout>(R.id.advanced)
            adv.visibility = if (adv.visibility == LinearLayout.VISIBLE) LinearLayout.GONE else LinearLayout.VISIBLE
            Toast.makeText(this, if (adv.visibility == LinearLayout.VISIBLE) "Advanced" else "Hidden", Toast.LENGTH_SHORT).show()
            true
        }
        input = findViewById(R.id.input)

        findViewById<Button>(R.id.btnMic).setOnClickListener { runCommand(input.text.toString()) }
        findViewById<Button>(R.id.btnService).setOnClickListener { toggleService() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnWriteSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnBrain).setOnClickListener { showBrainDialog() }
        findViewById<Button>(R.id.btnMemory).setOnClickListener { showMemory() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 11)
        }
        ContextCompat.registerReceiver(this, uiReceiver,
            IntentFilter("com.assistant.exon.UI_UPDATE"), ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val acc = if (ExonAccessibility.isRunning()) "accessibility ON" else "accessibility OFF"
        val writes = try { if (Settings.System.canWrite(this)) "system writes ON" else "system writes OFF" } catch (e: Exception) { "system writes ?" }
        val svc = if (ExonService.running) "listener ON" else "listener OFF"
        val brain = if (Brain.isConfigured(this)) "brain ON" else "brain OFF"
        val mem = "memory ${memory.count()}"
        status.text = "$acc | $writes | $svc | $brain | $mem | mic ${if (mic) "ON" else "OFF"}"
        findViewById<Button>(R.id.btnService).text =
            if (ExonService.running) "Stop always-on listening" else "Start always-on listening"
    }

    private fun toggleService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 11)
            Toast.makeText(this, "Pehle mic permission do", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, ExonService::class.java)
        if (ExonService.running) {
            stopService(intent); Toast.makeText(this, "Listener band", Toast.LENGTH_SHORT).show()
        } else {
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Listener chalu - 'Exon' bolo", Toast.LENGTH_SHORT).show()
        }
        status.postDelayed({ refreshStatus() }, 600)
    }

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
                return say("$n baat bhool gaya.")
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
        return say(brainReply ?: "Iske liye brain on karo (Brain settings), ya simple device command bolo.")
    }

    private fun say(text: String) {
        replyView.text = text
        if (t0 > 0L) {
            val ms = System.currentTimeMillis() - t0
            val mode = if (ms < 400) "local" else "brain"
            latencyView.text = "\u23F1 ${ms} ms \u00B7 $mode"
            t0 = 0L
        }
        speaker.say(text.take(300))
        refreshStatus()
    }

    private fun showBrainDialog() {
        val prefs = getSharedPreferences("exon_brain", MODE_PRIVATE)
        val endpointInput = EditText(this).apply {
            hint = "https://your-endpoint/v1/chat"
            setText(prefs.getString("endpoint", ""))
        }
        val keyInput = EditText(this).apply {
            hint = "API key (optional)"
            setText(prefs.getString("key", ""))
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(endpointInput); addView(keyInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Brain (optional)")
            .setMessage("Device commands aur memory bilkul local chalte hain. Conversation, research aur content polish ke liye apna endpoint do. Khali chhodne par brain off rahega.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Brain.save(this, endpointInput.text.toString(), keyInput.text.toString())
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMemory() {
        val facts = memory.facts()
        val text = if (facts.isEmpty()) "Abhi kuch yaad nahi hai." else facts.joinToString("\n") { "- $it" }
        AlertDialog.Builder(this)
            .setTitle("Memory (${facts.size})")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear all") { _, _ -> memory.clear(); refreshStatus() }
            .show()
    }

    override fun onDestroy() {
        try { unregisterReceiver(uiReceiver) } catch (_: Exception) {}
        speaker.shutdown()
        super.onDestroy()
    }
}
