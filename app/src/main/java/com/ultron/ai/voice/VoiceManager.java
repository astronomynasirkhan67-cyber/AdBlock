package com.ultron.ai.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Set;

/**
 * Ultron's voice. Uses Google's speech engine (its male voices are natural and work offline once the
 * voice data is installed). Sentences are queued so speech can start while the brain is still thinking.
 */
public class VoiceManager extends UtteranceProgressListener implements TextToSpeech.OnInitListener {
    public interface Listener {
        void onSpeakStart();
        void onSpeakIdle();
        /** The phone has no offline male voice for the current language. */
        default void onNoMaleVoice(String langCode) { }
    }

    private static final String GOOGLE_TTS = "com.google.android.tts";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Context app;
    private TextToSpeech tts;
    private boolean triedGoogle = false;
    private volatile boolean ready = false;
    private Locale pending = Locale.US;
    private boolean maleFound = false;
    private int gen = 0;
    private int counter = 0;
    private int queued = 0;

    // ---- deep-voice mode: used when the phone has no offline male voice.
    // The sentence is synthesised to a file at a faster rate, then played back at a lower sample rate.
    // That lowers pitch AND formants together, which sounds like a deeper male voice (much better than
    // the engine's own pitch control).
    private static final float SHIFT = 0.80f;
    private boolean useShift = false;
    private final LinkedBlockingQueue<Object[]> playQ = new LinkedBlockingQueue<>();
    private Thread player;
    private volatile AudioTrack track;

    public VoiceManager(Context c, Listener l) {
        listener = l;
        app = c.getApplicationContext();
        triedGoogle = true;
        tts = new TextToSpeech(app, this, GOOGLE_TTS);
    }

    @Override public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            if (triedGoogle) {            // Google engine missing: use the phone's default engine
                triedGoogle = false;
                try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { }
                tts = new TextToSpeech(app, this);
            }
            return;
        }
        tts.setOnUtteranceProgressListener(this);
        tts.setSpeechRate(0.95f);
        ready = true;
        applyLocale();
    }

    public synchronized void setLocale(Locale l) {
        pending = l;
        if (ready) applyLocale();
    }

    private void applyLocale() {
        int r = tts.setLanguage(pending);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US);
        }
        pickMaleVoice();
    }

    // Voice-name tokens of known male voices in Google's speech engine.
    private static final String[] MALE_TOKENS = {"-iol-", "-iom-", "-tpd-", "-gbb-", "-gbd-", "-rjs-", "-end-", "-ene-", "-hid-", "-hie-"};

    /** Picks an OFFLINE male voice. If there is none, the engine's default voice is used at a mild pitch. */
    private void pickMaleVoice() {
        maleFound = false;
        String lang = "en";
        try {
            Locale cur = tts.getLanguage();
            if (cur != null) lang = cur.getLanguage();
            Set<Voice> voices = tts.getVoices();
            Voice best = null;
            int bestScore = 0;
            if (voices != null) {
                for (Voice v : voices) {
                    if (!v.getLocale().getLanguage().equals(lang)) continue;
                    if (v.isNetworkConnectionRequired()) continue;        // must work offline
                    String n = v.getName().toLowerCase(Locale.ROOT);
                    if (n.contains("female")) continue;
                    int score = 0;
                    for (String t : MALE_TOKENS) if (n.contains(t)) score += 5;
                    if (n.contains("male")) score += 5;
                    if (score > bestScore) { bestScore = score; best = v; }
                }
            }
            if (best != null) {
                tts.setVoice(best);
                tts.setPitch(0.95f);   // the voice is already male: keep it natural
                tts.setSpeechRate(0.95f);
                maleFound = true;
                useShift = false;
                return;
            }
        } catch (Exception ignored) { }
        tts.setPitch(1.0f);           // do not use the engine pitch (it sounds bad); we shift while playing instead
        tts.setSpeechRate(0.95f / SHIFT);
        useShift = true;
        final String fl = lang;
        main.post(() -> listener.onNoMaleVoice(fl));
    }

    public boolean hasMaleVoice() { return maleFound; }

    public boolean isReady() { return ready; }

    public synchronized boolean isSpeaking() { return queued > 0; }

    public synchronized void speak(String text) {
        if (!ready || text == null || text.trim().isEmpty()) return;
        queued++;
        if (useShift) {
            String id = "s" + gen + "_" + (counter++);
            File f = new File(app.getCacheDir(), id + ".wav");
            int r = tts.synthesizeToFile(text, new Bundle(), f, id);
            if (r != TextToSpeech.SUCCESS) {
                queued--;
                if (queued == 0) main.post(listener::onSpeakIdle);
            }
            return;
        }
        String id = "u" + gen + "_" + (counter++);
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
    }

    public synchronized void stop() {
        gen++;
        queued = 0;
        playQ.clear();
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (Exception ignored) { }
        }
        if (tts != null) tts.stop();
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    private boolean current(String id) { return id != null && (id.startsWith("u" + gen + "_") || id.startsWith("s" + gen + "_")); }

    @Override public void onStart(String id) {
        boolean ok;
        synchronized (this) { ok = current(id); }
        if (ok) main.post(listener::onSpeakStart);
    }

    @Override public void onDone(String id) {
        if (id != null && id.startsWith("s")) { enqueuePlay(id); return; }
        finished(id);
    }
    @Override public void onError(String id) {
        if (id != null && id.startsWith("s")) {
            //noinspection ResultOfMethodCallIgnored
            new File(app.getCacheDir(), id + ".wav").delete();
        }
        finished(id);
    }

    private void enqueuePlay(String id) {
        boolean ok;
        synchronized (this) { ok = current(id); }
        File f = new File(app.getCacheDir(), id + ".wav");
        if (!ok) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        playQ.add(new Object[]{f, id});
        synchronized (this) {
            if (player == null) {
                player = new Thread(this::playLoop, "ultron-voice-player");
                player.setDaemon(true);
                player.start();
            }
        }
    }

    private void playLoop() {
        while (true) {
            Object[] job;
            try { job = playQ.take(); } catch (InterruptedException e) { return; }
            File f = (File) job[0];
            String id = (String) job[1];
            boolean ok;
            synchronized (this) { ok = current(id); }
            if (ok) {
                main.post(listener::onSpeakStart);
                try { playShifted(f, id); } catch (Exception ignored) { }
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            finished(id);
        }
    }

    private void playShifted(File f, String id) throws Exception {
        byte[] all;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            all = new byte[(int) raf.length()];
            raf.readFully(all);
        }
        int rate = 22050, channels = 1, dataStart = 44, dataLen = all.length - 44;
        int pos = 12;
        while (pos + 8 <= all.length) {
            String cid = new String(all, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = le32(all, pos + 4);
            if (cid.equals("fmt ")) {
                channels = le16(all, pos + 10);
                rate = le32(all, pos + 12);
            } else if (cid.equals("data")) {
                dataStart = pos + 8;
                dataLen = (size <= 0 || dataStart + size > all.length) ? all.length - dataStart : size;
                break;
            }
            pos += 8 + Math.max(0, size);
        }
        if (dataLen <= 0) return;
        int outRate = Math.max(8000, (int) (rate * SHIFT));
        int mask = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(outRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(outRate)
                        .setChannelMask(mask).build())
                .setBufferSizeInBytes(Math.max(min, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track = t;
        try {
            t.play();
            int off = dataStart, end = dataStart + dataLen;
            while (off < end) {
                boolean stillCurrent;
                synchronized (this) { stillCurrent = current(id); }
                if (!stillCurrent) return;
                int n = t.write(all, off, Math.min(4096, end - off));
                if (n <= 0) break;
                off += n;
            }
            int frames = dataLen / (2 * Math.max(1, channels));
            long waited = 0;
            while (t.getPlaybackHeadPosition() < frames && waited < 60000) {
                boolean stillCurrent;
                synchronized (this) { stillCurrent = current(id); }
                if (!stillCurrent) return;
                Thread.sleep(20);
                waited += 20;
            }
        } finally {
            track = null;
            try { t.stop(); } catch (Exception ignored) { }
            t.release();
        }
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }
    @Override public void onStop(String id, boolean interrupted) { }

    private void finished(String id) {
        boolean idle;
        synchronized (this) {
            if (!current(id)) return;
            if (queued > 0) queued--;
            idle = queued == 0;
        }
        if (idle) main.post(listener::onSpeakIdle);
    }
}
