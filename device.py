"""ADB device control layer for AXON. Every action returns a short human-readable result."""
import os, re, subprocess, time, json

def adb(*args, timeout=25, binary=False):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, text=not binary, timeout=timeout)
        out = r.stdout if binary else ((r.stdout or "") + (r.stderr or ""))
        return out
    except FileNotFoundError:
        return "ADB_ERROR: adb not found. Install platform-tools and add it to PATH."
    except subprocess.TimeoutExpired:
        return "ADB_ERROR: device timeout. Reconnect USB or run adb connect <ip>:5555."

def connected():
    return "device" in adb("get-state").lower()

def sh(cmd, **kw):
    return adb("shell", *cmd.split(), **kw)

# ---------- apps ----------
PACKAGES = {
    "instagram": "com.instagram.android", "youtube": "com.google.android.youtube",
    "facebook": "com.facebook.katana", "whatsapp": "com.whatsapp",
    "chrome": "com.android.chrome", "camera": "com.android.camera",
    "settings": "com.android.settings", "gmail": "com.google.android.gm",
    "maps": "com.google.android.apps.maps", "spotify": "com.spotify.music",
    "telegram": "org.telegram.messenger", "snapchat": "com.snapchat.android",
    "playstore": "com.android.vending", "calculator": "com.google.android.calculator",
    "clock": "com.google.android.deskclock", "phone": "com.google.android.dialer",
    "messages": "com.google.android.apps.messaging", "netflix": "com.netflix.mediaclient",
}

def open_app(name):
    pkg = PACKAGES.get(name.lower().strip())
    if not pkg:
        hits = search_apps(name)
        if not hits:
            return f"'{name}' nahi mila. Installed apps dekhne ke liye 'apps batao' bolo."
        pkg = hits[0][1]
    out = adb("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
    return f"{name} khol diya." if "No activities" not in out and "ADB_ERROR" not in out else out.strip()[:160]

def close_app(name):
    pkg = PACKAGES.get(name.lower().strip()) or (search_apps(name) or [(None, None)])[0][1]
    if not pkg:
        return f"'{name}' nahi mila."
    adb("shell", "am", "force-stop", pkg)
    return f"{name} band kar diya."

def search_apps(query):
    out = sh("pm list packages -3")
    found = []
    for line in out.splitlines():
        m = re.match(r"package:(.+)", line.strip())
        if not m: continue
        pkg = m.group(1)
        if query.lower().replace(" ", "") in pkg.lower():
            found.append((pkg.split(".")[-1].capitalize(), pkg))
    return found[:8]

def list_apps(only_third_party=True):
    out = sh("pm list packages" + (" -3" if only_third_party else ""))
    names = sorted({p.split(".")[-1].capitalize() for p in re.findall(r"package:(\S+)", out)})
    return ", ".join(names[:40]) if names else out[:200]

# ---------- media / keys ----------
KEYS = {"play": "85", "pause": "85", "toggle": "85", "next": "87", "prev": "88", "previous": "88",
        "volume_up": "24", "volume_down": "25", "mute": "164", "home": "3", "back": "4",
        "recent": "187", "wake": "224", "sleep": "223", "enter": "66", "delete": "67",
        "lock": "26", "power": "26", "camera": "27", "search": "84", "menu": "82"}

def key(name):
    code = KEYS.get(name.lower())
    if not code: return f"'{name}' key nahi hai."
    out = sh(f"input keyevent {code}")
    return f"{name} done." if "ADB_ERROR" not in out else out

def media(command):
    c = command.lower().strip()
    if c in ("screenshot", "screen shot", "ss"):
        return screenshot()
    return key(c)

def screenshot():
    stamp = time.strftime("%Y%m%d_%H%M%S")
    remote = f"/sdcard/axon_{stamp}.png"
    local = os.path.expanduser(f"~/axon_shot_{stamp}.png")
    out = sh(f"screencap -p {remote}")
    if "ADB_ERROR" in out or "No such file" in out:
        return out.strip()[:160]
    pulled = adb("pull", remote, local)
    adb("shell", "rm", remote)
    if "ADB_ERROR" in pulled or "error" in pulled.lower():
        return pulled.strip()[:160]
    return f"Screenshot saved: {local}"

def type_text(text):
    safe = text.replace(" ", "%s").replace("'", "")
    out = sh(f'input text "{safe}"')
    return "Type kar diya." if "ADB_ERROR" not in out else out

def tap(x, y):
    sh(f"input tap {int(x)} {int(y)}"); return f"Tap {x},{y}"

def swipe(x1, y1, x2, y2, ms=300):
    sh(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms)}")
    return "Swipe done."

# ---------- system ----------
def brightness(level=None):
    if level is None:
        cur = sh("settings get system screen_brightness").strip()
        if "ADB_ERROR" in cur: return cur
        return f"Brightness {cur}."
    sh(f"settings put system screen_brightness {max(0,min(255,int(level)))}")
    return f"Brightness {int(level)} set."

def dnd(state):
    val = "1" if str(state).lower() in ("on", "1", "true", "yes", "chalu") else "0"
    sh(f"settings put global zen_mode {val}")
    return f"DND {'on' if val=='1' else 'off'}."

def wifi(state):
    res = adb("shell", "svc", "wifi", "enable" if str(state).lower() in ("on","1","true") else "disable")
    return "Wi-Fi " + ("on" if "on" in str(state).lower() or str(state)=="1" else "off") + "." if "ADB_ERROR" not in res else res

def data(state):
    on = str(state).lower() in ("on","1","true")
    res = adb("shell", "svc", "data", "enable" if on else "disable")
    return f"Mobile data {'on' if on else 'off'}." if "ADB_ERROR" not in res else res

def bluetooth(state):
    on = str(state).lower() in ("on","1","true")
    res = adb("shell", "cmd", "bluetooth_manager", "enable" if on else "disable")
    return f"Bluetooth {'on' if on else 'off'}." if "ADB_ERROR" not in res else res

def flashlight(state):
    return key("camera") if str(state).lower() in ("on","1","true") else key("camera")

def battery():
    out = sh("dumpsys battery")
    if "ADB_ERROR" in out:
        return out
    lvl = re.search(r"level: (\d+)", out); st = re.search(r"status: (\d+)", out)
    charging = {2: "charging", 5: "full", 3: "discharging"}.get(int(st.group(1)) if st else 3, "unknown")
    return f"Battery {lvl.group(1) if lvl else '?'}%, {charging}."

def notification_read():
    out = sh("dumpsys notification --noredact")
    titles = re.findall(r'android\.title=(.+)', out)
    clean = [t.strip() for t in titles if t.strip()][:6]
    return " | ".join(clean) if clean else "Koi nayi notification nahi."

def alarm(hhmm, label="Axon"):
    h, m = hhmm.split(":")[:2]
    adb("shell", "am", "start", "-a", "android.intent.action.SET_ALARM",
        "-e", "android.intent.extra.alarm.HOUR", h,
        "-e", "android.intent.extra.alarm.MINUTES", m,
        "-e", "android.intent.extra.alarm.MESSAGE", label)
    return f"Alarm {hhmm} par set."

def timer(minutes):
    adb("shell", "am", "start", "-a", "android.intent.action.SET_TIMER",
        "-e", "android.intent.extra.alarm.LENGTH", str(int(minutes)),
        "-e", "android.intent.extra.alarm.MESSAGE", "Axon timer")
    return f"{minutes} minute ka timer."

def call(number):
    adb("shell", "am", "start", "-a", "android.intent.action.CALL", "-d", f"tel:{number}")
    return f"Calling {number}."

def sms(number, message):
    adb("shell", "am", "start", "-a", "android.intent.action.SENDTO", "-d", f"smsto:{number}",
        "--es", "sms_body", message)
    return f"SMS ready for {number}."

def whatsapp(message):
    from urllib.parse import quote
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"https://wa.me/?text={quote(message)}")
    return "WhatsApp khul gaya, message ready hai."

def open_url(url):
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url)
    return f"Khol diya: {url}"

def search_web(query):
    from urllib.parse import quote_plus
    return open_url(f"https://www.google.com/search?q={quote_plus(query)}")

def youtube_play(query):
    from urllib.parse import quote_plus
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"https://www.youtube.com/results?search_query={quote_plus(query)}")
    return f"YouTube pe {query} search kiya."

def speech_to_phone(text):
    safe = text.replace("'", "\u2019")
    adb("shell", "am", "start", "-a", "android.intent.action.SEND",
        "--es", "android.intent.extra.TEXT", f"'{safe}'", "-t", "text/plain")
    return "Phone pe text share sheet khula."

def screenshot_pull(local=None):
    return media("screenshot")

def files_list(path="/sdcard/Download"):
    return sh(f"ls -1 {path}").strip()[:600] or "Khaali folder."

def pull(remote, local=None):
    local = local or os.path.expanduser("~/axon_" + os.path.basename(remote))
    adb("pull", remote, local)
    return f"File: {local}"

DANGEROUS = {"uninstall", "reboot", "factory_reset"}

def uninstall(package):
    out = adb("shell", "pm", "uninstall", package)
    return out.strip()[:160]
