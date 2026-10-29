package com.termux.app.files.editor;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Android-independent, bounded file I/O. Offsets and ranges are always byte offsets. */
public final class EditorIo {
    public static final int TEXT_PAGE_BYTES = 128 * 1024;
    public static final int HEX_PAGE_BYTES = 4096;
    private EditorIo() {}

    public static final class ConflictException extends IOException {
        private static final long serialVersionUID = 1L;
        public ConflictException() { super("File changed outside this editor. Reopen it before saving."); }
    }
    public static final class Snapshot implements Serializable {
        private static final long serialVersionUID = 1L;
        public final File file;
        public final long length, modified;
        private final byte[] hash;
        private Snapshot(File file, long length, long modified, byte[] hash) {
            this.file = file; this.length = length; this.modified = modified; this.hash = hash;
        }
    }
    public static Snapshot snapshot(File input) throws IOException {
        File file = input.getCanonicalFile();
        if (!file.isFile()) throw new IOException("Not a readable regular file: " + file);
        long size = file.length(), modified = file.lastModified();
        byte[] hash;
        try (InputStream in = new FileInputStream(file)) { hash = digest(in); }
        if (size != file.length() || modified != file.lastModified()) throw new ConflictException();
        return new Snapshot(file, size, modified, hash);
    }
    public static void verify(Snapshot expected) throws IOException {
        Snapshot actual = snapshot(expected.file);
        if (actual.length != expected.length || actual.modified != expected.modified ||
                !Arrays.equals(actual.hash, expected.hash)) throw new ConflictException();
    }
    public static byte[] digest(InputStream in) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        byte[] buffer = new byte[32768]; int count;
        while ((count = in.read(buffer)) != -1) { cancelled(); digest.update(buffer, 0, count); }
        return digest.digest();
    }
    static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    public interface Writer { void write(OutputStream out) throws IOException; }
    /** Platform hook: copy exact Unix mode before rename; must fail closed if copying fails. */
    public interface Metadata { void copy(File original, File temporary) throws IOException; }
    public static synchronized Snapshot atomicSave(Snapshot expected, Writer writer, Metadata metadata)
            throws IOException {
        verify(expected);
        File temporary = File.createTempFile(".vey-editor-", ".tmp", expected.file.getParentFile());
        boolean renamed = false;
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                writer.write(out); out.flush(); out.getFD().sync();
            }
            if (metadata != null) metadata.copy(expected.file, temporary);
            Snapshot written = snapshot(temporary);
            verify(expected); cancelled();
            // Both files share a directory: Unix rename replaces atomically, never delete first.
            if (!temporary.renameTo(expected.file)) throw new IOException("Atomic rename failed; original retained");
            renamed = true;
            return new Snapshot(expected.file, written.length, expected.file.lastModified(), written.hash);
        } finally {
            if (!renamed && temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }
    public static byte[] readPage(File file, long offset, int maximum) throws IOException {
        if (offset < 0 || maximum < 1 || maximum > 1024 * 1024) throw new IOException("Invalid page range");
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (offset > in.length()) throw new IOException("Offset exceeds file length");
            byte[] bytes = new byte[(int) Math.min(maximum, in.length() - offset)];
            in.seek(offset); in.readFully(bytes); return bytes;
        }
    }
    private static void copyRange(File file, long start, long end, OutputStream out) throws IOException {
        try (InputStream in = range(file, start, end)) { copy(in, out); }
    }
    private static InputStream range(File file, long start, long end) throws IOException {
        if (start < 0 || end < start || end > file.length()) throw new IOException("Invalid byte range");
        final RandomAccessFile input = new RandomAccessFile(file, "r");
        input.seek(start);
        return new InputStream() {
            long remaining = end - start;
            public int read() throws IOException {
                if (remaining == 0) return -1;
                int value = input.read(); if (value < 0) throw new EOFException("File shrank");
                remaining--; return value;
            }
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (remaining == 0) return -1;
                int count = input.read(b, off, (int) Math.min(len, remaining));
                if (count < 0) throw new EOFException("File shrank");
                remaining -= count; return count;
            }
            public void close() throws IOException { input.close(); }
        };
    }
    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[32768]; int count;
        while ((count = in.read(buffer)) != -1) { cancelled(); out.write(buffer, 0, count); }
    }
    public static Snapshot saveRange(Snapshot expected, long start, long end, byte[] replacement,
            Metadata metadata) throws IOException {
        if (start < 0 || end < start || end > expected.length) throw new IOException("Invalid replacement range");
        return atomicSave(expected, out -> {
            copyRange(expected.file, 0, start, out); out.write(replacement);
            copyRange(expected.file, end, expected.length, out);
        }, metadata);
    }
    public static final class Encoding {
        public final Charset charset;
        public final byte[] bom;
        public Encoding(Charset charset, byte[] bom) { this.charset = charset; this.bom = bom.clone(); }
        public String toString() { return charset.name() + (bom.length == 0 ? "" : " + BOM"); }
    }
    public static Encoding detectEncoding(File file) throws IOException {
        byte[] bytes = readPage(file, 0, 4);
        if (starts(bytes, 0x00, 0x00, 0xfe, 0xff)) return encoding("UTF-32BE", true);
        if (starts(bytes, 0xff, 0xfe, 0x00, 0x00)) return encoding("UTF-32LE", true);
        if (starts(bytes, 0xef, 0xbb, 0xbf)) return encoding("UTF-8", true);
        if (starts(bytes, 0xfe, 0xff)) return encoding("UTF-16BE", true);
        if (starts(bytes, 0xff, 0xfe)) return encoding("UTF-16LE", true);
        return encoding("UTF-8", false); // Invalid UTF-8 is an error, never a guessed legacy encoding.
    }
    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        return true;
    }
    public static Encoding encoding(String name, boolean withBom) throws IOException {
        try {
            Charset charset = Charset.forName(name);
            String canonical = charset.name();
            if (encoder(charset).maxBytesPerChar() > 1 &&
                    !canonical.matches("UTF-(8|16LE|16BE|32LE|32BE)|Shift_JIS|windows-31j|GBK|GB18030|GB2312|Big5|Big5-HKSCS|EUC-JP|EUC-KR|x-windows-949|x-Johab"))
                throw new IOException("Stateful or unsupported paged encoding: " + canonical + "; use a stateless Unicode or legacy encoding");
            if (canonical.equals("UTF-16") || canonical.equals("UTF-32"))
                throw new IOException("Choose explicit LE or BE byte order");
            byte[] bom = new byte[0];
            if (withBom) {
                switch (canonical) {
                    case "UTF-8": bom = new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}; break;
                    case "UTF-16LE": bom = new byte[]{(byte) 0xff, (byte) 0xfe}; break;
                    case "UTF-16BE": bom = new byte[]{(byte) 0xfe, (byte) 0xff}; break;
                    case "UTF-32LE": bom = new byte[]{(byte) 0xff, (byte) 0xfe, 0, 0}; break;
                    case "UTF-32BE": bom = new byte[]{0, 0, (byte) 0xfe, (byte) 0xff}; break;
                    default: throw new IOException("This encoding has no supported BOM");
                }
            }
            return new Encoding(charset, bom);
        } catch (IllegalArgumentException | UnsupportedOperationException e) { throw new IOException("Unsupported encoding: " + name, e); }
    }
    static CharsetDecoder decoder(Charset charset) {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }
    static CharsetEncoder encoder(Charset charset) {
        return charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }
    public static byte[] encode(String text, Charset charset) throws IOException {
        try {
            ByteBuffer bytes = encoder(charset).encode(CharBuffer.wrap(text));
            byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
        } catch (CharacterCodingException e) { throw new IOException("Text cannot be represented in " + charset.name(), e); }
    }
    public static final class TextPage {
        public final long start, end;
        public final String text;
        TextPage(long start, long end, String text) { this.start = start; this.end = end; this.text = text; }
    }
    /** Starts at BOM end or an earlier page.end. Never silently consumes incomplete characters. */
    public static TextPage readTextPage(File file, Encoding encoding, long start, int maximum) throws IOException {
        if (start < encoding.bom.length || maximum < 16 || maximum > 1024 * 1024)
            throw new IOException("Invalid text page range");
        byte[] bytes = readPage(file, start, maximum);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharsetDecoder decoder = decoder(encoding.charset);
        CharBuffer output = CharBuffer.allocate(bytes.length * 2 + 8);
        boolean eof = start + bytes.length == file.length();
        CoderResult result = decoder.decode(input, output, eof);
        if (result.isError()) throw new IOException("Invalid " + encoding.charset.name() + " at byte " +
            (start + input.position()) + "; choose the actual encoding, or open Hex");
        if (result.isOverflow()) throw new IOException("Text page character limit exceeded");
        output.flip(); String text = output.toString();
        int consumed = input.position();
        // Keep CRLF in one page, so line-ending conversion also works at page boundaries.
        if (!eof && text.endsWith("\r")) {
            int crBytes = encode("\r", encoding.charset).length;
            consumed -= crBytes; text = text.substring(0, text.length() - 1);
        }
        if (!eof && consumed == 0) throw new IOException("Page cannot advance in this encoding");
        return new TextPage(start, start + consumed, text);
    }
    public static String displayText(String raw) { return raw.replace("\r\n", "\n").replace('\r', '\n'); }
    /** Preserve original mixed line-ending styles; new lines use the prevailing style. */
    public static String retainLineEndings(String original, String edited) {
        List<String> styles = new ArrayList<>(); int crlf = 0, cr = 0, lf = 0;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (c == '\r') {
                if (i + 1 < original.length() && original.charAt(i + 1) == '\n') { styles.add("\r\n"); crlf++; i++; }
                else { styles.add("\r"); cr++; }
            } else if (c == '\n') { styles.add("\n"); lf++; }
        }
        String preferred = crlf > lf && crlf >= cr ? "\r\n" : cr > lf ? "\r" : "\n";
        StringBuilder out = new StringBuilder(); int index = 0;
        for (int i = 0; i < edited.length(); i++) {
            char c = edited.charAt(i);
            if (c == '\n') out.append(index < styles.size() ? styles.get(index++) : preferred);
            else out.append(c);
        }
        return out.toString();
    }
    public enum LineEnding { PRESERVE, LF, CRLF, CR }
    public static String lineEndingSummary(String text) {
        int crlf = 0, lf = 0, cr = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\r') { if (i + 1 < text.length() && text.charAt(i + 1) == '\n') { crlf++; i++; } else cr++; }
            else if (text.charAt(i) == '\n') lf++;
        }
        return "CRLF " + crlf + " / LF " + lf + " / CR " + cr;
    }
    public static Snapshot saveTextPage(Snapshot expected, TextPage page, Encoding source, String text,
            Encoding target, LineEnding endings, Metadata metadata) throws IOException {
        if (page.start < source.bom.length || page.end > expected.length) throw new IOException("Invalid text replacement");
        return atomicSave(expected, out -> {
            out.write(target.bom);
            if (source.charset.equals(target.charset) && endings == LineEnding.PRESERVE) {
                copyRange(expected.file, source.bom.length, page.start, out); out.write(encode(text, target.charset));
                copyRange(expected.file, page.end, expected.length, out);
            } else {
                // Each byte range ends at a decoded character boundary. The edited page is a
                // Reader too, so conversion can introduce characters absent from the source charset.
                List<Reader> parts = new ArrayList<>();
                try {
                    parts.add(new InputStreamReader(range(expected.file, source.bom.length, page.start), decoder(source.charset)));
                    parts.add(new StringReader(text));
                    parts.add(new InputStreamReader(range(expected.file, page.end, expected.length), decoder(source.charset)));
                    OutputStream nonClosing = new FilterOutputStream(out) {
                        @Override public void close() throws IOException { flush(); }
                        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
                    };
                    try (java.io.Writer writer = new OutputStreamWriter(nonClosing, encoder(target.charset))) {
                        char[] buffer = new char[8192]; int count; boolean pendingCr = false;
                        String newline = endings == LineEnding.CRLF ? "\r\n" : endings == LineEnding.CR ? "\r" : "\n";
                        for (Reader reader : parts) while ((count = reader.read(buffer)) != -1) {
                            cancelled();
                            if (endings == LineEnding.PRESERVE) { writer.write(buffer, 0, count); continue; }
                            for (int i = 0; i < count; i++) {
                                char c = buffer[i];
                                if (pendingCr) { writer.write(newline); pendingCr = false; if (c == '\n') continue; }
                                if (c == '\r') pendingCr = true;
                                else if (c == '\n') writer.write(newline);
                                else writer.write(c);
                            }
                        }
                        if (pendingCr) writer.write(newline);
                    }
                } finally { for (Reader part : parts) part.close(); }
            }
        }, metadata);
    }
    public static Pattern searchPattern(String query, boolean regex, boolean caseSensitive) {
        if (query.isEmpty()) throw new IllegalArgumentException("Search is empty");
        if (query.length() > 8192) throw new IllegalArgumentException("Search expression exceeds 8192 characters");
        try { return Pattern.compile(regex ? query : Pattern.quote(query),
            caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
        catch (StackOverflowError e) { throw new IllegalArgumentException("Search expression is too complex", e); }
    }
    public static String replace(String text, String query, String replacement, boolean regex, boolean caseSensitive) {
        try { return searchMatcher(searchPattern(query, regex, caseSensitive), text)
            .replaceAll(regex ? replacement : Matcher.quoteReplacement(replacement)); }
        catch (StackOverflowError e) { throw new IllegalArgumentException("Search expression is too complex", e); }
    }
    /** Best-effort guard: JVM Matcher probes this sequence; Android native Matcher may copy it. */
    public static Matcher searchMatcher(Pattern pattern, String text) { return searchMatcher(pattern, text, 2000); }
    static Matcher searchMatcher(Pattern pattern, String text, long milliseconds) {
        final long deadline = System.nanoTime() + milliseconds * 1000000L;
        return pattern.matcher(new CharSequence() {
            int probes;
            public int length() { return text.length(); }
            public char charAt(int index) {
                if ((probes++ & 255) == 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("Search cancelled");
                    if (System.nanoTime() - deadline >= 0) throw new IllegalArgumentException("Search exceeded two seconds; simplify the expression or page content");
                }
                return text.charAt(index);
            }
            public CharSequence subSequence(int start, int end) { return text.subSequence(start, end); }
            public String toString() { return text; }
        });
    }
    /** Streaming KMP search, including matches straddling an I/O buffer boundary. */
    public static long findBytes(File file, byte[] needle, long start) throws IOException {
        if (needle.length == 0 || needle.length > 1024 * 1024 || start < 0) throw new IOException("Invalid byte search");
        int[] prefix = new int[needle.length];
        for (int i = 1, j = 0; i < needle.length; i++) {
            while (j > 0 && needle[i] != needle[j]) j = prefix[j - 1];
            if (needle[i] == needle[j]) j++;
            prefix[i] = j;
        }
        try (InputStream in = range(file, Math.min(start, file.length()), file.length())) {
            byte[] buffer = new byte[32768]; int count, matched = 0; long offset = start;
            while ((count = in.read(buffer)) != -1) {
                cancelled();
                for (int i = 0; i < count; i++, offset++) {
                    while (matched > 0 && buffer[i] != needle[matched]) matched = prefix[matched - 1];
                    if (buffer[i] == needle[matched]) matched++;
                    if (matched == needle.length) return offset - needle.length + 1;
                }
            }
            return -1;
        }
    }
    public static byte[] parseHex(String value) throws IOException {
        String compact = value.replaceAll("\\s+", "");
        if ((compact.length() & 1) != 0) throw new IOException("Each byte needs two hex digits");
        byte[] bytes = new byte[compact.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(compact.charAt(i * 2), 16), low = Character.digit(compact.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IOException("Use hex digits 00–FF, separated by spaces");
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 3);
        final char[] digits = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(i % 16 == 0 ? '\n' : ' ');
            out.append(digits[(bytes[i] & 255) >>> 4]).append(digits[bytes[i] & 15]);
        }
        return out.toString();
    }
    /** Global logical line lookup, streamed and strict; returns the byte page containing the line. */
    public static TextPage pageForLine(File file, Encoding encoding, long wanted, int pageSize) throws IOException {
        if (wanted < 1) throw new IOException("Line numbers start at 1");
        long offset = encoding.bom.length, line = 1;
        do {
            cancelled(); TextPage page = readTextPage(file, encoding, offset, pageSize);
            for (int i = 0; i < page.text.length(); i++) {
                if (line == wanted) return page;
                char c = page.text.charAt(i);
                if (c == '\r') { line++; if (i + 1 < page.text.length() && page.text.charAt(i + 1) == '\n') i++; }
                else if (c == '\n') line++;
            }
            if (line == wanted) return page;
            offset = page.end;
        } while (offset < file.length());
        throw new IOException("Line exceeds document length (" + line + ")");
    }
    public static long linesBefore(File file, Encoding encoding, long end) throws IOException {
        long lines = 0; boolean previousCr = false;
        try (Reader reader = new InputStreamReader(range(file, encoding.bom.length, end), decoder(encoding.charset))) {
            char[] buffer = new char[8192]; int count;
            while ((count = reader.read(buffer)) != -1) {
                cancelled();
                for (int i = 0; i < count; i++) {
                    char c = buffer[i];
                    if (c == '\r') lines++;
                    else if (c == '\n' && !previousCr) lines++;
                    previousCr = c == '\r';
                }
            }
        }
        return lines;
    }
    public static int newlineCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\r') { count++; if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++; }
            else if (text.charAt(i) == '\n') count++;
        }
        return count;
    }
}
