package com.ultron.ai.ai;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads the Qwen3.5-0.8B (GGUF, Q8_0, about 0.8 GB) brain once, with resume support. */
public class BrainDownloader {
    public interface Listener {
        void onProgress(long done, long total);
        void onDone(File model);
        void onError(String message);
    }

    public static final String FILE_NAME = "Qwen3.5-0.8B-Q8_0.gguf";
    private static final String[] URLS = {
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q8_0.gguf",
        "https://huggingface.co/EnclaveHost/qwen3.5-0.8b-gguf/resolve/main/Qwen3.5-0.8B-Q8_0.gguf"
    };
    private static final long MIN_BYTES = 400L * 1024 * 1024;

    private volatile boolean cancelled = false;

    public static File modelFile(Context c) {
        return new File(new File(c.getFilesDir(), "brain"), FILE_NAME);
    }

    public static boolean isInstalled(Context c) {
        File f = modelFile(c);
        if (!f.isFile() || f.length() < MIN_BYTES) return false;
        try (InputStream in = new FileInputStream(f)) {
            byte[] m = new byte[4];
            return in.read(m) == 4 && m[0] == 'G' && m[1] == 'G' && m[2] == 'U' && m[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    public void cancel() { cancelled = true; }

    public void start(final Context ctx, final Listener l) {
        cancelled = false;
        new Thread(() -> {
            File target = modelFile(ctx);
            File dir = target.getParentFile();
            if (dir != null) dir.mkdirs();
            File part = new File(dir, FILE_NAME + ".part");
            String lastError = "Unknown error";
            for (String url : URLS) {
                if (cancelled) { l.onError("Cancelled"); return; }
                try {
                    download(url, part, l);
                    if (cancelled) { l.onError("Cancelled"); return; }
                    if (target.exists()) target.delete();
                    if (!part.renameTo(target)) throw new IOException("Could not save the file");
                    if (!isInstalled(ctx)) {
                        target.delete();
                        throw new IOException("Downloaded file is not a valid model");
                    }
                    l.onDone(target);
                    return;
                } catch (IOException e) {
                    lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            }
            l.onError(lastError);
        }, "brain-download").start();
    }

    private void download(String url, File part, Listener l) throws IOException {
        long have = part.exists() ? part.length() : 0;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "UltronAI/0.2");
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        int code = c.getResponseCode();
        long total;
        boolean append;
        if (code == 206) {
            total = have + c.getContentLengthLong();
            append = true;
        } else if (code == 200) {
            total = c.getContentLengthLong();
            have = 0;
            append = false;
        } else if (code == 416 && have >= MIN_BYTES) {
            l.onProgress(have, have);
            return; // already complete
        } else {
            c.disconnect();
            if (code == 416 && part.exists()) part.delete();
            throw new IOException("Server answered HTTP " + code);
        }
        long done = have;
        long lastReport = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) return;
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastReport > 250) {
                    lastReport = now;
                    l.onProgress(done, total);
                }
            }
        } finally {
            c.disconnect();
        }
        if (total > 0 && done != total) throw new IOException("Download interrupted - tap to resume");
        l.onProgress(done, done);
    }
}
