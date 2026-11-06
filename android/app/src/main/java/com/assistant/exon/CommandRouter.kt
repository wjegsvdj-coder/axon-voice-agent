package com.assistant.exon

import android.content.Context

/**
 * Local intent router. Understands commands instantly, offline, no API key, no credits.
 * Returns null when the sentence is not a device command (caller can then fall back to another brain).
 */
class CommandRouter(private val ctx: Context) {

    private val sys = SystemActions(ctx)

    fun handle(raw: String): String? {
        val t = raw.lowercase().trim().trim('।', '.', ',', '!', '?')
        if (t.isEmpty()) return null

        // ---------- screen timeout ----------
        if (t.contains("screen timeout") || t.contains("screen off timeout")) {
            ExonAccessibility.instance?.let { return it.executeDeepSystemCommand(t) }
            return "Accessibility service off hai - Settings > Accessibility mein Exon on karo."
        }

        // ---------- refresh rate ----------
        if (t.contains("refresh rate") || t.contains("motion smoothness")) {
            ExonAccessibility.instance?.let { return it.executeDeepSystemCommand(t) }
            return "Accessibility service off hai."
        }

        // ---------- navigation / gestures ----------
        if (Regex("\\b(go home|home jao|home pe|ghar jao)\\b").containsMatchIn(t)) {
            return ExonAccessibility.instance?.executeDeepSystemCommand("go home") ?: "Accessibility off hai."
        }
        if (Regex("\\b(go back|back jao|wapas)\\b").containsMatchIn(t)) {
            return ExonAccessibility.instance?.executeDeepSystemCommand("go back") ?: "Accessibility off hai."
        }
        if (t.contains("recent")) return ExonAccessibility.instance?.executeDeepSystemCommand("recent apps") ?: "Accessibility off hai."
        if (t.contains("screenshot")) return ExonAccessibility.instance?.executeDeepSystemCommand("screenshot") ?: "Accessibility off hai."
        if (t.contains("scroll down")) return ExonAccessibility.instance?.executeDeepSystemCommand("scroll down") ?: "Accessibility off hai."
        if (t.contains("scroll up")) return ExonAccessibility.instance?.executeDeepSystemCommand("scroll up") ?: "Accessibility off hai."
        if (t.contains("notification panel") || t.contains("quick settings")) {
            return ExonAccessibility.instance?.executeDeepSystemCommand("notification panel") ?: "Accessibility off hai."
        }

        // ---------- volume ----------
        if (t.contains("volume")) {
            return when {
                t.contains("up") || t.contains("badha") || t.contains("bada") -> sys.volumeUp()
                t.contains("down") || t.contains("kam") || t.contains("ghata") -> sys.volumeDown()
                t.contains("mute") || t.contains("silent") -> sys.mute()
                t.contains("unmute") -> sys.unmute()
                else -> "Volume up ya down bolo."
            }
        }
        if (t == "mute" || t.contains("mute karo")) return sys.mute()

        // ---------- dnd ----------
        if (t.contains("dnd") || t.contains("do not disturb") || t.contains("disturb")) {
            val off = t.contains("off") || t.contains("band") || t.contains("hata")
            return sys.dnd(!off)
        }

        // ---------- brightness ----------
        if (t.contains("brightness")) {
            val num = Regex("(\\d{1,3})").find(t)?.groupValues?.get(1)?.toIntOrNull()
            val level = num ?: when {
                t.contains("full") || t.contains("max") -> 100
                t.contains("half") -> 50
                t.contains("low") || t.contains("kam") -> 20
                else -> 60
            }
            return sys.brightness(level)
        }

        // ---------- battery ----------
        if (t.contains("battery") || t.contains("charge")) return sys.battery()

        // ---------- flashlight ----------
        if (t.contains("flashlight") || t.contains("torch") || t.contains("light on")) {
            val off = t.contains("off") || t.contains("band")
            return sys.flashlight(!off)
        }

        // ---------- alarm / timer ----------
        if (t.contains("alarm")) {
            val time = Regex("(\\d{1,2})[:.]?(\\d{2})?").find(t)
            val h = time?.groupValues?.getOrNull(1)
            val m = time?.groupValues?.getOrNull(2)
            return sys.alarm("${h ?: 6}:${m?.takeIf { it.isNotBlank() } ?: "30"}")
        }
        if (t.contains("timer")) {
            val n = Regex("(\\d{1,3})").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            return sys.timer(n)
        }

        // ---------- calls ----------
        val phone = Regex("(\\+?\\d{7,15})").find(t)
        if (phone != null && (t.contains("call") || t.contains("phone"))) return sys.call(phone.groupValues[1])

        // ---------- messaging / web ----------
        if (t.contains("whatsapp")) {
            val msg = t.substringAfter("whatsapp").trim(' ', ':', '-')
            return sys.whatsapp(msg.ifBlank { "Hello" })
        }
        if (t.contains("youtube")) {
            val q = t.substringAfter("youtube").replace("pe", "").replace("par", "").trim(' ', ':', '-')
            return if (q.isBlank()) sys.openApp("youtube") else sys.youtube(q)
        }
        if (t.contains("google") && (t.contains("search") || t.contains("khoj"))) {
            val q = t.substringAfter("search").ifBlank { t.substringAfter("khoj") }.trim(' ', ':', '-')
            return if (q.isBlank()) "Kya search karna hai?" else sys.search(q)
        }
        if (t.startsWith("open http") || t.contains("khol website")) {
            val url = Regex("https?://\\S+").find(t)?.value ?: return "URL bolo."
            return sys.openUrl(url)
        }

        // ---------- panels ----------
        if (t.contains("wifi") || t.contains("wi-fi")) return sys.wifiPanel()
        if (t.contains("bluetooth")) return sys.bluetoothPanel()

        // ---------- apps ----------
        val openMatch = Regex("\\b(open|khol do|kholo|khol|launch|chala do)\\b").containsMatchIn(t)
        val closeMatch = Regex("\\b(band karo|band kar|close|kill)\\b").containsMatchIn(t)
        if (openMatch || closeMatch) {
            val name = t
                .replace(Regex("\\b(open|khol do|kholo|khol|launch|chala do|band karo|band kar|close|kill|kar|do|de)\\b"), "")
                .trim()
            if (name.isBlank()) return "Kaunsa app?"
            if (closeMatch) {
                return try {
                    val pkg = ctx.packageManager.getInstalledApplications(0)
                        .firstOrNull { ctx.packageManager.getApplicationLabel(it).toString().lowercase().contains(name) }
                        ?.packageName
                    if (pkg == null) "'$name' nahi mila." else {
                        ctx.packageManager.getLaunchIntentForPackage(pkg)
                        "Android apps ko user ki tarah band nahi kar sakta. Home se bahar kar doon?"
                    }
                } catch (e: Exception) { "Close error: ${e.message}" }
            }
            return sys.openApp(name)
        }

        return null
    }
}
