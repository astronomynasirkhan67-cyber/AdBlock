package com.ultron.ai.voice;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Small offline speech models (Vosk). Downloaded once, then speech recognition works with no internet. */
public class VoiceModels {
    public interface Progress { void onProgress(long done, long total); }

    public static final String[] LANGS = {"en", "hi"};

    public static String url(String lang) {
        return lang.equals("hi")
                ? "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"
                : "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    }

    public static File dir(Context c, String lang) {
        return new File(new File(c.getFilesDir(), "vosk"), lang);
    }

    public static boolean isInstalled(Context c, String lang) {
        File d = dir(c, lang);
        return new File(d, "am").isDirectory() && new File(d, "conf").isDirectory();
    }

    public static boolean allInstalled(Context c) {
        for (String l : LANGS) if (!isInstalled(c, l)) return false;
        return true;
    }

    /** Blocking. Call from a background thread. */
    public static void install(Context c, String lang, Progress p, AtomicBoolean cancel) throws IOException {
        File root = new File(c.getFilesDir(), "vosk");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        File zip = new File(root, lang + ".zip.part");
        HttpURLConnection con = (HttpURLConnection) new URL(url(lang)).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "UltronAI/0.3");
        if (con.getResponseCode() != 200) throw new IOException("Voice model server answered HTTP " + con.getResponseCode());
        long total = con.getContentLengthLong();
        long done = 0, last = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(zip)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get()) throw new IOException("Cancelled");
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - last > 250) { last = now; p.onProgress(done, total); }
            }
        } finally {
            con.disconnect();
        }
        p.onProgress(done, done);

        File tmp = new File(root, lang + ".tmp");
        deleteRec(tmp);
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        String tmpPath = tmp.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;                  // the zip has one top folder; strip it
                String rel = name.substring(slash + 1);
                if (rel.isEmpty()) continue;
                File f = new File(tmp, rel);
                if (!f.getCanonicalPath().startsWith(tmpPath)) continue; // zip-slip guard
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.mkdirs();
                } else {
                    File parent = f.getParentFile();
                    if (parent != null) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        int n;
                        while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        zip.delete();
        File finalDir = dir(c, lang);
        deleteRec(finalDir);
        if (!tmp.renameTo(finalDir)) throw new IOException("Could not install the voice model");
        if (!isInstalled(c, lang)) throw new IOException("Voice model looks incomplete");
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
