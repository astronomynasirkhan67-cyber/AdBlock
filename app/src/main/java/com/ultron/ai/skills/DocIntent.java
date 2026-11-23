package com.ultron.ai.skills;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognises "make a document about X in Word" (English and Hindi). Pure Java, no Android needed. */
public class DocIntent {
    private static boolean has(String t, String... keys) {
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }

    /** "make a document about black holes in MS Word" -> "black holes". Returns null if not a document request. */
    public static String topic(String raw) {
        String t = raw.toLowerCase(Locale.ROOT).trim();
        boolean docWord = has(t, "document", " doc ", "report", "essay", "assignment", "article", "notes on", "notes about",
                "डॉक्यूमेंट", "डॉक्युमेंट", "दस्तावेज", "रिपोर्ट", "असाइनमेंट", "निबंध");
        boolean makeWord = has(t, "make", "create", "write", "prepare", "generate", "type", "बना", "लिख");
        boolean wordApp = has(t, "ms word", "microsoft word", " word", "वर्ड");
        if (!(docWord && makeWord) && !(wordApp && makeWord)) return null;

        String topic = null;
        Matcher m = Pattern.compile("(?:on|about|regarding)\\s+(.+)$").matcher(t);
        if (m.find()) topic = m.group(1);
        if (topic == null) {
            m = Pattern.compile("(.+?)\\s*(?:के बारे में|के बारे मे|के ऊपर|पर)\\s").matcher(t + " ");
            if (m.find()) topic = m.group(1);
        }
        if (topic == null) return null;
        topic = topic.replaceAll("(?i)\\s+(?:in|using|with|on|inside)\\s+(?:ms\\s+|microsoft\\s+)?word\\s*$", "")
                .replaceAll("(?i)\\s*(?:वर्ड में|एमएस वर्ड में)\\s*$", "")
                .replaceAll("(?i)^(?:a |an |the |एक )", "")
                .replaceAll("(?i)(?:डॉक्यूमेंट|डॉक्युमेंट|दस्तावेज़|दस्तावेज|रिपोर्ट|असाइनमेंट|वर्ड में|बनाओ|बना दो|लिखो|लिख दो)", " ")
                .replaceAll("\\s+", " ").trim();
        return topic.length() < 2 ? null : topic;
    }
}
