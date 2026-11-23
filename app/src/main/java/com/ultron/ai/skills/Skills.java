package com.ultron.ai.skills;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;

import com.ultron.ai.UltronAccessibilityService;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in voice plugins. Each one recognises a spoken command and does it on the phone.
 * handle() returns the sentence Ultron should say, or null when the text is not a command
 * (then the brain answers instead).
 */
public class Skills {
    public static final int REQ_CONTACTS = 78;

    private final Activity act;

    public Skills(Activity a) { act = a; }

    private static boolean has(String t, String... keys) {
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }

    // ------------------------------------------------------------------ commands

    public String handle(String raw) {
        String t = raw.toLowerCase(Locale.ROOT).trim();
        try {
            // ---- time and date
            if (has(t, "what time", "the time", "time is it", "समय क्या", "टाइम क्या", "कितने बजे")) {
                return "It is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()) + ".";
            }
            if (has(t, "what date", "today's date", "what day", "तारीख", "कौन सा दिन")) {
                return "Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(new Date()) + ".";
            }

            // ---- battery
            if (has(t, "battery", "बैटरी")) {
                BatteryManager bm = (BatteryManager) act.getSystemService(Context.BATTERY_SERVICE);
                return "Battery is at " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent.";
            }

            // ---- floating core permission
            if (has(t, "stay with me", "floating", "over other apps", "overlay", "bubble")) {
                if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(act)) {
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.getPackageName())));
                    return "Please allow Ultron to display over other apps.";
                }
                return "I am already able to stay with you over other apps.";
            }

            // ---- accessibility: act inside other apps (needs ULTRON turned on in Accessibility settings)
            if (has(t, "accessibility settings", "turn on accessibility")) {
                act.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return "Opening accessibility settings. Turn on Ultron there.";
            }
            String a11y = accessibilityCommand(t);
            if (a11y != null) return a11y;

            // ---- flashlight
            if (has(t, "flashlight", "torch", "टॉर्च", "फ्लैशलाइट")) {
                return setTorch(!has(t, "off", "बंद", "band"));
            }

            // ---- volume
            AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
            if (has(t, "volume up", "louder", "increase volume", "आवाज़ बढ़ा", "आवाज बढ़ा")) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return "Volume increased.";
            }
            if (has(t, "volume down", "quieter", "decrease volume", "lower the volume", "आवाज़ कम", "आवाज कम")) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return "Volume lowered.";
            }

            // ---- music controls for whatever player is running
            if (has(t, "pause music", "stop music", "pause the music", "pause song")) { media(am, KeyEvent.KEYCODE_MEDIA_PAUSE); return "Paused."; }
            if (has(t, "resume music", "play music", "continue music", "resume the music")) { media(am, KeyEvent.KEYCODE_MEDIA_PLAY); return "Playing."; }
            if (has(t, "next song", "next track", "skip song", "skip this song")) { media(am, KeyEvent.KEYCODE_MEDIA_NEXT); return "Next track."; }
            if (has(t, "previous song", "previous track", "last song")) { media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); return "Previous track."; }

            // ---- timer / alarm
            Matcher m = Pattern.compile("timer (?:for )?(\\d+)\\s*(second|sec|minute|min|hour)").matcher(t);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                String u = m.group(2);
                int secs = u.startsWith("h") ? n * 3600 : (u.startsWith("m") ? n * 60 : n);
                act.startActivity(new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true));
                return "Timer set for " + n + " " + (u.startsWith("h") ? "hour" : u.startsWith("m") ? "minute" : "second") + (n == 1 ? "." : "s.");
            }
            m = Pattern.compile("alarm (?:for|at) (\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").matcher(t);
            if (m.find()) {
                int hh = Integer.parseInt(m.group(1));
                int mm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                String ap = m.group(3) == null ? "" : m.group(3).replace(".", "");
                if (ap.equals("pm") && hh < 12) hh += 12;
                if (ap.equals("am") && hh == 12) hh = 0;
                act.startActivity(new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hh).putExtra(AlarmClock.EXTRA_MINUTES, mm)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true));
                return String.format(Locale.US, "Alarm set for %02d:%02d.", hh, mm);
            }

            // ---- messages: "send whatsapp to ali saying hello", "send a message to 0300... saying hi"
            m = Pattern.compile("send (?:a |an )?(whatsapp|message|sms|text)(?: message)? to (.+?)\\s+(?:saying|that says|that|message)\\s+(.+)$").matcher(t);
            if (m.find()) {
                boolean wa = m.group(1).equals("whatsapp");
                String number = resolveNumber(m.group(2));
                if (number == null) return "I could not find " + m.group(2) + " in your contacts.";
                if (number.equals("PERM")) return "Please allow contacts access, then ask me again.";
                String body = m.group(3);
                if (wa) {
                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + number.replaceAll("[^0-9]", "") + "?text=" + Uri.encode(body))));
                    return "WhatsApp is open with your message. Press send.";
                }
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
                i.putExtra("sms_body", body);
                act.startActivity(i);
                return "Your message is ready. Press send.";
            }

            // ---- calling: a number, or a contact name
            m = Pattern.compile("^(?:please )?(?:call|dial|phone)\\s+(.+)$").matcher(t);
            if (m.find()) {
                String who = m.group(1).trim();
                String number = resolveNumber(who);
                if (number == null) return "I could not find " + who + " in your contacts.";
                if (number.equals("PERM")) return "Please allow contacts access, then ask me again.";
                act.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)));
                return "Opening the dialer. Press call.";
            }

            // ---- notes
            m = Pattern.compile("^(?:remember|note|make a note)(?: that| down)?\\s+(.+)$").matcher(t);
            if (m.find()) {
                SharedPreferences p = act.getSharedPreferences("ultron_notes", Context.MODE_PRIVATE);
                String old = p.getString("notes", "");
                p.edit().putString("notes", old + (old.isEmpty() ? "" : "\n") + m.group(1)).apply();
                return "I will remember that.";
            }
            if (has(t, "what did i ask you to remember", "read my notes", "my notes", "what do you remember")) {
                String n = act.getSharedPreferences("ultron_notes", Context.MODE_PRIVATE).getString("notes", "");
                return n.isEmpty() ? "You have no notes yet." : "Your notes are: " + n.replace("\n", ". ");
            }
            if (has(t, "clear my notes", "delete my notes", "forget my notes")) {
                act.getSharedPreferences("ultron_notes", Context.MODE_PRIVATE).edit().clear().apply();
                return "Notes cleared.";
            }

            // ---- calculator: "calculate 12 times 5"
            m = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(plus|minus|times|multiplied by|divided by|into|x|\\+|\\-|\\*|/)\\s*(-?\\d+(?:\\.\\d+)?)").matcher(t);
            if (m.find() && has(t, "calculate", "what is", "what's", "how much", "compute", "कितना", "जोड़", "गुणा")) {
                double a = Double.parseDouble(m.group(1)), b = Double.parseDouble(m.group(3)), r;
                String op = m.group(2);
                if (op.equals("plus") || op.equals("+")) r = a + b;
                else if (op.equals("minus") || op.equals("-")) r = a - b;
                else if (op.equals("divided by") || op.equals("/")) { if (b == 0) return "I cannot divide by zero."; r = a / b; }
                else r = a * b;
                String s = (r == Math.floor(r) && Math.abs(r) < 1e12) ? String.valueOf((long) r) : String.format(Locale.US, "%.4f", r).replaceAll("0+$", "");
                return "The answer is " + s + ".";
            }

            // ---- maps
            m = Pattern.compile("(?:navigate to|directions to|take me to|route to|show me)\\s+(.+)$").matcher(t);
            if (m.find() && !has(t, "show me how", "show me what")) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(m.group(1))));
                try { act.startActivity(i); } catch (Exception e) {
                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(m.group(1)))));
                }
                return "Navigating to " + m.group(1) + ".";
            }

            // ---- camera / settings
            if (has(t, "take a photo", "take a picture", "take a selfie", "click a photo")) {
                act.startActivity(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
                return "Opening the camera.";
            }
            if (has(t, "wifi settings", "wi-fi settings", "wifi setting")) { act.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); return "Opening Wi-Fi settings."; }
            if (has(t, "bluetooth settings")) { act.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return "Opening Bluetooth settings."; }
            if (t.equals("open settings") || t.equals("settings")) { act.startActivity(new Intent(Settings.ACTION_SETTINGS)); return "Opening settings."; }
            if (has(t, "weather")) {
                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(t))));
                return "Here is the weather.";
            }

            // ---- YouTube / web search
            m = Pattern.compile("(?:play|watch)\\s+(.+?)\\s+on youtube").matcher(t);
            if (m.find()) {
                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(m.group(1)))));
                return "Searching YouTube for " + m.group(1) + ".";
            }
            m = Pattern.compile("^(?:search|google|look up)(?: for)?\\s+(.+)$").matcher(t);
            if (m.find()) {
                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(m.group(1)))));
                return "Searching for " + m.group(1) + ".";
            }

            // ---- open an app
            m = Pattern.compile("^(?:open|launch|start)\\s+(.+)$").matcher(t);
            String app = null;
            if (m.find()) app = m.group(1);
            else {
                m = Pattern.compile("^(.+?)\\s+(?:खोलो|खोलिए|ओपन करो|चालू करो)$").matcher(t);
                if (m.find()) app = m.group(1);
            }
            if (app != null) return openApp(app.trim());
        } catch (Exception e) {
            return "Sorry, I could not do that.";
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static final String NEED_A11Y = "Please turn on Ultron in Accessibility settings first.";

    /** Back, home, recents, notifications, screenshot, lock, scroll, click a label, type text. Null = not such a command. */
    private String accessibilityCommand(String t) {
        boolean wants = has(t, "go back", "press back", "go home", "home screen", "recent apps", "open recents",
                "notification panel", "open notifications", "quick settings", "take a screenshot", "take screenshot",
                "lock the screen", "lock the phone", "lock phone", "scroll down", "scroll up");
        Matcher mc = Pattern.compile("^(?:click|tap|press)(?: on)?\\s+(.+)$").matcher(t);
        Matcher mt = Pattern.compile("^type\\s+(.+)$").matcher(t);
        boolean click = mc.find(), type = mt.find();
        if (!wants && !click && !type) return null;

        UltronAccessibilityService a = UltronAccessibilityService.inst;
        if (a == null) {
            try { act.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
            return NEED_A11Y;
        }
        if (has(t, "go back", "press back")) return a.back() ? "Going back." : "I could not go back.";
        if (has(t, "go home", "home screen")) return a.home() ? "Going home." : "I could not go home.";
        if (has(t, "recent apps", "open recents")) return a.recents() ? "Here are your recent apps." : "I could not open recents.";
        if (has(t, "notification panel", "open notifications")) return a.notifications() ? "Opening notifications." : "I could not open notifications.";
        if (has(t, "quick settings")) return a.quickSettings() ? "Opening quick settings." : "I could not open quick settings.";
        if (has(t, "take a screenshot", "take screenshot")) return a.screenshot() ? "Screenshot taken." : "I could not take a screenshot.";
        if (has(t, "lock the screen", "lock the phone", "lock phone")) return a.lockScreen() ? "Locking the screen." : "I could not lock the screen.";
        if (has(t, "scroll down")) return a.scroll(true) ? "Scrolling down." : "I found nothing to scroll.";
        if (has(t, "scroll up")) return a.scroll(false) ? "Scrolling up." : "I found nothing to scroll.";
        if (click) {
            String label = mc.group(1).trim();
            return a.click(label) ? "Pressed " + label + "." : "I could not find " + label + " on the screen.";
        }
        String text = mt.group(1);
        return a.typeNow(text) ? "Typed." : "I could not find a text box to type in.";
    }

    private void media(AudioManager am, int code) {
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    /** Digits stay digits; a name is looked up in contacts. Returns null if not found, "PERM" if permission is missing. */
    private String resolveNumber(String who) {
        String digits = who.replaceAll("[^0-9+]", "");
        if (digits.length() >= 5 && who.replaceAll("[0-9+\\s-]", "").isEmpty()) return digits;
        if (act.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
            return "PERM";
        }
        try (Cursor c = act.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + who + "%"}, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    private String openApp(String name) {
        PackageManager pm = act.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        String q = name.replace("the ", "").replace(" app", "").trim();
        for (ResolveInfo ri : apps) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            int score = 0;
            if (label.equals(q)) score = 3;
            else if (label.startsWith(q)) score = 2;
            else if (label.contains(q)) score = 1;
            if (score > bestScore) { bestScore = score; best = ri; }
        }
        if (best == null) return "I could not find an app called " + name + ".";
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) return "I could not open " + name + ".";
        act.startActivity(launch);
        return "Opening " + best.loadLabel(pm) + ".";
    }

    private String setTorch(boolean on) {
        try {
            CameraManager cm = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (flash != null && flash && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cm.setTorchMode(id, on);
                    return on ? "Flashlight on." : "Flashlight off.";
                }
            }
        } catch (Exception ignored) { }
        return "I could not reach the flashlight.";
    }
}
