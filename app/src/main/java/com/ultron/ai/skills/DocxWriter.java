package com.ultron.ai.skills;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds a real .docx file (Word document) with no libraries. */
public class DocxWriter {
    public static final String MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String para(String text, int halfPoints, boolean bold, boolean center, int afterTwips) {
        return "<w:p><w:pPr>" + (center ? "<w:jc w:val=\"center\"/>" : "")
                + "<w:spacing w:after=\"" + afterTwips + "\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>"
                + "<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>"
                + (bold ? "<w:b/><w:bCs/>" : "")
                + "<w:sz w:val=\"" + halfPoints + "\"/><w:szCs w:val=\"" + halfPoints + "\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private static String clean(String line) {
        return line.replace("**", "").replace("__", "").replace("`", "").trim();
    }

    /** First line = title. Lines that end with ':' or start with '#' = headings. Everything else = paragraphs. */
    public static byte[] build(String fallbackTitle, String body) throws IOException {
        StringBuilder x = new StringBuilder();
        String[] lines = body.replace("\r", "").split("\n");
        boolean titleDone = false;
        for (String raw : lines) {
            String line = clean(raw);
            if (line.isEmpty()) continue;
            boolean md = line.startsWith("#");
            line = line.replaceFirst("^#+\\s*", "").replaceFirst("^[*]+\\s*", "");
            if (line.startsWith("- ")) line = "\u2022 " + line.substring(2);
            if (line.isEmpty()) continue;
            if (!titleDone) {
                x.append(para(line.replaceAll("[:]+$", ""), 44, true, true, 360));
                titleDone = true;
            } else if (md || (line.endsWith(":") && line.length() < 80)) {
                x.append(para(line.replaceAll("[:]+$", ""), 30, true, false, 120));
            } else {
                x.append(para(line, 24, false, false, 160));
            }
        }
        if (!titleDone) x.append(para(fallbackTitle, 44, true, true, 360));

        String doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + x
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>"
                + "</w:sectPr></w:body></w:document>";
        String types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, "[Content_Types].xml", types);
            put(z, "_rels/.rels", rels);
            put(z, "word/document.xml", doc);
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
