"""ULTRON AI - Windows desktop (voice only).

A glowing virtual core you talk to. Speech in/out uses the Windows built-in offline
speech engine (System.Speech); thinking is done by the Qwen3.5-0.8B brain running
locally through llama.cpp's llama-server.exe (bundled inside this EXE).
"""
import base64
import json
import math
import os
import queue
import random
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.error
import urllib.request

import win_skills

APP_NAME = "ULTRON"
PORT = 18080
BASE = "http://127.0.0.1:%d" % PORT
APP_DIR = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "UltronAI")
BRAIN_DIR = os.path.join(APP_DIR, "brain")
MODEL_NAME = "Qwen3.5-0.8B-Q8_0.gguf"
MODEL_PATH = os.path.join(BRAIN_DIR, MODEL_NAME)
MIN_BYTES = 400 * 1024 * 1024
URLS = [
    "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q8_0.gguf",
    "https://huggingface.co/EnclaveHost/qwen3.5-0.8b-gguf/resolve/main/Qwen3.5-0.8B-Q8_0.gguf",
]
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

BG = "#05070b"
RED = (232, 40, 58)
BG_RGB = (5, 7, 11)
TEXT = "#f4f5f7"
MUTED = "#969eaf"

SYSTEM_PROMPT = (
    "You are ULTRON, a calm, precise and slightly formal voice assistant on the user's computer. "
    "You are being spoken aloud, so answer in one to three short plain sentences. "
    "Never use markdown, lists, symbols or emojis. Reply in the same language the user speaks."
)


def resource_path(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def mix(c1, c2, t):
    t = max(0.0, min(1.0, t))
    return "#%02x%02x%02x" % tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


def last_sentence_end(s):
    for i in range(len(s) - 1, -1, -1):
        if s[i] in ".!?\n\u0964\u061f" and i + 1 >= 12:
            return i + 1
    return 0


def clean_for_speech(s):
    out = []
    skip = False
    for ch in s:
        if ch == "<":
            skip = True
        elif ch == ">":
            skip = False
        elif not skip and ch not in "*#_`~>|":
            out.append(ch)
    return " ".join("".join(out).split())


# --------------------------------------------------------------------- PowerShell speech workers

STT_PS = r'''
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
  Add-Type -AssemblyName System.Speech
  try { $rec = New-Object System.Speech.Recognition.SpeechRecognitionEngine((New-Object System.Globalization.CultureInfo('en-US'))) }
  catch { $rec = New-Object System.Speech.Recognition.SpeechRecognitionEngine }
  $rec.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
  $rec.SetInputToDefaultAudioDevice()
  $rec.InitialSilenceTimeout = [TimeSpan]::FromSeconds(7)
  $rec.EndSilenceTimeout = [TimeSpan]::FromSeconds(1.0)
  Write-Output 'READY'
} catch {
  Write-Output 'ERR'
  exit 1
}
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($line -eq $null -or $line -eq 'QUIT') { break }
  if ($line -eq 'LISTEN') {
    try { $r = $rec.Recognize() } catch { $r = $null }
    if ($r -ne $null -and $r.Text) {
      Write-Output ('TEXT:' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($r.Text)))
    } else { Write-Output 'NONE' }
  }
}
'''

TTS_PS = r'''
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
  Add-Type -AssemblyName System.Speech
  $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
  # choose an installed MALE voice (prefer David / Mark / Guy), fall back to the Windows default
  $male = $s.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Gender -eq 'Male' }
  $pick = $male | Where-Object { $_.VoiceInfo.Name -match 'David|Mark|Guy|George|James' } | Select-Object -First 1
  if (-not $pick) { $pick = $male | Select-Object -First 1 }
  if ($pick) { $s.SelectVoice($pick.VoiceInfo.Name) }
  $s.Rate = 0
  Write-Output 'READY'
} catch {
  Write-Output 'ERR'
  exit 1
}
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($line -eq $null -or $line -eq 'QUIT') { break }
  if ($line.Length -gt 0) {
    $t = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($line))
    try {
      $x = [Security.SecurityElement]::Escape($t)
      $s.SpeakSsml('<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US"><prosody pitch="-18%" rate="-4%">' + $x + '</prosody></speak>')
    } catch {
      try { $s.Speak($t) } catch { }
    }
    Write-Output 'DONE'
  }
}
'''


class PsWorker:
    """A long-running PowerShell process we talk to line by line."""

    def __init__(self, script, filename):
        self.script = script
        self.filename = filename
        self.proc = None
        self.lines = queue.Queue()
        self.ok = False

    def start(self):
        os.makedirs(APP_DIR, exist_ok=True)
        path = os.path.join(APP_DIR, self.filename)
        with open(path, "w", encoding="utf-8-sig") as f:
            f.write(self.script)
        self.proc = subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", path],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            creationflags=NO_WINDOW)
        threading.Thread(target=self._reader, args=(self.proc,), daemon=True).start()
        try:
            first = self.lines.get(timeout=25)
        except queue.Empty:
            first = "ERR"
        self.ok = (first == "READY")
        return self.ok

    def _reader(self, proc):
        try:
            for raw in proc.stdout:
                self.lines.put(raw.decode("utf-8", "ignore").strip())
        except Exception:
            pass
        self.lines.put("EOF")

    def send(self, line):
        try:
            self.proc.stdin.write((line + "\n").encode("ascii", "ignore"))
            self.proc.stdin.flush()
            return True
        except Exception:
            return False

    def kill(self):
        p, self.proc = self.proc, None
        self.ok = False
        if p:
            try:
                p.kill()
            except Exception:
                pass


class Mouth:
    """Queues sentences to the Windows speech synthesiser."""

    def __init__(self, events):
        self.events = events
        self.worker = None
        self.pending = 0
        self.gen = 0
        self.lock = threading.Lock()
        self.starting = False

    def _ensure(self):
        if self.worker and self.worker.ok:
            return True
        w = PsWorker(TTS_PS, "ultron_tts.ps1")
        if not w.start():
            return False
        self.worker = w
        threading.Thread(target=self._watch, args=(w, self.gen), daemon=True).start()
        return True

    def _watch(self, w, gen):
        while True:
            try:
                line = w.lines.get()
            except Exception:
                return
            if line == "EOF":
                return
            if line == "DONE":
                with self.lock:
                    if gen != self.gen:
                        return
                    self.pending = max(0, self.pending - 1)
                    idle = self.pending == 0
                if idle:
                    self.events.put(("tts_idle", None))

    def speak(self, text):
        def run():
            with self.lock:
                gen = self.gen
            if not self._ensure():
                self.events.put(("tts_idle", None))
                return
            with self.lock:
                if gen != self.gen:
                    return
                self.pending += 1
            self.events.put(("tts_start", None))
            self.worker.send(base64.b64encode(text.encode("utf-8")).decode("ascii"))
        threading.Thread(target=run, daemon=True).start()

    def is_speaking(self):
        with self.lock:
            return self.pending > 0

    def stop(self):
        with self.lock:
            self.gen += 1
            self.pending = 0
        if self.worker:
            self.worker.kill()
            self.worker = None

    def shutdown(self):
        self.stop()


class Ears:
    def __init__(self):
        self.worker = None

    def listen(self):
        """Blocks until a phrase is heard. Returns text, '' (silence) or None (speech engine unavailable)."""
        if not (self.worker and self.worker.ok):
            w = PsWorker(STT_PS, "ultron_stt.ps1")
            if not w.start():
                w.kill()
                return None
            self.worker = w
        w = self.worker
        if not w.send("LISTEN"):
            self.worker = None
            return None
        while True:
            try:
                line = w.lines.get(timeout=40)
            except queue.Empty:
                return ""
            if line.startswith("TEXT:"):
                try:
                    return base64.b64decode(line[5:]).decode("utf-8", "ignore").strip()
                except Exception:
                    return ""
            if line == "NONE":
                return ""
            if line == "EOF":
                self.worker = None
                return None

    def shutdown(self):
        if self.worker:
            self.worker.send("QUIT")
            self.worker.kill()
            self.worker = None


# --------------------------------------------------------------------------------- brain engine

class Engine:
    def __init__(self):
        self.proc = None
        self.log = []

    def _health(self):
        try:
            with urllib.request.urlopen(BASE + "/health", timeout=1.5) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code
        except Exception:
            return -1

    def start(self):
        if self._health() == 200:
            return
        exe = resource_path("llama-server.exe")
        if not os.path.exists(exe):
            raise RuntimeError("Engine file llama-server.exe is missing from this build.")
        threads = max(2, min(8, (os.cpu_count() or 4) // 2))
        self.proc = subprocess.Popen(
            [exe, "-m", MODEL_PATH, "--host", "127.0.0.1", "--port", str(PORT),
             "-c", "4096", "-t", str(threads), "-np", "1"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, creationflags=NO_WINDOW)
        threading.Thread(target=self._reader, args=(self.proc,), daemon=True).start()

    def _reader(self, p):
        try:
            for raw in p.stdout:
                self.log.append(raw.decode("utf-8", "ignore").rstrip())
                del self.log[:-40]
        except Exception:
            pass

    def wait_ready(self, timeout=180):
        end = time.time() + timeout
        while time.time() < end:
            if self._health() == 200:
                return True
            if self.proc is not None and self.proc.poll() is not None:
                return False
            time.sleep(0.5)
        return False

    def recent_log(self):
        return "\n".join(self.log[-8:])

    def generate(self, prompt, on_piece, cancel):
        payload = {
            "prompt": prompt, "n_predict": 320, "temperature": 0.7, "top_k": 20, "top_p": 0.8,
            "min_p": 0.0, "presence_penalty": 1.0, "stream": True, "cache_prompt": True,
            "stop": ["<|im_end|>", "<|im_start|>"],
        }
        req = urllib.request.Request(
            BASE + "/completion", data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"})
        in_think = False
        out = []
        with urllib.request.urlopen(req, timeout=180) as resp:
            for raw in resp:
                if cancel.is_set():
                    break
                line = raw.decode("utf-8", "ignore").strip()
                if not line.startswith("data:"):
                    continue
                body = line[5:].strip()
                if not body:
                    continue
                o = json.loads(body)
                piece = o.get("content", "")
                if "<think>" in piece:
                    in_think = True
                if not in_think and piece:
                    out.append(piece)
                    on_piece(piece)
                if "</think>" in piece:
                    in_think = False
                if o.get("stop"):
                    break
        return "".join(out)

    def stop(self):
        if self.proc is not None:
            try:
                self.proc.kill()
            except Exception:
                pass
            self.proc = None


def brain_installed():
    try:
        if os.path.getsize(MODEL_PATH) < MIN_BYTES:
            return False
        with open(MODEL_PATH, "rb") as f:
            return f.read(4) == b"GGUF"
    except OSError:
        return False


def download_brain(on_progress, cancel):
    """Resumable download. Raises RuntimeError with a readable message on failure."""
    os.makedirs(BRAIN_DIR, exist_ok=True)
    part = MODEL_PATH + ".part"
    last = "Unknown error"
    for url in URLS:
        try:
            have = os.path.getsize(part) if os.path.exists(part) else 0
            headers = {"User-Agent": "UltronAI/0.2"}
            if have:
                headers["Range"] = "bytes=%d-" % have
            try:
                resp = urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30)
            except urllib.error.HTTPError as e:
                if e.code == 416 and have >= MIN_BYTES:
                    resp = None
                else:
                    raise
            if resp is not None:
                with resp:
                    if resp.status == 206:
                        total = have + int(resp.headers.get("Content-Length", 0))
                        mode = "ab"
                    else:
                        total = int(resp.headers.get("Content-Length", 0))
                        have = 0
                        mode = "wb"
                    done = have
                    lastrep = 0.0
                    with open(part, mode) as f:
                        while True:
                            if cancel.is_set():
                                raise RuntimeError("Cancelled")
                            chunk = resp.read(256 * 1024)
                            if not chunk:
                                break
                            f.write(chunk)
                            done += len(chunk)
                            if time.time() - lastrep > 0.25:
                                lastrep = time.time()
                                on_progress(done, total)
                    if total and done != total:
                        raise RuntimeError("Download interrupted - click to resume")
            if os.path.exists(MODEL_PATH):
                os.remove(MODEL_PATH)
            os.replace(part, MODEL_PATH)
            if not brain_installed():
                os.remove(MODEL_PATH)
                raise RuntimeError("Downloaded file is not a valid model")
            return
        except RuntimeError as e:
            if str(e) == "Cancelled":
                raise
            last = str(e)
        except Exception as e:
            last = str(e) or e.__class__.__name__
    raise RuntimeError(last)


# ----------------------------------------------------------------------------------------- app

class UltronApp:
    def __init__(self, root):
        self.root = root
        root.title("ULTRON")
        root.configure(bg=BG)
        root.geometry("980x720")
        root.minsize(560, 480)
        try:
            root.iconbitmap(resource_path("ultron.ico"))
        except Exception:
            pass

        self.c = tk.Canvas(root, bg=BG, highlightthickness=0)
        self.c.pack(fill="both", expand=True)
        self.c.bind("<Button-1>", lambda e: self.on_tap())
        root.bind("<space>", lambda e: self.on_tap())
        root.protocol("WM_DELETE_WINDOW", self.on_close)

        self.events = queue.Queue()
        self.engine = Engine()
        self.mouth = Mouth(self.events)
        self.ears = Ears()
        self.cancel_dl = threading.Event()

        self.state = "OFFLINE"
        self.status = ""
        self.caption = ""
        self.level = 0.0
        self.t = 0.0
        self.last = time.time()
        self.panel = None  # dict(title, text, button, progress, pct) or None
        self.btn = tk.Button(root, text="", bg="#e8283a", fg="white", activebackground="#ff4a5a",
                             activeforeground="white", relief="flat", bd=0, padx=26, pady=10,
                             font=("Segoe UI", 11, "bold"), cursor="hand2")

        self.engine_ready = False
        self.convo = False
        self.generating = False
        self.silent_rounds = 0
        self.listen_token = 0
        self.cancel_gen = threading.Event()
        self.history = []
        self.skills = win_skills.WinSkills(self.events)
        self.make_mini_core()

        self.set_state("OFFLINE", "OFFLINE")
        self.root.after(33, self.frame)
        self.root.after(50, self.poll)
        if brain_installed():
            self.boot_engine()
        else:
            self.show_setup()

    # ---------------------------------------------------------------- state helpers
    def set_state(self, s, text):
        self.state = s
        self.status = text

    def show_panel(self, title, text, button, handler, progress=None, pct=""):
        self.panel = {"title": title, "text": text, "progress": progress, "pct": pct}
        if button:
            self.btn.configure(text=button, command=handler, state="normal")
            self.btn.place(relx=0.5, rely=0.69, anchor="center")
        else:
            self.btn.place_forget()

    def hide_panel(self):
        self.panel = None
        self.btn.place_forget()

    # ---------------------------------------------------------------- brain setup
    def show_setup(self, error=None):
        self.set_state("OFFLINE", "BRAIN NOT INSTALLED")
        if error:
            self.show_panel("DOWNLOAD STOPPED", error + "\n\nYour progress is saved. Click to resume.",
                            "RESUME", self.start_download)
        else:
            self.show_panel(
                "INSTALL ULTRON BRAIN",
                "Ultron needs its brain before it can talk.\n\nOne-time download: about 0.8 GB (Qwen3.5 0.8B).\n"
                "After this, Ultron thinks completely offline on your computer.",
                "INSTALL BRAIN", self.start_download)

    def start_download(self):
        self.cancel_dl.clear()
        self.show_panel("DOWNLOADING BRAIN", "Keep this window open. If it stops, click resume.",
                        None, None, progress=0.0, pct="starting...")
        self.set_state("THINKING", "DOWNLOADING BRAIN")

        def progress(done, total):
            self.events.put(("dl_progress", (done, total)))

        def run():
            try:
                download_brain(progress, self.cancel_dl)
                self.events.put(("dl_done", None))
            except Exception as e:
                self.events.put(("dl_error", str(e)))
        threading.Thread(target=run, daemon=True).start()

    # ---------------------------------------------------------------- engine boot
    def boot_engine(self):
        self.hide_panel()
        self.set_state("THINKING", "WAKING UP")

        def run():
            try:
                self.engine.start()
                if self.engine.wait_ready(180):
                    self.events.put(("engine_ready", None))
                else:
                    self.events.put(("engine_error", "The brain did not start.\n\n" + self.engine.recent_log()))
            except Exception as e:
                self.events.put(("engine_error", str(e)))
        threading.Thread(target=run, daemon=True).start()

    # ---------------------------------------------------------------- conversation
    def on_tap(self):
        if not self.engine_ready:
            return
        if self.state in ("SPEAKING", "THINKING"):
            self.interrupt()
            self.convo = True
            self.start_listening()
        elif self.state == "LISTENING":
            self.convo = False
            self.listen_token += 1
            self.set_state("IDLE", "PAUSED  -  CLICK THE CORE OR PRESS SPACE")
        else:
            self.convo = True
            self.silent_rounds = 0
            self.start_listening()

    def interrupt(self):
        self.cancel_gen.set()
        self.mouth.stop()

    def start_listening(self):
        if not self.engine_ready:
            return
        self.caption = ""
        self.set_state("LISTENING", "LISTENING")
        self.listen_token += 1
        token = self.listen_token

        def run():
            text = self.ears.listen()
            self.events.put(("heard", (token, text)))
        threading.Thread(target=run, daemon=True).start()

    def on_heard(self, token, text):
        if token != self.listen_token or self.state != "LISTENING":
            return
        if text is None:
            self.convo = False
            self.set_state("IDLE", "NO MICROPHONE OR WINDOWS SPEECH RECOGNIZER FOUND")
            return
        if not text:
            self.silent_rounds += 1
            if self.silent_rounds >= 5:
                self.convo = False
                self.set_state("IDLE", "PAUSED  -  CLICK THE CORE OR PRESS SPACE")
            elif self.convo:
                self.start_listening()
            return
        self.silent_rounds = 0
        self.caption = text
        t = text.lower().strip(" .!?,")
        if t in ("stop", "cancel", "be quiet", "quiet", "enough"):
            self.interrupt()
            self.start_listening()
            return
        if t in ("go to sleep", "sleep", "stop listening", "goodbye"):
            self.convo = False
            self.set_state("SPEAKING", "SPEAKING")
            self.mouth.speak("Standing by. Click the core when you need me.")
            return
        topic = win_skills.doc_topic(text)
        if topic:
            self.make_document(topic)
            return
        quick = self.skills.handle(text)
        if quick is not None:
            self.set_state("SPEAKING", "SPEAKING")
            self.mouth.speak(quick)
            return
        self.set_state("THINKING", "THINKING")
        self.think(text)

    def build_prompt(self):
        parts = ["<|im_start|>system\n%s<|im_end|>\n" % SYSTEM_PROMPT]
        for role, text in self.history[-8:]:
            parts.append("<|im_start|>%s\n%s<|im_end|>\n" % (role, text))
        parts.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        return "".join(parts)

    def think(self, user_text):
        self.history.append(("user", user_text))
        prompt = self.build_prompt()
        cancel = threading.Event()
        self.cancel_gen = cancel
        self.generating = True

        def run():
            buf = []
            full = []
            err = None

            def on_piece(p):
                buf.append(p)
                full.append(p)
                s = "".join(buf)
                cut = last_sentence_end(s)
                if cut > 0:
                    ready, rest = s[:cut], s[cut:]
                    buf[:] = [rest]
                    self.events.put(("say", (ready, cancel)))
            try:
                self.engine.generate(prompt, on_piece, cancel)
            except Exception as e:
                err = str(e) or e.__class__.__name__
            tail = "".join(buf).strip()
            if tail and not cancel.is_set():
                self.events.put(("say", (tail, cancel)))
            self.events.put(("gen_done", (cancel, err, "".join(full).strip())))
        threading.Thread(target=run, daemon=True).start()

    def after_speech(self):
        if self.generating:
            self.set_state("THINKING", "THINKING")
        elif self.convo and self.engine_ready:
            self.start_listening()
        elif self.engine_ready:
            self.set_state("IDLE", "PAUSED  -  CLICK THE CORE OR PRESS SPACE")

    # ---------------------------------------------------------------- events from threads
    def poll(self):
        try:
            while True:
                name, data = self.events.get_nowait()
                self.handle(name, data)
        except queue.Empty:
            pass
        self.root.after(50, self.poll)

    def handle(self, name, data):
        if name == "dl_progress":
            done, total = data
            frac = (done / total) if total else 0.0
            self.panel = {"title": "DOWNLOADING BRAIN", "text": "Keep this window open. If it stops, click resume.",
                          "progress": frac, "pct": "%d MB / %d MB" % (done // 1048576, max(total, done) // 1048576)}
        elif name == "dl_done":
            self.boot_engine()
        elif name == "dl_error":
            self.show_setup(data)
        elif name == "engine_ready":
            self.engine_ready = True
            self.convo = True
            self.set_state("SPEAKING", "ONLINE")
            self.mouth.speak("Ultron online. How can I help you?")
        elif name == "engine_error":
            self.set_state("OFFLINE", "BRAIN ERROR")
            self.show_panel("BRAIN FAILED TO START", data, "TRY AGAIN", self.boot_engine)
        elif name == "heard":
            self.on_heard(*data)
        elif name == "say":
            text, cancel = data
            clean = clean_for_speech(text)
            if clean and not cancel.is_set():
                self.mouth.speak(clean)
        elif name == "say_now":
            self.mouth.speak(data)
        elif name == "status":
            if self.generating and not self.mouth.is_speaking():
                self.set_state("THINKING", data)
        elif name == "doc_done":
            self.generating = False
            if not self.mouth.is_speaking():
                self.after_speech()
        elif name == "tts_start":
            self.set_state("SPEAKING", "SPEAKING")
        elif name == "tts_idle":
            self.after_speech()
        elif name == "gen_done":
            cancel, err, answer = data
            if cancel is not self.cancel_gen:
                return
            self.generating = False
            if not cancel.is_set():
                if err:
                    self.convo = False
                    self.caption = err
                    self.set_state("IDLE", "BRAIN ERROR  -  CLICK THE CORE")
                    return
                if answer:
                    self.history.append(("assistant", answer))
                else:
                    self.mouth.speak("Sorry, I didn't catch that.")
            if not self.mouth.is_speaking():
                self.after_speech()

    # ---------------------------------------------------------------- Word documents
    def ask(self, instruction, cancel):
        prompt = ("<|im_start|>system\nYou are a careful writer. Output plain text only: no markdown, no bullet symbols, "
                  "no numbering.<|im_end|>\n<|im_start|>user\n" + instruction +
                  "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n")
        return self.engine.generate(prompt, lambda p: None, cancel).strip()

    def make_document(self, topic):
        """Writes a document with the local brain. Word opens first, then the text appears in it section by section."""
        cancel = threading.Event()
        self.cancel_gen = cancel
        self.generating = True
        self.set_state("THINKING", "WRITING DOCUMENT")
        self.caption = topic
        self.mouth.speak("Writing a document about " + topic + ". Word will open and I will write in it.")

        def run():
            ev = self.events
            try:
                title = " ".join(w[:1].upper() + w[1:] for w in topic.split())
                ev.put(("status", "PLANNING DOCUMENT"))
                outline = self.ask("List exactly 5 short section headings for an educational document about " + topic +
                                   ". One heading per line. Start with an introduction and end with a conclusion.", cancel)
                heads = []
                for line in outline.split("\n"):
                    h = line.strip().lstrip("0123456789.)-*#: ").replace("*", "").replace("#", "").strip()
                    if 3 <= len(h) <= 70 and len(heads) < 6:
                        heads.append(h)
                if len(heads) < 3:
                    heads = ["Introduction", "Key Facts", "How It Works", "Why It Matters", "Conclusion"]

                slug = "".join(ch if ch.isalnum() else "-" for ch in topic.lower()).strip("-")[:40] or "document"
                live = os.path.join(win_skills.DOCS_DIR, "ultron-" + slug + ".docx")
                full = os.path.join(win_skills.DOCS_DIR, "ultron-" + slug + "-full.docx")
                win_skills.build_docx(live, title, [])
                os.startfile(live)                      # opens in Word (or whatever opens .docx)
                time.sleep(7)

                sections = []
                typed_ok = True
                for i, h in enumerate(heads, 1):
                    if cancel.is_set():
                        raise RuntimeError("Cancelled")
                    ev.put(("status", "WRITING %d OF %d" % (i, len(heads))))
                    body = self.ask('Write one clear, factual paragraph of about 90 words for the section "%s" of a '
                                    'document about %s.' % (h, topic), cancel).replace("*", "").replace("#", "").strip()
                    sections.append((h, body))
                    if typed_ok:
                        typed_ok = win_skills.paste_into_word("\r\n" + h + "\r\n" + body + "\r\n")
                win_skills.build_docx(full, title, sections)   # a complete copy, always
                if typed_ok:
                    win_skills.save_word()
                    ev.put(("say_now", "Your document is ready in Word."))
                else:
                    os.startfile(full)
                    ev.put(("say_now", "Your document is ready. I opened the finished copy."))
            except Exception as e:
                if str(e) != "Cancelled":
                    ev.put(("say_now", "Sorry, I could not write the document."))
            ev.put(("doc_done", None))
        threading.Thread(target=run, daemon=True).start()

    # ---------------------------------------------------------------- floating core
    def make_mini_core(self):
        """A small always-on-top core that stays on screen when the main window is minimised."""
        self.mini = tk.Toplevel(self.root)
        self.mini.withdraw()
        self.mini.overrideredirect(True)
        self.mini.attributes("-topmost", True)
        try:
            self.mini.attributes("-transparentcolor", BG)
        except Exception:
            pass
        self.mini.geometry("120x120+60+260")
        self.mini_c = tk.Canvas(self.mini, width=120, height=120, bg=BG, highlightthickness=0)
        self.mini_c.pack()
        self.mini_drag = {"x": 0, "y": 0, "moved": False}

        def press(e):
            self.mini_drag.update(x=e.x_root - self.mini.winfo_x(), y=e.y_root - self.mini.winfo_y(), moved=False)

        def move(e):
            self.mini_drag["moved"] = True
            self.mini.geometry("+%d+%d" % (e.x_root - self.mini_drag["x"], e.y_root - self.mini_drag["y"]))

        def release(e):
            if not self.mini_drag["moved"]:
                self.on_tap()
        self.mini_c.bind("<ButtonPress-1>", press)
        self.mini_c.bind("<B1-Motion>", move)
        self.mini_c.bind("<ButtonRelease-1>", release)

        def sync(_e=None):
            try:
                iconic = self.root.state() == "iconic"
            except Exception:
                return
            if iconic and self.engine_ready:
                self.mini.deiconify()
                self.mini.attributes("-topmost", True)
            else:
                self.mini.withdraw()
        self.root.bind("<Unmap>", lambda e: self.root.after(120, sync) if e.widget is self.root else None)
        self.root.bind("<Map>", lambda e: self.root.after(120, sync) if e.widget is self.root else None)

    # ---------------------------------------------------------------- drawing
    def frame(self):
        now = time.time()
        dt = min(0.05, now - self.last)
        self.last = now
        self.t += dt
        t = self.t
        c = self.c
        w = max(100, c.winfo_width())
        h = max(100, c.winfo_height())
        cx, cy = w / 2, h * 0.47
        R = min(w, h) * 0.21

        st = self.state
        alpha = 1.0
        if st == "LISTENING":
            target = 0.22 + 0.14 * math.sin(t * 3.1) ** 2 + 0.10 * random.random()
            spin = 0.6
        elif st == "THINKING":
            target = 0.30 + 0.10 * math.sin(t * 6.0)
            spin = 3.2
        elif st == "SPEAKING":
            target = 0.40 + 0.30 * abs(math.sin(t * 9.0)) + 0.18 * math.sin(t * 17.3 + 1.3)
            spin = 1.1
        elif st == "IDLE":
            target = 0.08 + 0.05 * math.sin(t * 1.6)
            spin = 0.25
        else:
            target = 0.03
            spin = 0.1
            alpha = 0.45
        target = max(0.0, min(1.0, target))
        self.level += (target - self.level) * min(1.0, dt * 10)
        lv = self.level

        c.delete("all")

        self.draw_orb(c, cx, cy, R, t, lv, alpha, spin, st)
        try:
            if self.mini.state() == "normal":
                mc = self.mini_c
                mc.delete("all")
                self.draw_orb(mc, 60, 60, 26, t, lv, alpha, spin, st)
        except Exception:
            pass

        # texts
        c.create_text(w / 2, 42, text="U  L  T  R  O  N", fill="#e8283a", font=("Segoe UI", 22, "bold"))
        c.create_text(w / 2, 76, text=self.status, fill=MUTED, font=("Segoe UI", 10))
        if self.caption:
            c.create_text(w / 2, h - 64, text=self.caption, fill=MUTED, font=("Segoe UI", 12),
                          width=max(200, w - 120), justify="center")
        c.create_text(w / 2, h - 24, text="click the core or press SPACE to talk / pause",
                      fill="#4c5262", font=("Segoe UI", 9))

        # setup / error panel
        p = self.panel
        if p:
            x1, y1, x2, y2 = w * 0.14, h * 0.20, w * 0.86, h * 0.77
            c.create_rectangle(x1, y1, x2, y2, fill="#0b0e15", outline="#e8283a", width=1)
            c.create_text(w / 2, y1 + 34, text=p["title"], fill=TEXT, font=("Segoe UI", 15, "bold"))
            c.create_text(w / 2, y1 + 104, text=p["text"], fill=MUTED, font=("Segoe UI", 11),
                          width=(x2 - x1) - 60, justify="center")
            if p["progress"] is not None:
                bx1, bx2, by = x1 + 40, x2 - 40, y2 - 92
                c.create_rectangle(bx1, by, bx2, by + 10, fill="#1a1f2b", outline="")
                c.create_rectangle(bx1, by, bx1 + (bx2 - bx1) * max(0.0, min(1.0, p["progress"])), by + 10,
                                   fill="#e8283a", outline="")
                c.create_text(w / 2, by + 28, text=p["pct"], fill=MUTED, font=("Segoe UI", 10))

        self.root.after(33, self.frame)

    def draw_orb(self, c, cx, cy, R, t, lv, alpha, spin, st):
        # glow
        gr = R * (1.7 + 0.6 * lv)
        steps = 18
        for i in range(steps):
            k = (i + 1) / steps
            r = gr * (1 - i / steps)
            c.create_oval(cx - r, cy - r, cx + r, cy + r, outline="",
                          fill=mix(BG_RGB, RED, (k ** 2.4) * 0.5 * (0.5 + lv) * alpha))

        red = mix(BG_RGB, RED, alpha)

        def ring(r, width, segs, sweep, rot):
            step = 360.0 / segs
            for i in range(segs):
                c.create_arc(cx - r, cy - r, cx + r, cy + r, start=rot + i * step, extent=sweep,
                             style="arc", outline=red, width=width)
        ring(R * 0.62, max(2, int(R * 0.045)), 4, 62, t * 40 * spin)
        ring(R * 0.82, max(2, int(R * 0.03)), 6, 38, -t * 55 * spin)
        ring(R * 1.02, max(3, int(R * 0.06)), 3, 78, t * 22 * spin)

        # radial voice ring
        n = 72
        base = R * 1.16
        for i in range(n):
            a = math.radians(i * 360.0 / n) + t * 0.15 * spin
            wave = 0.5 + 0.5 * math.sin(i * 0.55 + t * (9 if st == "THINKING" else 5))
            wave2 = 0.5 + 0.5 * math.sin(i * 1.7 - t * 7.3)
            ln = R * 0.04 + R * 0.34 * lv * (0.35 + 0.65 * wave * wave2)
            ca, sa = math.cos(a), math.sin(a)
            c.create_line(cx + base * ca, cy + base * sa, cx + (base + ln) * ca, cy + (base + ln) * sa,
                          fill=red, width=2, capstyle="round")

        # ripples
        if st in ("LISTENING", "SPEAKING"):
            for k in range(3):
                f = (t * 0.55 + k / 3.0) % 1.0
                r = R * (1.55 + 0.9 * f)
                col = mix(BG_RGB, RED, (1 - f) * (0.25 + lv) * 0.6)
                c.create_oval(cx - r, cy - r, cx + r, cy + r, outline=col, width=1)

        # hot core
        cr = R * (0.30 + 0.14 * lv)
        for scale, col in ((1.25, (255, 70, 80)), (0.78, (255, 205, 200)), (0.38, (255, 255, 255))):
            rr = cr * scale
            c.create_oval(cx - rr, cy - rr, cx + rr, cy + rr, outline="",
                          fill=mix(BG_RGB, col, alpha))

    def on_close(self):
        self.cancel_dl.set()
        self.cancel_gen.set()
        try:
            self.mouth.shutdown()
            self.ears.shutdown()
            self.engine.stop()
        finally:
            self.root.destroy()


def main():
    root = tk.Tk()
    UltronApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
