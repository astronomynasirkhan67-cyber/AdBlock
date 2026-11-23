package com.ultron.ai.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;

import java.io.IOException;
import java.util.Random;

/** Offline microphone -> text with Vosk. Delivers exactly one result (or an error) per start(). */
public class VoskStt {
    private final Context ctx;
    private final SpeechInput.Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();

    private Model model;
    private String modelLang;
    private SpeechService svc;
    private int token = 0;        // invalidates callbacks of an older listening session
    private boolean active = false;
    private float level = 0f;

    public VoskStt(Context c, SpeechInput.Callback cb) {
        ctx = c;
        this.cb = cb;
    }

    public void start(final String lang) {
        stopService();
        final int my = ++token;
        active = true;
        if (model != null && lang.equals(modelLang)) {
            begin(my);
            return;
        }
        new Thread(() -> {
            Model m = null;
            try {
                m = new Model(VoiceModels.dir(ctx, lang).getAbsolutePath());
            } catch (Throwable e) {
                // fall through: m stays null
            }
            final Model fm = m;
            main.post(() -> {
                if (my != token) { if (fm != null) fm.close(); return; }
                if (fm == null) { active = false; cb.onError(5); return; }
                if (model != null) model.close();
                model = fm;
                modelLang = lang;
                begin(my);
            });
        }, "vosk-load").start();
    }

    private void begin(final int my) {
        try {
            Recognizer r = new Recognizer(model, 16000.0f);
            svc = new SpeechService(r, 16000.0f);
            svc.startListening(new org.vosk.android.RecognitionListener() {
                @Override public void onPartialResult(String h) {
                    if (my != token || !active) return;
                    String t = text(h, "partial");
                    if (!t.isEmpty()) {
                        level = 0.5f + 0.5f * rnd.nextFloat();
                        cb.onPartial(t);
                    }
                }
                @Override public void onResult(String h) { done(my, text(h, "text")); }
                @Override public void onFinalResult(String h) { done(my, text(h, "text")); }
                @Override public void onError(Exception e) {
                    if (my != token || !active) return;
                    active = false;
                    stopService();
                    cb.onError(3);
                }
                @Override public void onTimeout() {
                    if (my != token || !active) return;
                    active = false;
                    stopService();
                    cb.onError(6); // speech timeout (nobody spoke)
                }
            }, 12000);
            level = 0.15f;
            tick(my);
        } catch (IOException e) {
            active = false;
            cb.onError(3);
        }
    }

    private void done(int my, String t) {
        if (my != token || !active || t.isEmpty()) return;
        active = false;
        stopService();
        cb.onResult(t);
    }

    /** Vosk gives no loudness, so the core pulses when words arrive and calms down in between. */
    private void tick(final int my) {
        if (my != token || !active) return;
        level = Math.max(0.12f, level * 0.78f);
        cb.onLevel(level);
        main.postDelayed(() -> tick(my), 90);
    }

    private static String text(String json, String key) {
        try { return new JSONObject(json).optString(key, "").trim(); } catch (Exception e) { return ""; }
    }

    private void stopService() {
        SpeechService s = svc;
        svc = null;
        if (s != null) {
            try { s.cancel(); } catch (Throwable ignored) { }
            try { s.shutdown(); } catch (Throwable ignored) { }
        }
    }

    public void cancel() {
        active = false;
        token++;
        stopService();
    }

    public void destroy() {
        cancel();
        if (model != null) { model.close(); model = null; }
    }
}
