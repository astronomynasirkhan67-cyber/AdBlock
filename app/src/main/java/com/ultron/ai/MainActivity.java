package com.ultron.ai;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ultron.ai.ai.BrainDownloader;
import com.ultron.ai.ai.LlmEngine;
import com.ultron.ai.skills.DocIntent;
import com.ultron.ai.skills.DocxWriter;
import com.ultron.ai.skills.Skills;
import com.ultron.ai.ui.OrbView;
import com.ultron.ai.ui.OverlayOrb;
import com.ultron.ai.voice.SpeechInput;
import com.ultron.ai.voice.VoiceModels;
import com.ultron.ai.voice.VoiceManager;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** ULTRON - voice only. Tap the core to talk; Ultron answers out loud. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int RED = Color.rgb(232, 40, 58);
    private static final int WHITE = Color.rgb(245, 246, 250);
    private static final int MUTED = Color.rgb(150, 158, 175);
    private static final int REQ_MIC = 77;

    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private OrbView orb;
    private TextView status, caption, langBtn;
    private LinearLayout setup;
    private TextView setupTitle, setupText, setupPct;
    private ProgressBar setupBar;
    private Button setupBtn, skipBtn;
    private Runnable voiceNext;
    private final AtomicBoolean voiceCancel = new AtomicBoolean(false);

    private VoiceManager voice;
    private SpeechInput stt;
    private LlmEngine engine;
    private Skills skills;
    private OverlayOrb overlay;
    private BrainDownloader downloader;
    private SharedPreferences prefs;

    private boolean engineReady = false;
    private boolean convoActive = false;
    private volatile boolean generating = false;
    private int silentRounds = 0;
    private int clientErrors = 0;
    private AtomicBoolean cancelGen = new AtomicBoolean(false);
    private final ArrayList<String[]> history = new ArrayList<>(); // {role, text}
    private String lang = "en";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("ultron", MODE_PRIVATE);
        lang = prefs.getString("lang", "en");

        buildUi();
        overlay = new OverlayOrb(this, this::onOrbTap);

        engine = new LlmEngine(this);
        skills = new Skills(this);
        downloader = new BrainDownloader();
        voice = new VoiceManager(this, new VoiceManager.Listener() {
            @Override public void onSpeakStart() { setMode(OrbView.State.SPEAKING, "SPEAKING"); }
            @Override public void onSpeakIdle() { afterSpeech(); }
            @Override public void onNoMaleVoice(String langCode) {
                // Ultron still sounds male (deep-voice mode); a real male voice sounds even better.
                if (!prefs.getBoolean("maleHint_" + langCode, false)) {
                    prefs.edit().putBoolean("maleHint_" + langCode, true).apply();
                    caption.setText("Tip: for the best male voice, open Settings > Text-to-speech > Google > Install voice data, and download a male voice.");
                }
            }
        });
        voice.setLocale(localeFor(lang));
        stt = new SpeechInput(this, new SpeechInput.Callback() {
            @Override public void onLevel(float l) { orb.setMicLevel(l); overlay.setMicLevel(l); }
            @Override public void onPartial(String t) { caption.setText(t); }
            @Override public void onResult(String t) { onHeard(t); }
            @Override public void onError(int code) { onSttError(code); }
        });

        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_MIC);

        if (BrainDownloader.isInstalled(this)) ensureVoiceModel(this::bootEngine);
        else showSetup(false, null);
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private TextView tv(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private Button btn(String s) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setTextColor(Color.WHITE);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        x.setLetterSpacing(0.15f);
        x.setBackgroundTintList(android.content.res.ColorStateList.valueOf(RED));
        return x;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                top = i.top; bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        orb = new OrbView(this);
        root.addView(orb, new FrameLayout.LayoutParams(-1, -1));
        orb.setOnClickListener(v -> onOrbTap());

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView brand = tv("ULTRON", 22, RED);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setLetterSpacing(0.45f);
        status = tv("", 11, MUTED);
        status.setLetterSpacing(0.3f);
        status.setPadding(0, dp(6), 0, 0);
        head.addView(brand);
        head.addView(status);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        hp.topMargin = dp(18);
        root.addView(head, hp);

        // language switch (top right)
        langBtn = tv(lang.equals("hi") ? "HI" : "EN", 12, WHITE);
        langBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        langBtn.setLetterSpacing(0.2f);
        langBtn.setBackgroundColor(Color.argb(60, 232, 40, 58));
        langBtn.setOnClickListener(v -> toggleLang());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.topMargin = dp(18);
        lp.rightMargin = dp(16);
        root.addView(langBtn, lp);

        // caption (what the mic heard) near the bottom
        caption = tv("", 14, MUTED);
        caption.setPadding(dp(28), 0, dp(28), dp(56));
        caption.setMaxLines(3);
        root.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // brain setup panel (shown only until the brain is installed)
        setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);
        setup.setGravity(Gravity.CENTER_HORIZONTAL);
        setup.setPadding(dp(26), dp(22), dp(26), dp(22));
        setup.setBackgroundColor(Color.argb(235, 12, 15, 22));
        setupTitle = tv("INSTALL ULTRON BRAIN", 16, WHITE);
        setupTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setupTitle.setLetterSpacing(0.15f);
        setupText = tv("", 13, MUTED);
        setupText.setPadding(0, dp(12), 0, dp(16));
        setupBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        setupBar.setMax(1000);
        setupBar.setVisibility(View.GONE);
        setupPct = tv("", 12, MUTED);
        setupPct.setPadding(0, dp(6), 0, dp(10));
        setupBtn = btn("INSTALL BRAIN");
        setup.addView(setupTitle);
        setup.addView(setupText);
        setup.addView(setupBar, new LinearLayout.LayoutParams(-1, -2));
        setup.addView(setupPct);
        setup.addView(setupBtn, new LinearLayout.LayoutParams(-1, dp(50)));
        skipBtn = btn("USE ONLINE VOICE INSTEAD");
        skipBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(60, 66, 80)));
        skipBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams skp = new LinearLayout.LayoutParams(-1, dp(46));
        skp.topMargin = dp(10);
        setup.addView(skipBtn, skp);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        sp.leftMargin = dp(20);
        sp.rightMargin = dp(20);
        setup.setVisibility(View.GONE);
        root.addView(setup, sp);

        setMode(OrbView.State.OFFLINE, "OFFLINE");
    }

    private void setMode(OrbView.State s, String text) {
        orb.setState(s);
        if (overlay != null) overlay.setState(s);
        status.setText(text);
    }

    // ------------------------------------------------------------ brain setup

    private void showSetup(boolean failed, String error) {
        setupText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        setupText.setGravity(Gravity.CENTER);
        setMode(OrbView.State.OFFLINE, "BRAIN NOT INSTALLED");
        skipBtn.setVisibility(View.GONE);
        setup.setVisibility(View.VISIBLE);
        setupBar.setVisibility(View.GONE);
        setupPct.setText("");
        setupBtn.setEnabled(true);
        if (failed) {
            setupTitle.setText("DOWNLOAD STOPPED");
            setupText.setText((error == null ? "" : error + "\n\n") + "Your progress is saved. Tap to resume.");
            setupBtn.setText("RESUME");
        } else {
            setupTitle.setText("INSTALL ULTRON BRAIN");
            setupText.setText("Ultron needs its brain before it can talk.\n\n"
                    + "One-time download: about 0.8 GB (Qwen3.5 0.8B). Use Wi-Fi if you can. "
                    + "After this, Ultron thinks completely offline on your phone.");
            setupBtn.setText("INSTALL BRAIN");
        }
        setupBtn.setOnClickListener(v -> startDownload());
    }

    private void startDownload() {
        setupText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        setupText.setGravity(Gravity.CENTER);
        setupBtn.setEnabled(false);
        setupBtn.setText("DOWNLOADING...");
        setupTitle.setText("DOWNLOADING BRAIN");
        setupText.setText("Keep this screen open. If it stops, just tap resume.");
        setupBar.setVisibility(View.VISIBLE);
        setupBar.setProgress(0);
        setMode(OrbView.State.THINKING, "DOWNLOADING BRAIN");
        downloader.start(this, new BrainDownloader.Listener() {
            @Override public void onProgress(long done, long total) {
                main.post(() -> {
                    if (total > 0) setupBar.setProgress((int) (done * 1000L / total));
                    setupPct.setText(String.format(Locale.US, "%d MB / %d MB",
                            done / 1048576, Math.max(total, done) / 1048576));
                });
            }
            @Override public void onDone(File model) {
                main.post(() -> {
                    setup.setVisibility(View.GONE);
                    ensureVoiceModel(MainActivity.this::bootEngine);
                });
            }
            @Override public void onError(String message) { main.post(() -> showSetup(true, message)); }
        });
    }

    // ------------------------------------------------------------ offline voice setup

    /** Makes sure the small offline speech model for the current language is installed, then runs next. */
    private void ensureVoiceModel(Runnable next) {
        if (VoiceModels.isInstalled(this, lang)) {
            next.run();
            return;
        }
        voiceNext = next;
        showVoiceSetup(null);
    }

    private void showVoiceSetup(String error) {
        setupText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        setupText.setGravity(Gravity.CENTER);
        setMode(OrbView.State.OFFLINE, "OFFLINE VOICE NOT INSTALLED");
        setup.setVisibility(View.VISIBLE);
        setupBar.setVisibility(View.GONE);
        setupPct.setText("");
        setupBtn.setEnabled(true);
        String name = lang.equals("hi") ? "Hindi" : "English";
        if (error != null) {
            setupTitle.setText("VOICE DOWNLOAD STOPPED");
            setupText.setText(error + "\n\nCheck your internet and try again, or use the phone's online voice recognition.");
            setupBtn.setText("RETRY");
            skipBtn.setVisibility(View.VISIBLE);
        } else {
            setupTitle.setText("INSTALL OFFLINE VOICE");
            setupText.setText("So Ultron can hear you without internet, it needs a small " + name
                    + " listening model.\n\nOne-time download: about 40 MB.");
            setupBtn.setText("INSTALL VOICE");
            skipBtn.setVisibility(View.GONE);
        }
        setupBtn.setOnClickListener(v -> startVoiceDownload());
        skipBtn.setOnClickListener(v -> {
            setup.setVisibility(View.GONE);
            skipBtn.setVisibility(View.GONE);
            if (voiceNext != null) voiceNext.run();
        });
    }

    private void startVoiceDownload() {
        voiceCancel.set(false);
        skipBtn.setVisibility(View.GONE);
        setupBtn.setEnabled(false);
        setupBtn.setText("DOWNLOADING...");
        setupTitle.setText("DOWNLOADING VOICE");
        setupText.setText("Keep this screen open.");
        setupBar.setVisibility(View.VISIBLE);
        setupBar.setProgress(0);
        setMode(OrbView.State.THINKING, "DOWNLOADING VOICE");
        final String l = lang;
        new Thread(() -> {
            try {
                VoiceModels.install(this, l, (done, total) -> main.post(() -> {
                    if (total > 0) setupBar.setProgress((int) (done * 1000L / total));
                    setupPct.setText(String.format(Locale.US, "%d MB / %d MB",
                            done / 1048576, Math.max(total, done) / 1048576));
                }), voiceCancel);
                main.post(() -> {
                    setup.setVisibility(View.GONE);
                    if (voiceNext != null) voiceNext.run();
                });
            } catch (Exception e) {
                final String msg = String.valueOf(e.getMessage());
                main.post(() -> showVoiceSetup(msg));
            }
        }, "voice-model").start();
    }

    // ----------------------------------------------------------------- engine

    private void bootEngine() {
        setup.setVisibility(View.GONE);
        setMode(OrbView.State.THINKING, "WAKING UP");
        new Thread(() -> {
            String err = null;
            try {
                engine.start(BrainDownloader.modelFile(this));
                if (!engine.waitReady(120000)) {
                    err = "The brain did not start.\n\n" + engine.recentLog();
                }
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final String fe = err;
            main.post(() -> {
                if (fe == null) onEngineReady();
                else showEngineError(fe);
            });
        }, "engine-boot").start();
    }

    private void showEngineError(String msg) {
        setMode(OrbView.State.OFFLINE, "BRAIN ERROR");
        setup.setVisibility(View.VISIBLE);
        setupBar.setVisibility(View.GONE);
        setupPct.setText("");
        setupTitle.setText("BRAIN FAILED TO START");
        setupText.setText(msg);
        setupText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        setupText.setGravity(Gravity.START);
        setupBtn.setEnabled(true);
        setupBtn.setText("TRY AGAIN");
        setupBtn.setOnClickListener(v -> bootEngine());
    }

    private void onEngineReady() {
        engineReady = true;
        convoActive = true;
        String hello = lang.equals("hi")
                ? "अल्ट्रॉन ऑनलाइन है। मैं आपकी क्या मदद कर सकता हूँ?"
                : "Ultron online. How can I help you?";
        setMode(OrbView.State.SPEAKING, "ONLINE");
        voice.speak(hello);
        if (!voice.isReady()) startListening();
        startKeepAlive();
        askAccessOnce();
    }

    /** Lets Ultron keep running (and keep the microphone) while you use other apps. */
    private void startKeepAlive() {
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startForegroundService(new Intent(this, KeepAliveService.class));
            }
        } catch (Exception ignored) { }
    }

    /** One time: ask to turn on the Accessibility service (lets Ultron act in other apps and stay on top). */
    private void askAccessOnce() {
        if (UltronAccessibilityService.inst != null) return;
        if (prefs.getBoolean("askedA11y", false)) {
            askOverlayOnce();
            return;
        }
        prefs.edit().putBoolean("askedA11y", true).apply();
        caption.setText("Turn on ULTRON in Accessibility settings. If it is greyed out: App info > menu > Allow restricted settings.");
        voice.speak("To control your phone and stay with you over other apps, please turn on Ultron in accessibility settings.");
        main.postDelayed(() -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
        }, 5000);
    }

    /** One time: ask for "display over other apps" so the small floating core can follow you. */
    private void askOverlayOnce() {
        if (OverlayOrb.canShow(this) || prefs.getBoolean("askedOverlay", false)) return;
        prefs.edit().putBoolean("askedOverlay", true).apply();
        voice.speak("To stay with you over other apps, please allow Ultron to display over other apps.");
        main.postDelayed(() -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        }, 3500);
    }

    // ------------------------------------------------------------ conversation

    private void onOrbTap() {
        if (!engineReady) return;
        OrbView.State s = orb.getState();
        if (s == OrbView.State.SPEAKING || s == OrbView.State.THINKING) {
            interrupt();
            convoActive = true;
            startListening();
        } else if (s == OrbView.State.LISTENING) {
            convoActive = false;
            stt.cancel();
            setMode(OrbView.State.IDLE, "PAUSED  -  TAP THE CORE");
        } else {
            convoActive = true;
            silentRounds = 0;
            startListening();
        }
    }

    private void interrupt() {
        cancelGen.set(true);
        voice.stop();
    }

    private void startListening() {
        if (!engineReady) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            setMode(OrbView.State.IDLE, "MICROPHONE PERMISSION NEEDED");
            return;
        }
        if (!stt.isAvailable()) {
            setMode(OrbView.State.IDLE, "NO SPEECH RECOGNITION ON THIS PHONE");
            convoActive = false;
            return;
        }
        caption.setText("");
        setMode(OrbView.State.LISTENING, "LISTENING");
        try {
            stt.start(lang.equals("hi") ? "hi-IN" : "en-US");
        } catch (Exception e) {
            main.postDelayed(this::startListening, 700);
        }
    }

    private void onSttError(int code) {
        if (!convoActive || orb.getState() != OrbView.State.LISTENING) return;
        switch (code) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                silentRounds++;
                if (silentRounds >= 5) {
                    convoActive = false;
                    setMode(OrbView.State.IDLE, "PAUSED  -  TAP THE CORE");
                } else {
                    main.postDelayed(this::startListening, 300);
                }
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case SpeechRecognizer.ERROR_CLIENT:
                if (++clientErrors > 3) {
                    clientErrors = 0;
                    convoActive = false;
                    setMode(OrbView.State.IDLE, "VOICE ENGINE ERROR  -  TAP THE CORE");
                    break;
                }
                stt.destroy();
                main.postDelayed(this::startListening, 700);
                break;
            case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
            case 13: // ERROR_LANGUAGE_UNAVAILABLE (no offline language pack installed)
                if (stt.preferOffline) {
                    stt.preferOffline = false; // use the online recogniser instead
                    stt.destroy();
                    main.postDelayed(this::startListening, 400);
                } else {
                    convoActive = false;
                    setMode(OrbView.State.IDLE, "VOICE INPUT: LANGUAGE NOT AVAILABLE  -  CHECK INTERNET");
                }
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                convoActive = false;
                setMode(OrbView.State.IDLE, "MICROPHONE PERMISSION NEEDED");
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                convoActive = false;
                setMode(OrbView.State.IDLE, "VOICE INPUT NEEDS INTERNET OR OFFLINE LANGUAGE PACK");
                break;
            default:
                convoActive = false;
                setMode(OrbView.State.IDLE, "MIC ERROR " + code + "  -  TAP THE CORE");
        }
    }

    private void onHeard(String text) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) {
            onSttError(SpeechRecognizer.ERROR_NO_MATCH);
            return;
        }
        silentRounds = 0;
        clientErrors = 0;
        caption.setText(text);
        String lt = text.toLowerCase(Locale.ROOT);
        if (lt.contains("stop listening") || lt.contains("go to sleep") || lt.contains("goodbye") || lt.contains("good bye")) {
            convoActive = false;
            setMode(OrbView.State.SPEAKING, "SPEAKING");
            voice.speak("Standing by. Tap the core when you need me.");
            return;
        }
        String topic = DocIntent.topic(text);
        if (topic != null) {
            makeDocument(topic);
            return;
        }
        String quick = skills.handle(text);
        if (quick != null) {
            setMode(OrbView.State.SPEAKING, "SPEAKING");
            voice.speak(quick);
            return;
        }
        setMode(OrbView.State.THINKING, "THINKING");
        think(text);
    }

    private String systemPrompt() {
        String s = "You are ULTRON, a calm, precise and slightly formal voice assistant living on the user's phone. "
                + "You are being spoken aloud, so answer in one to three short plain sentences. "
                + "Never use markdown, lists, symbols or emojis. ";
        s += lang.equals("hi")
                ? "Reply in Hindi (Devanagari script) unless the user speaks English."
                : "Reply in the same language the user speaks.";
        return s;
    }

    private String buildPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n").append(systemPrompt()).append("<|im_end|>\n");
        int from = Math.max(0, history.size() - 8);
        for (int i = from; i < history.size(); i++) {
            sb.append("<|im_start|>").append(history.get(i)[0]).append('\n')
              .append(history.get(i)[1]).append("<|im_end|>\n");
        }
        sb.append("<|im_start|>assistant\n<think>\n\n</think>\n\n");
        return sb.toString();
    }

    private void think(String userText) {
        history.add(new String[]{"user", userText});
        final String prompt = buildPrompt();
        cancelGen = new AtomicBoolean(false);
        final AtomicBoolean cancel = cancelGen;
        generating = true;
        new Thread(() -> {
            final StringBuilder sentence = new StringBuilder();
            final StringBuilder full = new StringBuilder();
            String error = null;
            try {
                engine.generate(prompt, piece -> {
                    sentence.append(piece);
                    full.append(piece);
                    int cut = lastSentenceEnd(sentence);
                    if (cut > 0) {
                        String ready = sentence.substring(0, cut);
                        sentence.delete(0, cut);
                        sayFromThread(ready, cancel);
                    }
                }, cancel);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            if (!cancel.get() && sentence.length() > 0) sayFromThread(sentence.toString(), cancel);
            final String fe = error;
            final String answer = full.toString().trim();
            main.post(() -> {
                if (cancel != cancelGen) return; // a newer turn took over
                generating = false;
                if (!cancel.get()) {
                    if (fe != null) {
                        setMode(OrbView.State.IDLE, "BRAIN ERROR  -  TAP THE CORE");
                        caption.setText(fe);
                        convoActive = false;
                        return;
                    }
                    if (answer.isEmpty()) voice.speak(lang.equals("hi") ? "माफ़ कीजिए, मैं समझ नहीं पाया।" : "Sorry, I didn't catch that.");
                    else history.add(new String[]{"assistant", answer});
                }
                if (!voice.isSpeaking()) afterSpeech();
            });
        }, "ultron-think").start();
    }

    private void sayFromThread(String text, AtomicBoolean cancel) {
        final String clean = cleanForSpeech(text);
        if (clean.isEmpty()) return;
        main.post(() -> {
            if (!cancel.get()) voice.speak(clean);
        });
    }

    private static int lastSentenceEnd(CharSequence s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '\u0964' || c == '\u061F') {
                if (i + 1 >= 12) return i + 1;
            }
        }
        return 0;
    }

    private static String cleanForSpeech(String s) {
        return s.replaceAll("<[^>]*>", " ")
                .replaceAll("[*#_`~>|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ------------------------------------------------------------ Word documents

    private String docPrompt(String topic) {
        return "<|im_start|>system\nYou write clear, accurate study documents in plain text. "
                + "Write in the same language as the topic. Never use markdown symbols.<|im_end|>\n"
                + "<|im_start|>user\nWrite a document about: " + topic + ".\n"
                + "Format: first line is the title. Then 5 sections. Each section has a short heading line ending with a colon, "
                + "followed by one or two short paragraphs. About 400 words in total.<|im_end|>\n"
                + "<|im_start|>assistant\n<think>\n\n</think>\n\n";
    }

    private void makeDocument(final String topic) {
        cancelGen = new AtomicBoolean(false);
        final AtomicBoolean cancel = cancelGen;
        generating = true;
        setMode(OrbView.State.THINKING, "WRITING DOCUMENT");
        caption.setText(topic);
        voice.speak("Writing a document about " + topic + ". This takes about a minute.");
        new Thread(() -> {
            String text = "";
            String error = null;
            try {
                text = engine.generate(docPrompt(topic), 900, p -> { }, cancel).trim();
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final String fText = text, fErr = error;
            main.post(() -> {
                if (cancel != cancelGen) return;
                generating = false;
                if (cancel.get()) {
                    if (!voice.isSpeaking()) afterSpeech();
                    return;
                }
                if (fErr != null || fText.isEmpty()) {
                    voice.speak("Sorry, I could not write the document.");
                    return;
                }
                try {
                    String name = "Ultron - " + topic.replaceAll("[^\\p{L}\\p{N} ]", "").trim() + ".docx";
                    byte[] data = DocxWriter.build(topic, fText);
                    Uri uri = saveDocx(name, data);
                    voice.speak("Your document is ready. Opening it in Word.");
                    openDocx(uri);
                } catch (Exception e) {
                    voice.speak("I wrote the document but could not open it. " + e.getMessage());
                }
            });
        }, "ultron-doc").start();
    }

    private Uri saveDocx(String name, byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("Saving documents needs Android 10 or newer.");
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, DocxWriter.MIME);
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ultron");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("Could not create the file.");
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("Could not write the file.");
            os.write(data);
        }
        return uri;
    }

    private void openDocx(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, DocxWriter.MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (getPackageManager().getLaunchIntentForPackage("com.microsoft.office.word") != null) {
                i.setPackage("com.microsoft.office.word");
            }
            startActivity(i);
        } catch (Exception e) {
            i.setPackage(null);
            try { startActivity(i); } catch (Exception e2) {
                voice.speak("I saved it in your Downloads folder, in the Ultron folder.");
            }
        }
    }

    /** Called when the voice queue empties (or generation ends with nothing left to say). */
    private void afterSpeech() {
        if (generating) {
            setMode(OrbView.State.THINKING, "THINKING");
            return;
        }
        if (convoActive && engineReady) {
            startListening();
        } else if (engineReady) {
            setMode(OrbView.State.IDLE, "PAUSED  -  TAP THE CORE");
        }
    }

    private void toggleLang() {
        lang = lang.equals("en") ? "hi" : "en";
        prefs.edit().putString("lang", lang).apply();
        langBtn.setText(lang.equals("hi") ? "HI" : "EN");
        voice.setLocale(localeFor(lang));
        history.clear();
        if (engineReady && !VoiceModels.isInstalled(this, lang)) {
            convoActive = false;
            stt.cancel();
            ensureVoiceModel(() -> { convoActive = true; startListening(); });
        } else if (engineReady && orb.getState() == OrbView.State.LISTENING) {
            stt.cancel();
            main.postDelayed(this::startListening, 300);
        }
    }

    private static Locale localeFor(String l) {
        return l.equals("hi") ? new Locale("hi", "IN") : Locale.US;
    }

    // -------------------------------------------------------------- lifecycle

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED
                && engineReady && convoActive && !voice.isSpeaking() && !generating) {
            startListening();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        overlay.hide(); // the full screen is back, so the floating core goes away
    }

    @Override protected void onStop() {
        super.onStop();
        if (!engineReady) return;
        if (OverlayOrb.canShow(this)) {
            overlay.setState(orb.getState());
            overlay.show(); // stay with the user on top of other apps and keep talking
        } else {
            convoActive = false;
            stt.cancel();
            interrupt();
            setMode(OrbView.State.IDLE, "PAUSED  -  TAP THE CORE");
        }
    }

    @Override protected void onDestroy() {
        if (downloader != null) downloader.cancel();
        if (overlay != null) overlay.hide();
        stopService(new Intent(this, KeepAliveService.class));
        if (voice != null) voice.shutdown();
        if (stt != null) stt.destroy();
        if (engine != null) engine.stop();
        super.onDestroy();
    }
}
