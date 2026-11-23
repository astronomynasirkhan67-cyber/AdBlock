package com.ultron.ai.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Microphone -> text. Uses the offline Vosk model when it is installed (no internet needed),
 * otherwise falls back to the phone's own recogniser.
 */
public class SpeechInput {
    public interface Callback {
        void onLevel(float level01);
        void onPartial(String text);
        void onResult(String text);
        void onError(int code);
    }

    private final Context ctx;
    private final Callback cb;
    private VoskStt vosk;
    private SpeechRecognizer rec;
    /** true = the system recogniser tries its offline pack first (only used when no Vosk model is installed). */
    public volatile boolean preferOffline = true;

    public SpeechInput(Context c, Callback cb) {
        this.ctx = c;
        this.cb = cb;
    }

    public boolean isAvailable() {
        return VoiceModels.isInstalled(ctx, "en") || VoiceModels.isInstalled(ctx, "hi")
                || SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    public void start(String langTag) {
        String lang = langTag.startsWith("hi") ? "hi" : "en";
        if (VoiceModels.isInstalled(ctx, lang)) {
            if (rec != null) rec.cancel();
            if (vosk == null) vosk = new VoskStt(ctx, cb);
            vosk.start(lang);
        } else {
            startSystem(langTag);
        }
    }

    public void cancel() {
        if (vosk != null) vosk.cancel();
        if (rec != null) rec.cancel();
    }

    public void destroy() {
        if (vosk != null) { vosk.destroy(); vosk = null; }
        if (rec != null) { rec.destroy(); rec = null; }
    }

    private void startSystem(String langTag) {
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(ctx);
            rec.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onEvent(int eventType, Bundle params) { }
                @Override public void onRmsChanged(float rmsdB) {
                    cb.onLevel(Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f)));
                }
                @Override public void onPartialResults(Bundle b) {
                    ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (l != null && !l.isEmpty()) cb.onPartial(l.get(0));
                }
                @Override public void onResults(Bundle b) {
                    ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    cb.onResult(l != null && !l.isEmpty() ? l.get(0) : "");
                }
                @Override public void onError(int error) { cb.onError(error); }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L);
        rec.startListening(i);
    }
}
