package com.assistant.exon

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast

/** On-device actions that need no keys and no ADB. */
class SystemActions(private val ctx: Context) {

    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var torchOn = false

    fun volumeUp(): String { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return "Volume up." }
    fun volumeDown(): String { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return "Volume down." }
    fun mute(): String { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); return "Mute kar diya." }
    fun unmute(): String { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI); return "Unmute." }

    fun dnd(on: Boolean): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            openIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "DND permission chahiye - settings khol di."
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (on) "DND on." else "DND off."
    }

    fun brightness(level: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            openIntent(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return "Brightness permission chahiye - settings khol di."
        }
        val pct = level.coerceIn(1, 100)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct * 255) / 100)
        return "Brightness $pct%."
    }

    fun battery(): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "Battery $pct%" + if (charging) ", charging." else "."
    }

    fun flashlight(on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cam ->
                cm.getCameraCharacteristics(cam)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) {
                "Is phone mein flashlight nahi mila."
            } else {
                cm.setTorchMode(id, on)
                torchOn = on
                if (on) "Flashlight on." else "Flashlight off."
            }
        } catch (e: CameraAccessException) {
            "Flashlight error: ${e.message}"
        }
    }

    fun openApp(name: String): String {
        val query = name.lowercase().trim()
        val pm = ctx.packageManager
        val known = mapOf(
            "instagram" to "com.instagram.android", "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp", "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome", "camera" to "com.android.camera",
            "settings" to "com.android.settings", "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps", "spotify" to "com.spotify.music",
            "telegram" to "org.telegram.messenger", "snapchat" to "com.snapchat.android",
            "netflix" to "com.netflix.mediaclient", "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock", "phone" to "com.google.android.dialer",
            "messages" to "com.google.android.apps.messaging", "play store" to "com.android.vending"
        )
        var pkg = known[query]
        if (pkg == null) {
            pkg = pm.getInstalledApplications(0)
                .firstOrNull { pm.getApplicationLabel(it).toString().lowercase().contains(query) }
                ?.packageName
        }
        if (pkg == null) return "'$name' nahi mila."
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "'$name' launch nahi ho paya."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "$name khol diya."
    }

    fun call(number: String): String {
        openIntent(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        return "Calling $number."
    }

    fun whatsapp(message: String): String {
        openIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=${Uri.encode(message)}")))
        return "WhatsApp khul gaya, message ready."
    }

    fun openUrl(url: String): String {
        openIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return "Khol diya."
    }

    fun search(query: String): String = openUrl("https://www.google.com/search?q=${Uri.encode(query)}").let { "Google pe search kiya." }
    fun youtube(query: String): String = openUrl("https://www.youtube.com/results?search_query=${Uri.encode(query)}").let { "YouTube pe $query search kiya." }

    fun alarm(hhmm: String): String {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 6
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 30
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Exon")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Alarm %02d:%02d set.".format(h, m)
    }

    fun timer(minutes: Int): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Exon timer")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "$minutes minute ka timer."
    }

    fun wifiPanel(): String {
        openIntent(Intent(Settings.ACTION_WIFI_SETTINGS))
        return "Wi-Fi settings khol di."
    }

    fun bluetoothPanel(): String {
        openIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        return "Bluetooth settings khol di."
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun openIntent(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
