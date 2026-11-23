"""ULTRON desktop plugins: the Windows counterpart of the Android voice plugins and accessibility actions.

Everything here is plain Python + PowerShell (built into Windows), so it needs no extra downloads.
"""
import base64
import ctypes
import datetime
import os
import re
import subprocess
import threading
import time
import webbrowser
import zipfile
from urllib.parse import quote

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
APP_DIR = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "UltronAI")
DOCS_DIR = os.path.join(os.path.expanduser("~"), "Documents", "Ultron")


def ps(script, timeout=25):
    """Runs a PowerShell script (encoded, so quoting never breaks) and returns its output text."""
    enc = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    try:
        r = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc],
            capture_output=True, timeout=timeout, creationflags=NO_WINDOW)
        return r.stdout.decode("utf-8", "ignore").strip()
    except Exception:
        return ""


def _b64(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def set_clipboard(text):
    ps("Set-Clipboard -Value ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s')))" % _b64(text))


def send_keys(keys):
    ps("Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait('%s')" % keys.replace("'", "''"))


def paste_into_word(text):
    """Types text at the end of the open Word document (clipboard + Ctrl+End + Ctrl+V). True if Word took it."""
    script = (
        "$t=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'));"
        "Set-Clipboard -Value $t;"
        "$w=New-Object -ComObject WScript.Shell;"
        "if($w.AppActivate('Word')){Start-Sleep -m 600;$w.SendKeys('^{END}');Start-Sleep -m 250;"
        "$w.SendKeys('^v');Start-Sleep -m 500;Write-Output 'OK'}else{Write-Output 'NO'}" % _b64(text))
    return ps(script, 40).endswith("OK")


def save_word():
    ps("$w=New-Object -ComObject WScript.Shell; if($w.AppActivate('Word')){Start-Sleep -m 400;$w.SendKeys('^s')}")


# ------------------------------------------------------------------ documents

def _esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _para(text, bold=False, half_pts=24, center=False):
    return ("<w:p><w:pPr>%s<w:spacing w:after=\"160\"/></w:pPr><w:r><w:rPr>%s<w:sz w:val=\"%d\"/></w:rPr>"
            "<w:t xml:space=\"preserve\">%s</w:t></w:r></w:p>") % (
        '<w:jc w:val="center"/>' if center else "", "<w:b/>" if bold else "", half_pts, _esc(text))


def build_docx(path, title, sections):
    """sections = [(heading, body)]. A minimal, valid .docx with no libraries."""
    body = [_para(title, True, 44, True)]
    for head, text in sections:
        body.append(_para(head, True, 30))
        for p in [x.strip() for x in text.split("\n") if x.strip()]:
            body.append(_para(p))
    doc = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
           + "".join(body) +
           '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" '
           'w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>')
    types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
             '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
             '<Default Extension="xml" ContentType="application/xml"/>'
             '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
             '</Types>')
    rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
            '</Relationships>')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", types)
        z.writestr("_rels/.rels", rels)
        z.writestr("word/document.xml", doc)


def doc_topic(raw):
    """'make a document about black holes in MS Word' -> 'black holes'. None if it is not a document request."""
    t = raw.lower().strip()
    has_doc = any(k in t for k in ["document", " doc ", "report", "essay", "assignment", "article", "notes on",
                                   "notes about", "डॉक्यूमेंट", "डॉक्युमेंट", "दस्तावेज", "रिपोर्ट", "असाइनमेंट", "निबंध"])
    has_make = any(k in t for k in ["make", "create", "write", "prepare", "generate", "type", "बना", "लिख"])
    has_word = any(k in t for k in ["ms word", "microsoft word", " word", "वर्ड"])
    if not ((has_doc and has_make) or (has_word and has_make)):
        return None
    topic = None
    m = re.search(r"(?:on|about|regarding)\s+(.+)$", t)
    if m:
        topic = m.group(1)
    if topic is None:
        m = re.search(r"(.+?)\s*(?:के बारे में|के बारे मे|के ऊपर|पर)\s", t + " ")
        if m:
            topic = m.group(1)
    if not topic:
        return None
    topic = re.sub(r"\s+(?:in|using|with|on|inside)\s+(?:ms\s+|microsoft\s+)?word\s*$", "", topic, flags=re.I)
    topic = re.sub(r"\s*(?:वर्ड में|एमएस वर्ड में)\s*$", "", topic)
    topic = re.sub(r"^(?:a |an |the |एक )", "", topic, flags=re.I)
    topic = re.sub(r"(?:डॉक्यूमेंट|डॉक्युमेंट|दस्तावेज़|दस्तावेज|रिपोर्ट|असाइनमेंट|वर्ड में|बनाओ|बना दो|लिखो|लिख दो)", " ", topic)
    topic = " ".join(topic.split())
    return topic if len(topic) >= 2 else None


# ------------------------------------------------------------------ plugins

def _has(t, *keys):
    return any(k in t for k in keys)


APPS = {
    "notepad": "notepad.exe", "calculator": "calc.exe", "calc": "calc.exe", "paint": "mspaint.exe",
    "word": "winword.exe", "ms word": "winword.exe", "microsoft word": "winword.exe",
    "excel": "excel.exe", "powerpoint": "powerpnt.exe", "chrome": "chrome.exe", "google chrome": "chrome.exe",
    "edge": "msedge.exe", "firefox": "firefox.exe", "file explorer": "explorer.exe", "explorer": "explorer.exe",
    "task manager": "taskmgr.exe", "command prompt": "cmd.exe", "cmd": "cmd.exe", "settings": "ms-settings:",
    "camera": "microsoft.windows.camera:", "store": "ms-windows-store:", "whatsapp": "whatsapp:",
    "spotify": "spotify:", "snipping tool": "ms-screenclip:", "clock": "ms-clock:", "calendar": "outlookcal:",
}


class WinSkills:
    def __init__(self, events):
        self.events = events  # queue: ("say_now", text) is spoken by the app

    def handle(self, raw):
        t = raw.lower().strip().rstrip("?!. ")
        try:
            # time / date
            if _has(t, "what time", "the time", "time is it", "समय क्या", "टाइम क्या", "कितने बजे"):
                return "It is " + datetime.datetime.now().strftime("%I:%M %p").lstrip("0") + "."
            if _has(t, "what date", "today's date", "what day", "तारीख", "कौन सा दिन"):
                return "Today is " + datetime.datetime.now().strftime("%A, %d %B %Y") + "."

            # battery
            if _has(t, "battery", "बैटरी"):
                return self.battery()

            # calculator
            m = re.search(r"(-?\d+(?:\.\d+)?)\s*(plus|minus|times|multiplied by|into|divided by|over|x|\+|-|\*|/)\s*(-?\d+(?:\.\d+)?)", t)
            if m and _has(t, "calculate", "what is", "what's", "how much", "plus", "minus", "times", "divided", "multiplied"):
                a, b, op = float(m.group(1)), float(m.group(3)), m.group(2)
                if op in ("plus", "+"):
                    r = a + b
                elif op in ("minus", "-"):
                    r = a - b
                elif op in ("times", "multiplied by", "into", "x", "*"):
                    r = a * b
                else:
                    if b == 0:
                        return "I cannot divide by zero."
                    r = a / b
                s = str(int(r)) if r == int(r) else ("%.4f" % r).rstrip("0").rstrip(".")
                return "The answer is " + s + "."

            # timer / alarm (they ring while ULTRON is running)
            m = re.search(r"timer (?:for )?(\d+)\s*(second|sec|minute|min|hour)", t)
            if m:
                n, u = int(m.group(1)), m.group(2)
                secs = n * 3600 if u.startswith("h") else (n * 60 if u.startswith("m") else n)
                threading.Timer(secs, lambda: self.events.put(("say_now", "Your timer is done."))).start()
                return "Timer set for %d %s%s." % (n, "hour" if u.startswith("h") else "minute" if u.startswith("m") else "second", "" if n == 1 else "s")
            m = re.search(r"alarm (?:for|at) (\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?", t)
            if m:
                hh, mm = int(m.group(1)), int(m.group(2) or 0)
                ap = (m.group(3) or "").replace(".", "")
                if ap == "pm" and hh < 12:
                    hh += 12
                if ap == "am" and hh == 12:
                    hh = 0
                now = datetime.datetime.now()
                when = now.replace(hour=hh % 24, minute=mm, second=0, microsecond=0)
                if when <= now:
                    when += datetime.timedelta(days=1)
                threading.Timer((when - now).total_seconds(), lambda: self.events.put(("say_now", "Your alarm is ringing."))).start()
                return "Alarm set for %02d:%02d." % (hh % 24, mm)

            # notes
            m = re.search(r"^(?:take a note|note down|make a note|write a note|add a note)\s*(?:that|to)?\s*(.+)$", t)
            if m:
                os.makedirs(APP_DIR, exist_ok=True)
                with open(os.path.join(APP_DIR, "notes.txt"), "a", encoding="utf-8") as f:
                    f.write(m.group(1).strip() + "\n")
                return "Noted."
            if _has(t, "read my notes", "read my note", "what are my notes"):
                return self.read_notes()

            # web
            m = re.search(r"(?:play|watch)\s+(.+?)\s+on youtube", t) or re.search(r"^play\s+(.+)$", t)
            if m:
                webbrowser.open("https://www.youtube.com/results?search_query=" + quote(m.group(1)))
                return "Playing " + m.group(1) + " on YouTube."
            if _has(t, "weather", "मौसम"):
                webbrowser.open("https://www.google.com/search?q=" + quote("weather " + t.replace("weather", "").strip()))
                return "Here is the weather."
            m = re.search(r"^(?:navigate to|directions to|take me to|show)\s+(.+?)(?:\s+on (?:the )?map|\s+on google maps)?$", t) \
                if _has(t, "navigate to", "directions to", "take me to", "on the map", "on maps") else None
            if m:
                webbrowser.open("https://www.google.com/maps/search/" + quote(m.group(1)))
                return "Showing " + m.group(1) + " on the map."
            m = re.search(r"^(?:search|google|look up)(?: for)?\s+(.+)$", t)
            if m:
                webbrowser.open("https://www.google.com/search?q=" + quote(m.group(1)))
                return "Searching for " + m.group(1) + "."

            # computer control (the Windows counterpart of the phone's accessibility actions)
            if _has(t, "volume up", "louder", "increase volume"):
                ps("$w=New-Object -ComObject WScript.Shell; 1..5|%{$w.SendKeys([char]175)}")
                return "Volume increased."
            if _has(t, "volume down", "quieter", "decrease volume", "lower the volume"):
                ps("$w=New-Object -ComObject WScript.Shell; 1..5|%{$w.SendKeys([char]174)}")
                return "Volume lowered."
            if _has(t, "mute", "unmute"):
                ps("$w=New-Object -ComObject WScript.Shell; $w.SendKeys([char]173)")
                return "Done."
            if _has(t, "take a screenshot", "take screenshot", "screenshot"):
                os.startfile("ms-screenclip:")
                return "Select the area to capture."
            if _has(t, "lock the screen", "lock the computer", "lock computer", "lock the pc"):
                subprocess.Popen(["rundll32.exe", "user32.dll,LockWorkStation"], creationflags=NO_WINDOW)
                return "Locking the screen."
            if _has(t, "minimize all", "show desktop", "go home"):
                ps("(New-Object -ComObject Shell.Application).MinimizeAll()")
                return "Showing the desktop."
            if _has(t, "switch window", "next window", "recent apps"):
                send_keys("%{TAB}")
                return "Switching window."
            if _has(t, "close window", "close this window"):
                send_keys("%{F4}")
                return "Closing the window."
            if _has(t, "go back", "press back"):
                send_keys("%{LEFT}")
                return "Going back."
            if _has(t, "scroll down"):
                send_keys("{PGDN}")
                return "Scrolling down."
            if _has(t, "scroll up"):
                send_keys("{PGUP}")
                return "Scrolling up."
            if _has(t, "press enter"):
                send_keys("{ENTER}")
                return "Done."
            m = re.search(r"^type\s+(.+)$", raw.strip(), re.I)
            if m:
                set_clipboard(m.group(1))
                send_keys("^v")
                return "Typed."
            m = re.search(r"^(?:click|tap|press)(?: on)?\s+(.+)$", t)
            if m:
                return self.click(m.group(1).strip())

            # open an app
            m = re.search(r"^(?:open|launch|start)\s+(.+)$", t)
            if m:
                return self.open_app(m.group(1).strip())
        except Exception:
            return "Sorry, I could not do that."
        return None

    # ---------------------------------------------------------------- helpers
    def battery(self):
        class SPS(ctypes.Structure):
            _fields_ = [("AC", ctypes.c_ubyte), ("Flag", ctypes.c_ubyte), ("Pct", ctypes.c_ubyte), ("Sav", ctypes.c_ubyte),
                        ("Life", ctypes.c_ulong), ("Full", ctypes.c_ulong)]
        s = SPS()
        if ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(s)) and s.Pct != 255:
            return "Battery is at %d percent%s." % (s.Pct, ", charging" if s.AC == 1 else "")
        return "This computer has no battery."

    def read_notes(self):
        p = os.path.join(APP_DIR, "notes.txt")
        if not os.path.exists(p):
            return "You have no notes."
        with open(p, encoding="utf-8") as f:
            lines = [x.strip() for x in f if x.strip()]
        if not lines:
            return "You have no notes."
        return "You have %d note%s. %s" % (len(lines), "" if len(lines) == 1 else "s", " ".join(l + "." for l in lines[-5:]))

    def open_app(self, name):
        q = name.replace("the ", "").replace(" app", "").strip()
        target = APPS.get(q)
        try:
            if target:
                os.startfile(target)
            else:
                subprocess.Popen(["cmd", "/c", "start", "", q], creationflags=NO_WINDOW)
            return "Opening " + q + "."
        except Exception:
            return "I could not find an app called " + q + "."

    def click(self, name):
        script = r'''
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
Add-Type @"
using System; using System.Runtime.InteropServices;
public class FG { [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); }
"@
$root=[System.Windows.Automation.AutomationElement]::FromHandle([FG]::GetForegroundWindow())
$n=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'))
$c=New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty,$n,[System.Windows.Automation.PropertyConditionFlags]::IgnoreCase)
$el=$root.FindFirst([System.Windows.Automation.TreeScope]::Descendants,$c)
if($el -ne $null){ try{ $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke(); 'OK' }catch{ try{ $el.SetFocus(); 'OK' }catch{ 'NO' } } } else { 'NO' }
''' % _b64(name)
        return ("Pressed " + name + ".") if ps(script, 30).endswith("OK") else ("I could not find " + name + " on the screen.")
