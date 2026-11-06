# AXON — voice-controlled device agent

Local-first voice assistant: Kokoro TTS (free, offline) + a fast intent router + optional LLM brains.
Controls an Android phone over ADB: apps, media, alarms, DND, brightness, Wi-Fi, battery,
notifications, screenshots, calls, SMS, WhatsApp, YouTube search and more.

## 1. Setup
```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
sudo apt install portaudio19-dev adb      # Linux extras
```
Windows: install platform-tools (adb) and, if PyAudio fails, `pip install pipwin && pipwin install pyaudio`.

## 2. Phone bridge
1. Settings -> About phone -> tap *Build number* 7x -> Developer options.
2. Enable *USB debugging*, plug in, approve the prompt.
3. `adb devices` should list `device`.
4. Wireless: `adb tcpip 5555` then `adb connect <phone-ip>:5555`.

## 3. Run
```bash
python axon.py                     # voice mode
python axon.py --text              # typing mode
python axon.py --say "instagram kholo"   # one command
python axon.py --voice am_onyx --brain gemini
```

## 4. Wake words
`hey axon`, `hi axon`, `hello axon`, `morning axon`, `ok axon`, `axon`, `utho axon`,
`jago axon`, `axon suno`. Say `so jao` / `sleep` to send it back to sleep.

## 5. Brains (speed vs credits)

| Mode | What runs | Speed | Cost |
|---|---|---|---|
| `local` (default first pass) | regex intent router in `Router` | instant, ~0 ms | free, offline |
| `gemini` | Gemini 2.5 Flash with phone tools | 1-3 s | Gemini free tier |
| `agent` | this Base44 agent as the brain | 3-15 s | app credits |

`AXON_BRAIN=auto` (default) sends every device command to the router first — no LLM is
called at all — and only falls back to a brain for open-ended questions.

```bash
export AXON_BRAIN=auto
export GEMINI_API_KEY=AIza...          # for the gemini brain
export AGENT_API_KEY=...               # for the agent brain
export AGENT_ID=<superagent app id>
```
**Important:** the `agent` brain spends app credits on every message and needs the internet.
When credits run out the agent brain stops answering (device commands keep working through the
router). Web search, files and memory live on the agent side, so those features need the agent
brain plus available credits.

## 6. Kokoro voices (free, offline)
Default `af_heart`. Male: `am_michael`, `am_onyx`, `am_adam`, `bm_george` (British), `bm_fable`.
Hindi text is routed to `lang_code='h'` with `hf_alpha`.

Lightweight install without PyTorch:
```bash
pip install kokoro-onnx soundfile
# put in ~/.axon/kokoro/ : kokoro-v1.0.onnx (~325 MB), voices-v1.0.bin (~28 MB)
# https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0
```

## 7. Supported commands (examples)
"Instagram kholo" · "YouTube pe lofi search karo" · "next song" · "volume up" ·
"screenshot lo" · "alarm 6:30" · "5 minute ka timer" · "DND on" · "wifi off" ·
"brightness 80" · "battery batao" · "notifications padho" · "apps batao" ·
"whatsapp kal milte hain" · "google pe gold rate search karo"

## 8. Troubleshooting
- `ADB_ERROR: adb not found` — install platform-tools, add to PATH.
- `device timeout` — re-plug, `adb kill-server && adb start-server`.
- No sound — install `pygame`; the script falls back to `aplay`/`afplay`/`start`.
- Mic not found — `python -m speech_recognition` lists devices; PyAudio must match Python.
