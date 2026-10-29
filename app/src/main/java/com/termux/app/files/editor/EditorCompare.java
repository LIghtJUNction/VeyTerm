package com.termux.app.files.editor;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Real content comparisons; reports are bounded, scanning and counters are not silently truncated. */
public final class EditorCompare {
    private EditorCompare() {}
    public enum Mode { AUTO, TEXT, BINARY, DIRECTORY, ZIP }
    public static final class Report {
        private final StringBuilder rows = new StringBuilder();
        public long differences, compared;
        public boolean limited;
        void row(String text) {
            if (rows.length() + text.length() < 256 * 1024) rows.append(text).append('\n');
            else limited = true;
        }
        public String toString() {
            return "Compared: " + compared + "; differences: " + differences + "\n" +
                (limited ? "REPORT LIMIT: additional differences counted but not displayed.\n" : "") + rows;
        }
    }
    public static Report compare(File left, File right, Mode mode) throws IOException {
        if (!left.exists() || !right.exists()) throw new IOException("Both comparison paths must exist");
        if (mode == Mode.AUTO) {
            if (left.isDirectory() && right.isDirectory()) mode = Mode.DIRECTORY;
            else if (zipName(left) && zipName(right)) mode = Mode.ZIP;
            else mode = Mode.BINARY;
        }
        if (mode != Mode.DIRECTORY && (!left.isFile() || !right.isFile()))
            throw new IOException("File comparison requires two regular files");
        Report report = new Report();
        switch (mode) {
            case TEXT: text(left, right, report); break;
            case BINARY: binary(left, right, report); break;
            case DIRECTORY:
                if (!left.isDirectory() || !right.isDirectory()) throw new IOException("Choose two directories");
                directory(left.getCanonicalFile(), right.getCanonicalFile(), "", report, 0); break;
            case ZIP: zip(left, right, report); break;
            default: throw new IOException("Unsupported comparison mode");
        }
        return report;
    }
    private static boolean zipName(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip") || name.endsWith(".apk") || name.endsWith(".jar");
    }
    private static void binary(File left, File right, Report report) throws IOException {
        long length = Math.max(left.length(), right.length()), runStart = -1;
        try (InputStream a = new BufferedInputStream(new FileInputStream(left));
             InputStream b = new BufferedInputStream(new FileInputStream(right))) {
            for (long offset = 0; offset < length; offset++) {
                if ((offset & 32767) == 0) EditorIo.cancelled();
                int x = a.read(), y = b.read();
                if (x != y) { report.differences++; if (runStart < 0) runStart = offset; }
                else if (runStart >= 0) { report.row(String.format(Locale.ROOT, "Different bytes: 0x%X..0x%X", runStart, offset - 1)); runStart = -1; }
            }
        }
        if (runStart >= 0) report.row(String.format(Locale.ROOT, "Different bytes: 0x%X..0x%X", runStart, length - 1));
        report.compared = length;
        report.row("Sizes: " + left.length() + " / " + right.length() + " bytes");
    }
    private static void text(File left, File right, Report report) throws IOException {
        EditorIo.Encoding x = EditorIo.detectEncoding(left), y = EditorIo.detectEncoding(right);
        // Pages preserve exact line endings; line comparisons retain CR/LF and EOF distinctions.
        try (LineReader a = new LineReader(left, x); LineReader b = new LineReader(right, y)) {
            String p, q; long line = 0;
            while (true) {
                EditorIo.cancelled(); p = a.line(); q = b.line();
                if (p == null && q == null) break;
                line++; report.compared++;
                if (!Objects.equals(p, q)) {
                    report.differences++;
                    report.row("Line " + line + "\n- " + preview(p) + "\n+ " + preview(q));
                }
            }
        }
        report.row("Encodings: " + x + " / " + y);
        if (!x.charset.equals(y.charset) || !Arrays.equals(x.bom, y.bom)) {
            report.differences++; report.row("Encoding/BOM differs");
        }
    }
    private static String preview(String value) {
        if (value == null) return "<EOF>";
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
    /** Bounded logical lines: overlong lines fail explicitly instead of allocating the whole file. */
    private static final class LineReader implements Closeable {
        final PushbackReader reader;
        LineReader(File file, EditorIo.Encoding encoding) throws IOException {
            FileInputStream in = new FileInputStream(file);
            try {
                for (int i = 0; i < encoding.bom.length; i++) if (in.read() == -1) throw new EOFException();
                reader = new PushbackReader(new BufferedReader(new InputStreamReader(in, EditorIo.decoder(encoding.charset))), 1);
            } catch (IOException e) { in.close(); throw e; }
        }
        String line() throws IOException {
            StringBuilder line = new StringBuilder(); int c;
            while ((c = reader.read()) != -1) {
                if (line.length() >= 1024 * 1024) throw new IOException("Text line exceeds 1 MiB; use binary comparison");
                line.append((char) c);
                if (c == '\n') break;
                if (c == '\r') { int next = reader.read(); if (next == '\n') line.append('\n'); else if (next != -1) reader.unread(next); break; }
            }
            return line.length() == 0 ? null : line.toString();
        }
        public void close() throws IOException { reader.close(); }
    }
    private static void directory(File left, File right, String prefix, Report report, int depth) throws IOException {
        if (depth > 128) throw new IOException("Directory nesting exceeds 128 levels");
        String[] aa = left.list(), bb = right.list();
        if (aa == null || bb == null) throw new IOException("Cannot list " + (aa == null ? left : right));
        TreeSet<String> names = new TreeSet<>(); Collections.addAll(names, aa); Collections.addAll(names, bb);
        for (String name : names) {
            EditorIo.cancelled(); File a = new File(left, name), b = new File(right, name); String path = prefix + name;
            // Compare links as links. Do not recurse through them, even to an in-tree directory.
            String targetA = EditorLinks.target(a), targetB = EditorLinks.target(b);
            boolean linkA = targetA != null, linkB = targetB != null;
            report.compared++;
            if (linkA || linkB) {
                if (linkA != linkB || !Objects.equals(targetA, targetB)) {
                    report.differences++; report.row("LINK " + path + ": " + targetA + " / " + targetB);
                }
            } else if (!a.exists() || !b.exists()) {
                report.differences++; report.row((a.exists() ? "LEFT ONLY " : "RIGHT ONLY ") + path);
            } else if (a.isDirectory() != b.isDirectory()) { report.differences++; report.row("TYPE " + path); }
            else if (a.isDirectory()) directory(a, b, path + "/", report, depth + 1);
            else if (!a.isFile() || !b.isFile()) throw new IOException("Cannot compare special file: " + path);
            else if (!sameContent(a, b)) { report.differences++; report.row("CONTENT " + path); }
        }
    }
    private static boolean sameContent(File a, File b) throws IOException {
        if (a.length() != b.length()) return false;
        try (InputStream x = new FileInputStream(a); InputStream y = new FileInputStream(b)) {
            return Arrays.equals(EditorIo.digest(x), EditorIo.digest(y));
        }
    }
    private static Map<String, ZipEntry> entries(ZipFile zip) throws IOException {
        Map<String, ZipEntry> entries = new TreeMap<>(); Enumeration<? extends ZipEntry> list = zip.entries();
        while (list.hasMoreElements()) {
            EditorIo.cancelled(); ZipEntry entry = list.nextElement();
            if (entries.put(entry.getName(), entry) != null) throw new IOException("Ambiguous duplicate ZIP entry: " + entry.getName());
            if (entries.size() > 100000) throw new IOException("ZIP exceeds 100000 entries");
        }
        return entries;
    }
    private static void zip(File left, File right, Report report) throws IOException {
        try (ZipFile a = new ZipFile(left); ZipFile b = new ZipFile(right)) {
            Map<String, ZipEntry> aa = entries(a), bb = entries(b);
            TreeSet<String> names = new TreeSet<>(aa.keySet()); names.addAll(bb.keySet());
            for (String name : names) {
                EditorIo.cancelled(); ZipEntry x = aa.get(name), y = bb.get(name); report.compared++;
                if (x == null || y == null) { report.differences++; report.row((x == null ? "RIGHT ONLY " : "LEFT ONLY ") + name); }
                else if (x.isDirectory() != y.isDirectory()) { report.differences++; report.row("TYPE " + name); }
                else if (!x.isDirectory()) {
                    boolean same;
                    try (InputStream p = a.getInputStream(x); InputStream q = b.getInputStream(y)) {
                        same = Arrays.equals(EditorIo.digest(p), EditorIo.digest(q));
                    }
                    if (!same) { report.differences++; report.row("CONTENT " + name); }
                }
            }
        }
        report.row("ZIP comparison uses uncompressed content; timestamps/compression/signatures are not compared.");
    }
}
