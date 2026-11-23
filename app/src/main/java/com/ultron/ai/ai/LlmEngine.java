package com.ultron.ai.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs llama.cpp's llama-server (shipped as libllama_server.so) on 127.0.0.1 and talks to it over HTTP.
 * The model never leaves the phone.
 */
public class LlmEngine {
    public interface TokenListener { void onToken(String piece); }

    private static final int PORT = 18080;
    private static final String BASE = "http://127.0.0.1:" + PORT;

    private final Context ctx;
    private Process proc;
    private final ArrayDeque<String> log = new ArrayDeque<>();

    public LlmEngine(Context c) { ctx = c.getApplicationContext(); }

    private File modelFile;
    private int attempt = 0;
    private Thread logThread;
    // Launch plan: fast CPU build first, then the safe baseline build, then minimal options.
    private static final String[][] PLAN = {
        {"libllama_server_dot.so", "full"},
        {"libllama_server.so", "full"},
        {"libllama_server.so", "min"},
    };

    public synchronized void start(File model) throws IOException {
        modelFile = model;
        attempt = 0;
        if (healthCode() == 200) return; // an engine from a previous run is still alive
        launch();
    }

    /** Starts the next engine variant in the plan (skips variants that are not in this APK). */
    private void launch() throws IOException {
        File dir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        while (attempt < PLAN.length && !new File(dir, PLAN[attempt][0]).exists()) attempt++;
        if (attempt >= PLAN.length) throw new IOException("Engine file is missing in this APK");
        File exe = new File(dir, PLAN[attempt][0]);
        //noinspection ResultOfMethodCallIgnored
        exe.setExecutable(true);
        boolean full = PLAN[attempt][1].equals("full");
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add(exe.getAbsolutePath());
        cmd.add("-m"); cmd.add(modelFile.getAbsolutePath());
        cmd.add("--host"); cmd.add("127.0.0.1");
        cmd.add("--port"); cmd.add(String.valueOf(PORT));
        if (full) {
            cmd.add("-c"); cmd.add("4096");
            cmd.add("-t"); cmd.add(String.valueOf(threads));
            cmd.add("-np"); cmd.add("1");
        }
        synchronized (log) {
            log.addLast("--- attempt " + (attempt + 1) + ": " + exe.getName() + " (" + PLAN[attempt][1] + ")");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(ctx.getFilesDir());
        proc = pb.start();
        final InputStream out = proc.getInputStream();
        logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(out, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (log) {
                        log.addLast(line);
                        while (log.size() > 60) log.removeFirst();
                    }
                }
            } catch (IOException ignored) { }
        }, "engine-log");
        logThread.start();
    }

    /** Waits until the model is loaded. Returns false on timeout or once every launch option has failed. */
    public boolean waitReady(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (healthCode() == 200) return true;
            if (proc != null && !isAlive(proc)) {
                joinLog();
                int code = -999;
                try { code = proc.exitValue(); } catch (Exception ignored) { }
                synchronized (log) { log.addLast("--- exited with code " + code + (code == 132 ? " (illegal CPU instruction)" : "")); }
                attempt++;
                try { launch(); } catch (IOException e) {
                    synchronized (log) { log.addLast("--- no more options: " + e.getMessage()); }
                    return false;
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    private void joinLog() {
        Thread t = logThread;
        if (t != null) {
            try { t.join(1500); } catch (InterruptedException ignored) { }
        }
    }

    private static boolean isAlive(Process p) {
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    private int healthCode() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/health").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code;
        } catch (IOException e) {
            return -1;
        }
    }

    public String recentLog() {
        StringBuilder sb = new StringBuilder();
        synchronized (log) {
            int skip = Math.max(0, log.size() - 14);
            int i = 0;
            for (String s : log) { if (i++ >= skip) sb.append(s).append('\n'); }
        }
        return sb.toString().trim();
    }

    /** Streams a completion for a ready-made ChatML prompt. Returns the full text. */
    public String generate(String prompt, TokenListener l, AtomicBoolean cancel) throws IOException {
        return generate(prompt, 320, l, cancel);
    }

    public String generate(String prompt, int nPredict, TokenListener l, AtomicBoolean cancel) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/completion").openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(120000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            JSONObject req = new JSONObject();
            req.put("prompt", prompt);
            req.put("n_predict", nPredict);
            req.put("temperature", 0.7);
            req.put("top_k", 20);
            req.put("top_p", 0.8);
            req.put("min_p", 0.0);
            req.put("presence_penalty", 1.0);
            req.put("stream", true);
            req.put("cache_prompt", true);
            req.put("stop", new JSONArray().put("<|im_end|>").put("<|im_start|>"));
            try (OutputStream os = c.getOutputStream()) {
                os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new IOException("Engine answered HTTP " + c.getResponseCode());
            StringBuilder all = new StringBuilder();
            boolean inThink = false;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (cancel.get()) break;
                    if (!line.startsWith("data:")) continue;
                    String json = line.substring(5).trim();
                    if (json.isEmpty()) continue;
                    JSONObject o = new JSONObject(json);
                    String piece = o.optString("content", "");
                    if (piece.contains("<think>")) inThink = true;
                    if (!inThink && !piece.isEmpty()) {
                        all.append(piece);
                        l.onToken(piece);
                    }
                    if (piece.contains("</think>")) inThink = false;
                    if (o.optBoolean("stop", false)) break;
                }
            } catch (org.json.JSONException e) {
                throw new IOException("Bad engine reply", e);
            }
            return all.toString();
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        } finally {
            c.disconnect(); // closing the connection also stops generation in the server
        }
    }

    public void stop() {
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
    }
}
