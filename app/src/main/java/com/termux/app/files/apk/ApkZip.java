package com.termux.app.files.apk;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Streaming mutations create separate unsigned APKs; explicit apply commits a validated copy atomically. */
public final class ApkZip {
    public static final long MAX_ENTRY = 512L * 1024 * 1024;
    public static final long MAX_TOTAL = 4L * 1024 * 1024 * 1024;
    public static final int MAX_ENTRIES = 100000;
    private ApkZip() {}

    public static final class Entry {
        public final String name;
        public final long size, compressedSize, crc;
        public final boolean directory;
        Entry(ZipEntry e) {
            name = e.getName(); size = e.getSize(); compressedSize = e.getCompressedSize();
            crc = e.getCrc(); directory = e.isDirectory();
        }
        @Override public String toString() { return name + "  (" + size + " bytes)"; }
    }

    public static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }

    public static String safeName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")
            || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0)
            throw new IOException("Unsafe ZIP path: " + name);
        for (String part : name.split("/", -1))
            if (part.equals("..") || part.equals(".")) throw new IOException("Unsafe ZIP path: " + name);
        return name;
    }

    private static Map<String, ZipEntry> entries(ZipFile zip) throws IOException {
        Map<String, ZipEntry> result = new TreeMap<>();
        long total = 0;
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            checkCancelled(); ZipEntry e = all.nextElement(); safeName(e.getName());
            if (result.put(e.getName(), e) != null) throw new IOException("Duplicate ZIP path: " + e.getName());
            if (result.size() > MAX_ENTRIES) throw new IOException("Too many ZIP entries");
            if (e.getSize() > MAX_ENTRY) throw new IOException("ZIP entry exceeds 512 MiB: " + e.getName());
            total += Math.max(0, e.getSize());
            if (total > MAX_TOTAL) throw new IOException("ZIP exceeds 4 GiB inflated limit");
        }
        return result;
    }

    public static List<Entry> list(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            List<Entry> result = new ArrayList<>();
            for (ZipEntry e : entries(zip).values()) result.add(new Entry(e));
            return result;
        }
    }

    public static byte[] read(File apk, String name, int limit) throws IOException {
        safeName(name);
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = entries(zip).get(name);
            if (entry == null || entry.isDirectory()) throw new IOException("Missing ZIP file: " + name);
            if (entry.getSize() > limit) throw new IOException("Entry exceeds editor limit of " + limit + " bytes");
            try (CheckedInputStream in = new CheckedInputStream(zip.getInputStream(entry), new CRC32());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                long length = copy(in, out, limit);
                if (length != entry.getSize() || in.getChecksum().getValue() != entry.getCrc())
                    throw new IOException("CRC/size mismatch: " + name);
                return out.toByteArray();
            }
        }
    }

    public static void extract(File apk, String name, File output) throws IOException {
        safeName(name);
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = entries(zip).get(name);
            if (entry == null || entry.isDirectory()) throw new IOException("Missing ZIP file: " + name);
            File temp = temporary(output);
            try {
                try (CheckedInputStream in = new CheckedInputStream(zip.getInputStream(entry), new CRC32());
                     FileOutputStream out = new FileOutputStream(temp)) {
                    long length = copy(in, out, MAX_ENTRY);
                    if (length != entry.getSize() || in.getChecksum().getValue() != entry.getCrc())
                        throw new IOException("CRC/size mismatch: " + name);
                    out.getFD().sync();
                }
                publish(temp, output);
            } finally { temp.delete(); }
        }
    }

    public static boolean staleSignature(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (n.equals("STAMP-CERT-SHA256")) return true;
        if (!n.startsWith("META-INF/")) return false;
        String leaf = n.substring(9);
        return leaf.indexOf('/') < 0 && (leaf.equals("MANIFEST.MF") || leaf.endsWith(".SF")
            || leaf.endsWith(".RSA") || leaf.endsWith(".DSA") || leaf.endsWith(".EC") || leaf.startsWith("SIG-"));
    }

    /** Repacking drops the v2/v3 signing block (outside ZIP entries), v1 files and source stamp. */
    public static void rewrite(File input, File output, Map<String, File> replacements,
                               Set<String> deleted) throws IOException {
        if (input.getCanonicalFile().equals(output.getCanonicalFile()))
            throw new IOException("Choose a separate output APK; original is protected");
        for (Map.Entry<String, File> replacement : replacements.entrySet()) {
            safeName(replacement.getKey());
            if (replacement.getKey().endsWith("/") || staleSignature(replacement.getKey()))
                throw new IOException("Cannot inject a directory or stale signature");
            if (!replacement.getValue().isFile() || replacement.getValue().length() > MAX_ENTRY)
                throw new IOException("Replacement is missing or too large: " + replacement.getKey());
        }
        for (String name : deleted) safeName(name);
        File temp = temporary(output);
        try {
            try (ZipFile zip = new ZipFile(input);
                 FileOutputStream fileOut = new FileOutputStream(temp);
                 CountingOutput counter = new CountingOutput(fileOut);
                 ZipOutputStream out = new ZipOutputStream(counter)) {
                Map<String, ZipEntry> old = entries(zip);
                Set<String> names = new TreeSet<>(old.keySet()); names.addAll(replacements.keySet());
                if (names.size() > MAX_ENTRIES) throw new IOException("Too many output entries");
                long total = 0;
                for (String name : names) {
                    checkCancelled();
                    if (deleted.contains(name) || staleSignature(name)) continue;
                    ZipEntry previous = old.get(name);
                    File replacement = replacements.get(name);
                    ZipEntry next = new ZipEntry(name);
                    next.setTime(315532800000L); // No variable-length timestamp extras: alignment is exact.
                    boolean stored = name.equals("resources.arsc") || name.endsWith(".so")
                        || (previous != null && previous.getMethod() == ZipEntry.STORED);
                    if (stored) {
                        long size; long crc;
                        if (replacement == null) { size = previous.getSize(); crc = previous.getCrc(); }
                        else {
                            CRC32 sum = new CRC32();
                            try (InputStream in = new FileInputStream(replacement)) {
                                byte[] b = new byte[65536]; int n;
                                while ((n = in.read(b)) != -1) { checkCancelled(); sum.update(b, 0, n); }
                            }
                            size = replacement.length(); crc = sum.getValue();
                        }
                        next.setMethod(ZipEntry.STORED); next.setSize(size); next.setCompressedSize(size); next.setCrc(crc);
                        int align = name.endsWith(".so") ? 16384 : 4;
                        int pad = (int) ((align - (counter.count + 30 + name.getBytes(StandardCharsets.UTF_8).length + 4) % align) % align);
                        byte[] extra = new byte[pad + 4];
                        extra[0] = (byte) 0xfe; extra[1] = (byte) 0xca;
                        extra[2] = (byte) pad; extra[3] = (byte) (pad >>> 8); next.setExtra(extra);
                    }
                    out.putNextEntry(next);
                    if (!next.isDirectory()) {
                        try (InputStream in = replacement == null ? zip.getInputStream(previous) : new FileInputStream(replacement)) {
                            total += copy(in, out, MAX_ENTRY);
                            if (total > MAX_TOTAL) throw new IOException("APK exceeds 4 GiB inflated limit");
                        }
                    }
                    out.closeEntry();
                }
                out.finish(); out.flush(); fileOut.getFD().sync();
            }
            // Read all new entries before publishing, so CRC and stream errors cannot replace an artifact.
            test(temp); publish(temp, output);
        } finally { temp.delete(); }
    }

    public static void test(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            long total = 0;
            for (ZipEntry e : entries(zip).values()) {
                if (e.isDirectory()) continue;
                CRC32 crc = new CRC32(); long length = 0;
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) != -1) {
                        checkCancelled(); crc.update(b, 0, n); length += n;
                        if (length > MAX_ENTRY || total + length > MAX_TOTAL) throw new IOException("ZIP inflation limit");
                    }
                }
                if (length != e.getSize() || crc.getValue() != e.getCrc()) throw new IOException("CRC mismatch: " + e.getName());
                total += length;
            }
        }
    }

    /** SHA-256 on inflated bytes, not the untrusted CRC field. */
    public static List<String> diff(File left, File right) throws Exception {
        try (ZipFile a = new ZipFile(left); ZipFile b = new ZipFile(right)) {
            Map<String, ZipEntry> aa = entries(a), bb = entries(b);
            Set<String> names = new TreeSet<>(aa.keySet()); names.addAll(bb.keySet());
            List<String> result = new ArrayList<>();
            for (String name : names) {
                checkCancelled();
                if (!aa.containsKey(name)) result.add("ADDED " + name);
                else if (!bb.containsKey(name)) result.add("REMOVED " + name);
                else if (aa.get(name).isDirectory() != bb.get(name).isDirectory()
                    || !Arrays.equals(digest(a.getInputStream(aa.get(name))), digest(b.getInputStream(bb.get(name)))) )
                    result.add("CHANGED " + name);
            }
            return result;
        }
    }

    private static byte[] digest(InputStream stream) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = stream) {
            byte[] b = new byte[65536]; int n; long length = 0;
            while ((n = in.read(b)) != -1) {
                checkCancelled(); length += n; if (length > MAX_ENTRY) throw new IOException("Entry limit"); md.update(b, 0, n);
            }
        }
        return md.digest();
    }

    public static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int n;
        while ((n = in.read(buffer)) != -1) {
            checkCancelled(); total += n;
            if (total > limit) throw new IOException("Size limit exceeded: " + limit);
            out.write(buffer, 0, n);
        }
        return total;
    }

    static File temporary(File output) throws IOException {
        File absolute = output.getAbsoluteFile(); File parent = absolute.getParentFile();
        if (output.exists()) throw new IOException("Output already exists: " + output);
        if (parent == null || !parent.isDirectory()) throw new IOException("Output directory does not exist");
        return File.createTempFile(".apk-", ".tmp", parent);
    }

    static void publish(File temporary, File output) throws IOException {
        checkCancelled();
        if (output.exists() || !temporary.renameTo(output)) throw new IOException("Cannot publish output: " + output);
    }

    public static void writeNew(File output, byte[] bytes) throws IOException {
        File temp = temporary(output);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
            publish(temp, output);
        } finally { temp.delete(); }
    }

    /** Explicit commit of a validated copy to a caller-staged file. Same-directory rename is
     * atomic on Android/Linux; no delete/truncate of the old file occurs before that rename. */
    public static void apply(File candidate, File original) throws IOException {
        apply(candidate, original, fingerprint(original));
    }

    public static String fingerprint(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = new FileInputStream(file)) {
                byte[] bytes = new byte[65536]; int n;
                while ((n = in.read(bytes)) != -1) {
                    checkCancelled(); total += n;
                    if (total > MAX_TOTAL) throw new IOException("File exceeds 4 GiB");
                    md.update(bytes, 0, n);
                }
            }
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    public static void requireFingerprint(File original, String expected) throws IOException {
        if (expected == null || !expected.equals(fingerprint(original)))
            throw new IOException("Opened file changed since this workspace/draft was loaded; reopen it before applying");
    }

    /** Conflict detection brackets staging; the final same-directory rename remains atomic. */
    public static String apply(File candidate, File original, String expectedFingerprint) throws IOException {
        File target = original.getCanonicalFile();
        requireFingerprint(target, expectedFingerprint);
        if (!target.isFile()) throw new IOException("Opened file no longer exists");
        if (candidate.getCanonicalFile().equals(target)) throw new IOException("Candidate must be a separate output");
        File temp = File.createTempFile(".apk-apply-", ".tmp", target.getParentFile());
        try {
            try (InputStream in = new FileInputStream(candidate); FileOutputStream out = new FileOutputStream(temp)) {
                copy(in, out, MAX_TOTAL); out.getFD().sync();
            }
            test(temp); checkCancelled();
            String appliedFingerprint = fingerprint(temp);
            requireFingerprint(target, expectedFingerprint);
            if (!target.isFile() || !temp.renameTo(target)) throw new IOException("Cannot atomically apply APK; original preserved");
            // No interruptible I/O after commit: callers must report this successful apply.
            return appliedFingerprint;
        } finally { temp.delete(); }
    }

    private static final class CountingOutput extends FilterOutputStream {
        long count;
        CountingOutput(OutputStream out) { super(out); }
        @Override public void write(int value) throws IOException { out.write(value); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
