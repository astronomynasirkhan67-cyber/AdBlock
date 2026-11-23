# ULTRON AI — voice-only assistant (Android APK + Windows EXE)

Talk to a glowing virtual core, like J.A.R.V.I.S. / Ultron in the movies. No chat screen — voice only.

- The core is dark and calm when idle, reacts to your voice while **listening**, spins while **thinking**,
  and pulses while **speaking**.
- **Brain:** Qwen3.5-0.8B (GGUF, Q8_0, about 0.8 GB) running locally through llama.cpp. On first launch the app
  asks you to install the brain (one-time download). After that, thinking is fully offline.
- **Ears / voice:** Android uses the phone's own speech recogniser and text-to-speech. Windows uses the built-in
  offline Windows speech engine.

## Build (GitHub Actions)
1. Upload the contents of this folder to your repo root (keep the hidden `.github` folder).
2. Open **Actions → ULTRON AI — Build APK and Windows EXE → Run workflow**.
3. Download `ULTRON-AI.apk` and `ULTRON-AI.exe` from the **Releases** page (tag `latest`).

The workflow compiles llama.cpp's `llama-server` for each platform (about 10 minutes the first time).
You can pin a llama.cpp version with the optional `llama_tag` input when you run the workflow by hand.

## Use
- **Android:** open ULTRON → tap *INSTALL BRAIN* → wait for the download → allow the microphone → talk.
  Tap the core to interrupt Ultron or to pause/resume listening. The `EN/HI` button switches the voice language.
- **Windows 10/11 (64-bit, AVX2 CPU):** open ULTRON-AI.exe → *INSTALL BRAIN* → talk. Click the core or press Space
  to interrupt / pause. Brain is stored in `%LOCALAPPDATA%\UltronAI\brain`.

## Notes
- `pc-engine/qwen3_5-assembly` is the Linux x86-64 assembly engine you supplied. It cannot run on Android (ARM) or
  natively on Windows, so the app uses the same Qwen3.5-0.8B model through llama.cpp instead. The folder is kept
  for reference only and is not used by the build.
- Android build targets arm64 phones (almost every phone since 2017).

## Voice plugins (Android)
Say: time / date / battery, flashlight on/off, volume up/down, pause / next / previous music, set a timer or alarm,
send a WhatsApp or SMS to a contact, call a contact or number, remember a note / read my notes, calculate 12 times 5,
navigate to a place, take a photo, Wi-Fi / Bluetooth settings, weather, play X on YouTube, search for X, open any app.
**Documents:** "make a document about black holes in MS Word" - Ultron writes it with the brain, saves a real .docx in
Downloads/Ultron and opens it in Word. **Floating core:** allow "display over other apps" once; when you switch to another app
a small core stays on top (drag it, tap it to talk or pause).

## Feature parity: Android APK vs Windows EXE

| Feature | Android APK | Windows EXE |
|---|---|---|
| Voice-only glowing core, same logo | yes | yes |
| Offline brain (Qwen3.5-0.8B via llama.cpp) | yes | yes |
| Offline speech recognition | yes (Vosk model, one-time download; falls back to the phone's recogniser) | yes (Windows offline speech engine) |
| Offline spoken voice, male | yes (male voice if installed, otherwise deep-voice mode) | yes (Windows male voice) |
| Floating mini core over other apps | yes (needs "display over other apps" or the Accessibility service) | yes (small always-on-top core) |
| Plugins (time, battery, timer, alarm, notes, calculator, search, YouTube, maps, open apps...) | yes | yes |
| Control other apps by voice (back, home, scroll, click a button, type text) | yes, via the Accessibility service (turn it on once in Settings) | yes (keyboard/mouse automation) |
| "Write a document about X in Word" | writes the document, saves .docx in Downloads/Ultron, opens it in Word | opens Word and types it live, also saves a full copy |
