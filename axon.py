#!/usr/bin/env python3
"""
AXON v2 — voice device agent with a fast local router + switchable brain.

Brains:
  AXON_BRAIN=auto     local router first (instant, offline, 0 credits) -> then Gemini, then agent API
  AXON_BRAIN=local    device commands only, never calls an LLM
  AXON_BRAIN=gemini   Gemini (fast, needs GEMINI_API_KEY)
  AXON_BRAIN=agent    this Base44 agent as the brain (needs AGENT_API_KEY + AGENT_ID; uses app credits)

Run:  python axon.py            voice mode
      python axon.py --text     typing mode
      python axon.py --say "instagram kholo"   single command (scripting)
"""
import os, sys, re, json, time, argparse, subprocess, tempfile
import device as D

# ----------------------------- TTS (Kokoro, free) -----------------------------
class Speaker:
    def __init__(self, voice="af_heart"):
        self.voice, self.pipes, self.mode = voice, {}, None
        try:
            import kokoro  # noqa
            self.mode = "kokoro"
        except Exception:
            try:
                from kokoro_onnx import Kokoro
                base = os.path.expanduser("~/.axon/kokoro")
                onnx, binp = os.path.join(base, "kokoro-v1.0.onnx"), os.path.join(base, "voices-v1.0.bin")
                if not (os.path.exists(onnx) and os.path.exists(binp)):
                    raise FileNotFoundError("model files missing in ~/.axon/kokoro")
                self._k = Kokoro(onnx, binp); self.mode = "onnx"
            except Exception as e:
                print("[tts] Kokoro unavailable:", e)

    def synth(self, text):
        import numpy as np, soundfile as sf
        path = os.path.join(tempfile.gettempdir(), "axon_speech.wav")
        if self.mode == "kokoro":
            from kokoro import KPipeline
            hindi = any('\u0900' <= c <= '\u097F' for c in text)
            lang, voice = ('h', 'hf_alpha') if hindi else ('a', self.voice)
            if lang not in self.pipes:
                self.pipes[lang] = KPipeline(lang_code=lang)
            parts = [a for _, _, a in self.pipes[lang](text, voice=voice)]
            audio = np.concatenate(parts) if parts else np.zeros(24000)
            sf.write(path, audio, 24000)
        elif self.mode == "onnx":
            s, sr = self._k.create(text, voice=self.voice if self.voice[:3] in ("am_", "af_", "bm_", "bf_") else "af_heart", speed=1.03, lang="en-us")
            sf.write(path, s, sr)
        else:
            return None
        return path

    def say(self, text):
        print("AXON:", text)
        path = self.synth(text)
        if not path:
            return
        try:
            import pygame
            if not pygame.mixer.get_init():
                pygame.mixer.init()
            pygame.mixer.music.load(path)
            pygame.mixer.music.play()
            while pygame.mixer.music.get_busy():
                time.sleep(0.05)
            pygame.mixer.music.unload()
        except Exception:
            if sys.platform == "darwin":
                subprocess.run(["afplay", path], check=False)
            elif sys.platform.startswith("linux"):
                subprocess.run(["aplay", "-q", path], check=False)
            else:
                os.startfile(path)  # type: ignore[attr-defined]

# ----------------------------- Brains -----------------------------
class GeminiBrain:
    def __init__(self, api_key, model="gemini-2.5-flash"):
        os.environ.pop("GOOGLE_API_KEY", None)
        from google import genai
        from google.genai import types
        self.types = types
        self.client = genai.Client(api_key=api_key)
        self.tools = [
            D.open_app, D.close_app, D.list_apps, D.media, D.key, D.type_text, D.brightness,
            D.dnd, D.wifi, D.data, D.bluetooth, D.battery, D.notification_read, D.alarm, D.timer,
            D.call, D.sms, D.whatsapp, D.open_url, D.search_web, D.youtube_play, D.tap, D.swipe,
            D.files_list, D.pull, D.search_apps,
        ]
        self.chat = self.client.chats.create(model=model, config=types.GenerateContentConfig(
            system_instruction=(
                "You are AXON, the user's phone agent. Reply in short natural Hinglish (Hindi in Roman script), "
                "like a sharp technical friend. 1-2 sentences max. Use a tool whenever an action is possible. "
                "Never claim an action succeeded unless the tool returned success."),
            tools=self.tools))

    def ask(self, text):
        resp = self.chat.send_message(text)
        for _ in range(5):
            calls = getattr(resp, "function_calls", None)
            if not calls:
                break
            results = []
            for fc in calls:
                fn = {f.__name__: f for f in self.tools}.get(fc.name)
                try:
                    out = fn(**fc.args) if fn else f"unknown tool {fc.name}"
                except Exception as e:
                    out = f"tool error: {e}"
                print(f"[tool] {fc.name} -> {out}")
                results.append(self.types.Part.from_function_response(name=fc.name, response={"result": out}))
            resp = self.chat.send_message(results)
        return (resp.text or "").strip()

class AgentBrain:
    """Uses this Base44 agent as the brain. Costs app credits; has web search + memory."""
    def __init__(self, api_key, agent_id, base="https://base44.app/api/agents"):
        self.key, self.agent_id, self.base, self.conv = api_key, agent_id, base, None
        self.conv = self._default_conversation()

    def _headers(self):
        return {"api_key": self.key, "Content-Type": "application/json"}

    def _default_conversation(self):
        import urllib.request
        url = f"{self.base}/{self.agent_id}/conversations"
        req = urllib.request.Request(url, data=json.dumps({}).encode(), headers=self._headers())
        try:
            data = json.load(urllib.request.urlopen(req, timeout=30))
            if isinstance(data, dict):
                return data.get("id") or data.get("conversation_id")
            if isinstance(data, list) and data:
                return data[0].get("id")
        except Exception as e:
            print("[agent] conversation error:", str(e)[:120])
        return None

    def ask(self, text):
        import urllib.request
        if not self.conv:
            return ""
        url = f"{self.base}/{self.agent_id}/conversations/{self.conv}/messages"
        body = json.dumps({"message": text, "content": text}).encode()
        try:
            data = json.load(urllib.request.urlopen(urllib.request.Request(url, data=body, headers=self._headers()), timeout=120))
            if isinstance(data, dict):
                return (data.get("message") or data.get("content") or data.get("text") or "").strip()
            return ""
        except Exception as e:
            print("[agent] error:", str(e)[:160])
            return ""

# ----------------------------- Fast local router -----------------------------
class Router:
    """Instant offline intent matching for device commands (no LLM, no credits)."""
    def __init__(self):
        self.rules = [
            (r"\b(khol|kholo|open|launch)\b.*", self._open),
            (r"\b(band|close|kill)\b.*", self._close),
            (r"\b(next|agla|agli)\b.*(song|gaana|track|video)?", lambda m, t: D.media("next")),
            (r"\b(prev|previous|pichla|pichhla)\b", lambda m, t: D.media("prev")),
            (r"\b(pause|roko|ruk)\b", lambda m, t: D.media("pause")),
            (r"\b(play|chala|chalao)\b", lambda m, t: D.media("play")),
            (r"\b(volume|awaz|awaaz)\b.*(up|badha|बढ़ा)", lambda m, t: D.media("volume_up")),
            (r"\b(volume|awaz|awaaz)\b.*(down|kam|ghata)", lambda m, t: D.media("volume_down")),
            (r"\bmute\b|\bsilent karo\b", lambda m, t: D.media("mute")),
            (r"\b(screenshot|screen shot|ss)\b", lambda m, t: D.media("screenshot")),
            (r"\bbattery\b|\bcharge\b", lambda m, t: D.battery()),
            (r"\bnotification", lambda m, t: D.notification_read()),
            (r"\b(dnd|do not disturb|disturb)\b", self._dnd),
            (r"\bwifi\b", self._wifi),
            (r"\b(bluetooth)\b", self._bt),
            (r"\b(mobile data|internet on|internet off)\b", self._data),
            (r"\bbrightness\b", self._brightness),
            (r"\balarm\b", self._alarm),
            (r"\btimer\b", self._timer),
            (r"\b(call|phone)\b.*(\d{6,})", self._call),
            (r"\bwhatsapp\b", self._whatsapp),
            (r"\b(battery|charge)\b", lambda m, t: D.battery()),
            (r"\byoutube\b", self._youtube),
            (r"\b(google|search|khoj)\b", self._search),
            (r"\b(home|ghar)\b", lambda m, t: D.key("home")),
            (r"\bback\b", lambda m, t: D.key("back")),
            (r"\block\b", lambda m, t: D.key("lock")),
            (r"\bapps?\b.*\b(batao|list|dikhao)", lambda m, t: D.list_apps()),
        ]

    def handle(self, text):
        t = text.lower().strip()
        for pat, fn in self.rules:
            m = re.search(pat, t)
            if m:
                try:
                    res = fn(m, t)
                    if res: return res
                except Exception as e:
                    return f"Action error: {e}"
        return None

    # handlers needing args
    def _open(self, m, t):
        name = re.sub(r"\b(khol|kholo|open|launch|do|de|kar)\b", "", t).strip()
        return D.open_app(name) if name else "Kaunsa app kholna hai?"

    def _close(self, m, t):
        name = re.sub(r"\b(band|close|kill|karo|kar|do)\b", "", t).strip()
        return D.close_app(name) if name else "Kaunsa app band karna hai?"

    def _dnd(self, m, t):
        return D.dnd("off" if re.search(r"\b(off|band|hata)\b", t) else "on")

    def _wifi(self, m, t):
        return D.wifi("off" if re.search(r"\b(off|band|hata)\b", t) else "on")

    def _bt(self, m, t):
        return D.bluetooth("off" if re.search(r"\b(off|band|hata)\b", t) else "on")

    def _data(self, m, t):
        return D.data("off" if re.search(r"\b(off|band|hata)\b", t) else "on")

    def _brightness(self, m, t):
        num = re.search(r"(\d{1,3})", t)
        if num: return D.brightness(int(num.group(1)))
        return D.brightness("up" if re.search(r"\b(up|badha)\b", t) else None)

    def _alarm(self, m, t):
        hm = re.search(r"(\d{1,2})[:.](\d{2})", t)
        if hm: return D.alarm(f"{hm.group(1)}:{hm.group(2)}")
        return "Time bolo, jaise 'alarm 6:30'."

    def _timer(self, m, t):
        mins = re.search(r"(\d+)", t)
        return D.timer(int(mins.group(1))) if mins else "Kitne minute ka timer?"

    def _call(self, m, t):
        num = re.search(r"(\+?\d{6,})", t)
        return D.call(num.group(1)) if num else "Number bolo."

    def _whatsapp(self, m, t):
        msg = re.sub(r".*whatsapp\b", "", t).strip(" :,-")
        return D.whatsapp(msg or "Hello")

    def _youtube(self, m, t):
        q = re.sub(r".*(youtube|pe|par)\b", "", t).strip(" :,-")
        return D.youtube_play(q) if q else D.open_app("youtube")

    def _search(self, m, t):
        q = re.sub(r".*(google|search|khoj|karo|kar)\b", "", t).strip(" :,-")
        return D.search_web(q) if q else "Kya search karna hai?"

# ----------------------------- Ears -----------------------------
def listen(timeout=6, phrase_limit=9):
    try:
        import speech_recognition as sr
    except Exception:
        return ""
    r = sr.Recognizer()
    r.dynamic_energy_threshold = True
    try:
        with sr.Microphone() as src:
            r.adjust_for_ambient_noise(src, duration=0.35)
            audio = r.listen(src, timeout=timeout, phrase_time_limit=phrase_limit)
    except Exception:
        return ""
    for lang in ("en-IN", "hi-IN"):
        try:
            return r.recognize_google(audio, language=lang)
        except Exception:
            continue
    return ""

# ----------------------------- Wake words -----------------------------
WAKE_WORDS = ("hey axon", "hi axon", "hello axon", "hallo axon", "helo axon",
              "morning axon", "good morning axon", "ok axon", "okay axon",
              "axon", "utho axon", "jago axon", "axon suno")
SLEEP_WORDS = ("so jao", "sojao", "sleep", "band karo axon", "chup ho jao", "stop listening")

def has_wake(text):
    t = text.lower()
    return next((w for w in sorted(WAKE_WORDS, key=len, reverse=True) if w in t), None)

def strip_wake(text):
    t = text.lower()
    for w in sorted(WAKE_WORDS, key=len, reverse=True):
        t = t.replace(w, " ")
    return t.strip(" ,.!")

# ----------------------------- Main -----------------------------
def build_brain(mode):
    gem_key = os.getenv("GEMINI_API_KEY", "")
    agent_key = os.getenv("AGENT_API_KEY", "")
    agent_id = os.getenv("AGENT_ID", "")
    order = {"auto": ["gemini", "agent"], "gemini": ["gemini"], "agent": ["agent"], "local": []}[mode]
    for kind in order:
        if kind == "gemini" and gem_key.startswith("AIza"):
            try:
                return "gemini", GeminiBrain(gem_key)
            except Exception as e:
                print("[brain] gemini failed:", str(e)[:120])
        if kind == "agent" and agent_key and agent_id:
            try:
                return "agent", AgentBrain(agent_key, agent_id)
            except Exception as e:
                print("[brain] agent failed:", str(e)[:120])
    return "local", None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", action="store_true")
    ap.add_argument("--say", help="run one command and exit")
    ap.add_argument("--voice", default=os.getenv("AXON_VOICE", "af_heart"))
    ap.add_argument("--brain", default=os.getenv("AXON_BRAIN", "auto"))
    args = ap.parse_args()

    speaker = Speaker(args.voice)
    router = Router()
    mode, brain = build_brain(args.brain)
    print(f"[axon] brain={mode} tts={speaker.mode} device={'connected' if D.connected() else 'NOT connected'}")

    def handle(text):
        if any(s in text.lower() for s in SLEEP_WORDS):
            return "__SLEEP__"
        res = router.handle(text)
        if res:
            return res
        if brain:
            return brain.ask(text) or "Samajh nahi aaya, phir bolo."
        return "Yeh mere local brain se nahi ho sakta. Gemini ya agent brain on karo."

    if args.say:
        print(handle(args.say)); return

    speaker.say("Axon online. Bolo.")
    awake = bool(args.text)
    while True:
        try:
            if args.text:
                user = input("YOU: ").strip()
            else:
                print("... " + ("listening" if awake else "waiting for 'Axon'"))
                raw = listen(timeout=6 if awake else 5)
                if not raw:
                    continue
                print("HEARD:", raw)
                if not awake:
                    if not has_wake(raw):
                        continue
                    awake = True
                    user = strip_wake(raw) or ""
                    if not user:
                        speaker.say("Haan bhai, bolo.")
                        continue
                else:
                    user = strip_wake(raw) if has_wake(raw) else raw
        except (KeyboardInterrupt, EOFError):
            speaker.say("Axon band. Bye.")
            break

        if not user:
            continue
        reply = handle(user)
        if reply == "__SLEEP__":
            awake = (not args.text)
            speaker.say("Theek hai, 'Axon' bolne par jag jaunga.")
            continue
        if user.lower() in ("exit", "quit", "bye"):
            speaker.say("Bye bhai.")
            break
        speaker.say(reply)

if __name__ == "__main__":
    main()
