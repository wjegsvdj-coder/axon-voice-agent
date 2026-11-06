package com.assistant.exon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the always-listening service after reboot / app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("exon_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("always_listen", false)) return
        val i = Intent(context, ExonService::class.java)
        ContextCompat.startForegroundService(context, i)
    }
}
