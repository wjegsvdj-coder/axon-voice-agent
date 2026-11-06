package com.assistant.exon

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent

/**
 * Deep system actions that need accessibility + system write access.
 * No API keys, no network: everything runs on the device.
 */
class ExonAccessibility : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ExonAccessibility? = null
        fun isRunning() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // reserved for window state tracking
    }

    override fun onInterrupt() {}

    // ---------------- command entry ----------------
    fun executeDeepSystemCommand(processedCommand: String): String {
        val command = processedCommand.lowercase().trim()
        return when {
            command.contains("screen timeout") || (command.contains("timeout") && command.any { it.isDigit() }) -> {
                val ms = extractTimeoutDuration(command)
                setDeepScreenTimeout(ms)
            }
            command.contains("refresh rate") || command.contains("motion smoothness") -> {
                val high = command.contains("high") || command.contains("adaptive") ||
                        command.contains("90") || command.contains("120")
                setRefreshRate(if (high) 2 else 1)
            }
            command.contains("go home") || command == "home" -> {
                performGlobalAction(GLOBAL_ACTION_HOME); "Home par aa gaya."
            }
            command.contains("go back") || command == "back" -> {
                performGlobalAction(GLOBAL_ACTION_BACK); "Back ho gaya."
            }
            command.contains("recent") -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS); "Recent apps khul gaye."
            }
            command.contains("screenshot") -> takeScreenshot()
            command.contains("scroll down") -> simulateVerticalSwipe(true)
            command.contains("scroll up") -> simulateVerticalSwipe(false)
            command.contains("notification panel") || command.contains("quick settings") -> {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); "Notification panel khol diya."
            }
            else -> ""
        }
    }

    private fun takeScreenshot(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            if (ok) "Screenshot le liya (gallery mein hai)." else "Screenshot fail hua."
        } else "Screenshot ke liye Android 11+ chahiye."
    } catch (e: Exception) {
        "Screenshot error: ${e.message}"
    }

    private fun setDeepScreenTimeout(ms: Long): String = try {
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms.toInt())
        "Screen timeout ${ms / 60000} minute set."
    } catch (e: SecurityException) {
        launchSettingsPage(Settings.ACTION_DISPLAY_SETTINGS)
        "Write permission nahi hai. Settings khol di, ya adb se WRITE_SECURE_SETTINGS do."
    }

    private fun setRefreshRate(mode: Int): String {
        val keys = listOf("refresh_rate_mode", "peak_refresh_rate", "min_refresh_rate")
        for (k in keys) {
            try {
                Settings.Secure.putInt(contentResolver, k, mode)
                return "Refresh rate mode $mode set ($k)."
            } catch (_: SecurityException) {
                // try next key
            } catch (_: Exception) {
            }
        }
        launchSettingsPage(Settings.ACTION_DISPLAY_SETTINGS)
        return "Refresh rate write block hai. Settings khol di; adb se WRITE_SECURE_SETTINGS do."
    }

    private fun launchSettingsPage(actionString: String) {
        try {
            val intent = Intent(actionString).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun simulateVerticalSwipe(scrollDown: Boolean): String {
        val dm: DisplayMetrics = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val top = dm.heightPixels * 0.25f
        val bottom = dm.heightPixels * 0.75f
        val path = Path().apply {
            if (scrollDown) { moveTo(cx, bottom); lineTo(cx, top) }
            else { moveTo(cx, top); lineTo(cx, bottom) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        return if (ok) (if (scrollDown) "Scroll down kiya." else "Scroll up kiya.") else "Gesture fail hua."
    }

    fun extractTimeoutDuration(input: String): Long {
        val numeric = input.replace("[^0-9]".toRegex(), "").toLongOrNull() ?: 30L
        return when {
            input.contains("hour") || input.contains("ghanta") -> numeric * 60 * 60 * 1000
            input.contains("minute") || input.contains("min") || input.contains("mint") -> numeric * 60 * 1000
            else -> numeric * 1000
        }
    }
}
