package com.termux.app.files.data;

import com.termux.app.files.fs.FileAccess;
import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;
import com.termux.app.files.fs.FileOperations;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** Streaming data operations over FileAccess; never converts locations into host paths. */
public final class FileDataTools {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int MAX_LINE_CHARS = 64 * 1024;
    private static final int MAX_REPORT_CHARS = 256 * 1024;
    private static final Pattern GNU = Pattern.compile("^(\\\\?)([0-9a-fA-F]{32}|[0-9a-fA-F]{40}|[0-9a-fA-F]{64}) ([ *])(.+)$");

    private FileDataTools() { }

    /** Names are prefix.001, prefix.002, ...; an empty source produces one empty part. */
    public static List<FileLocation> split(FileAccess fs, FileLocation source,
                                          FileLocation destination, String prefix, long partBytes,
                                          FileOperations.Monitor monitor) throws IOException {
        List<FileLocation> completed = new ArrayList<>();
        try {
            Progress progress = new Progress(monitor);
            progress.check();
            name(prefix);
            if (partBytes <= 0) throw new IOException("Part size must be positive");
            directory(fs, destination);
            FileEntry before = regular(fs, source);
            byte[] buffer = new byte[BUFFER_BYTES];
            long sourceBytes = 0;
            try (InputStream in = fs.read(source)) {
                int first;
                do {
                    progress.check();
                    first = in.read();
                    progress.check();
                    if (first == -1 && !completed.isEmpty()) break;
                    String partName = prefix + "." + String.format(Locale.ROOT, "%03d", (long) completed.size() + 1);
                    absent(fs, destination, partName);
                    try (Pending pending = new Pending(fs, destination)) {
                        long written = 0;
                        try (OutputStream out = fs.write(pending.location, true)) {
                            if (first != -1) {
                                out.write(first);
                                written++;
                                sourceBytes++;
                                progress.add(before.name, 1);
                            }
                            while (first != -1 && written < partBytes) {
                                int count = read(in, buffer, (int) Math.min(buffer.length, partBytes - written), progress);
                                if (count == -1) { first = -1; break; }
                                out.write(buffer, 0, count);
                                written += count;
                                sourceBytes += count;
                                progress.add(before.name, count);
                            }
                        }
                        progress.check();
                        unchanged(fs, before);
                        if (first == -1) sizeMatches(before, sourceBytes);
                        FileLocation output = pending.publish(destination, partName, progress);
                        completed.add(output);
                        progress.done(partName);
                    }
                } while (first != -1);
            }
            progress.check();
            unchanged(fs, before);
            sizeMatches(before, sourceBytes);
            return Collections.unmodifiableList(new ArrayList<>(completed));
        } catch (IOException | RuntimeException failure) {
            String message = "Split failed: " + failure.getMessage() + "; completed outputs ("
                + completed.size() + "): " + completed;
            IOException reported = failure instanceof InterruptedIOException
                ? new InterruptedIOException(message) : new IOException(message);
            reported.initCause(failure);
            throw reported;
        }
    }

    /** Concatenates sources in caller order and publishes a single new file. */
    public static FileLocation merge(FileAccess fs, List<FileLocation> orderedSources,
                                     FileLocation destination, String name,
                                     FileOperations.Monitor monitor) throws IOException {
        Progress progress = new Progress(monitor);
        progress.check();
        name(name);
        directory(fs, destination);
        absent(fs, destination, name);
        if (orderedSources == null || orderedSources.isEmpty()) throw new IOException("Choose at least one merge source");
        List<FileEntry> sources = new ArrayList<>();
        for (FileLocation source : new ArrayList<>(orderedSources)) {
            progress.check();
            sources.add(regular(fs, source));
        }
        byte[] buffer = new byte[BUFFER_BYTES];
        try (Pending pending = new Pending(fs, destination)) {
            try (OutputStream out = fs.write(pending.location, true)) {
                for (FileEntry before : sources) {
                    long bytes = 0;
                    progress.check();
                    unchanged(fs, before);
                    try (InputStream in = fs.read(before.location)) {
                        int count;
                        while ((count = read(in, buffer, buffer.length, progress)) != -1) {
                            out.write(buffer, 0, count);
                            bytes += count;
                            progress.add(before.name, count);
                        }
                    }
                    unchanged(fs, before);
                    sizeMatches(before, bytes);
                    progress.done(before.name);
                }
            }
            progress.check();
            for (FileEntry before : sources) { progress.check(); unchanged(fs, before); }
            return pending.publish(destination, name, progress);
        }
    }

    /** Returns a human-readable report; malformed/unsafe input and cancellation throw IOException. */
    public static String verifyManifest(FileAccess fs, FileLocation manifest,
                                        FileOperations.Monitor monitor) throws IOException {
        Progress progress = new Progress(monitor);
        progress.check();
        FileEntry before = regular(fs, manifest);
        FileLocation root = fs.parent(manifest);
        if (root == null) throw new IOException("Manifest has no parent directory");
        directory(fs, root);
        Report report = new Report();
        long lines = 0, entries = 0, ok = 0, mismatched = 0, missing = 0, errors = 0;
        ManifestInput input = new ManifestInput(fs.read(manifest), progress);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input,
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            String line;
            while ((line = line(reader, progress)) != null) {
                lines++;
                if (lines == 1 && line.startsWith("\ufeff")) line = line.substring(1);
                if (line.trim().isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
                Record record = parse(line, lines);
                String[] components = confinedComponents(record.path, lines);
                progress.check();
                // Resolve outside the per-file error handler: unsafe paths must abort verification.
                FileLocation target;
                try { target = resolve(fs, root, components, record.path, progress); }
                catch (FileNotFoundException absent) {
                    report.add("MISSING: " + record.path);
                    missing++; entries++; progress.done(record.path); continue;
                }
                entries++;
                try {
                    String actual = checksum(fs, target, record.algorithm, progress);
                    if (record.expected.equals(actual)) {
                        ok++;
                        report.add("OK: " + record.path);
                    } else {
                        mismatched++;
                        report.add("MISMATCH: " + record.path + " (" + record.algorithm + ") expected "
                            + record.expected + ", actual " + actual);
                    }
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (FileNotFoundException absent) { missing++; report.add("MISSING: " + record.path); }
                catch (IOException failure) { errors++; report.add("ERROR: " + record.path + ": " + failure.getMessage()); }
                progress.done(record.path);
            }
        }
        progress.check();
        unchanged(fs, before);
        sizeMatches(before, input.bytes);
        if (entries == 0) throw new IOException("Manifest contains no checksum entries");
        return "Verified " + entries + " entries: " + ok + " OK, " + mismatched + " mismatched, "
            + missing + " missing, " + errors + " errors.\n" + report;
    }

    private static Record parse(String line, long number) throws IOException {
        Matcher gnu = GNU.matcher(line);
        if (gnu.matches()) {
            String expected = gnu.group(2).toLowerCase(Locale.ROOT);
            String path = gnu.group(4);
            if (!gnu.group(1).isEmpty()) path = unescape(path, number);
            return new Record(path, expected, expected.length() == 32 ? "MD5"
                : expected.length() == 40 ? "SHA-1" : "SHA-256");
        }
        // Scan the last SFV token directly; regex backtracking on long whitespace is quadratic.
        int end = line.length();
        while (end > 0 && horizontalSpace(line.charAt(end - 1))) end--;
        int hashStart = end - 8;
        if (hashStart > 1 && horizontalSpace(line.charAt(hashStart - 1))) {
            boolean hex = true;
            for (int i = hashStart; i < end; i++) {
                char c = line.charAt(i);
                if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F')) hex = false;
            }
            int nameEnd = hashStart;
            while (nameEnd > 0 && horizontalSpace(line.charAt(nameEnd - 1))) nameEnd--;
            if (hex && nameEnd > 0) return new Record(line.substring(0, nameEnd),
                line.substring(hashStart, end).toLowerCase(Locale.ROOT), "CRC32");
        }
        throw new IOException("Malformed checksum entry at line " + number + "; expected SFV or GNU MD5/SHA1/SHA256");
    }

    private static boolean horizontalSpace(char c) { return c == ' ' || c == '\t'; }

    private static String unescape(String path, long line) throws IOException {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\') {
                if (++i == path.length()) throw new IOException("Incomplete GNU escape at line " + line);
                c = path.charAt(i);
                if (c == 'n') c = '\n';
                else if (c == 'r') c = '\r';
                else if (c != '\\') throw new IOException("Invalid GNU escape at line " + line);
            }
            value.append(c);
        }
        return value.toString();
    }

    private static String[] confinedComponents(String path, long line) throws IOException {
        if (path.startsWith("./")) path = path.substring(2);
        if (path.isEmpty() || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0)
            throw new IOException("Unsafe manifest path at line " + line);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 32 || c == 127) throw new IOException("Unsafe manifest path at line " + line);
        }
        String[] components = path.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw new IOException("Unsafe manifest path at line " + line + ": " + path);
        }
        return components;
    }

    private static FileLocation resolve(FileAccess fs, FileLocation root, String[] components,
                                        String display, Progress progress) throws IOException {
        FileLocation current = root;
        for (int i = 0; i < components.length; i++) {
            progress.check();
            current = fs.child(current, components[i]);
            if (current.equals(root) || !fs.contains(root, current))
                throw new IOException("Unsafe manifest path outside its directory: " + display);
            if (!fs.exists(current)) throw new FileNotFoundException(display);
            FileEntry entry = fs.stat(current);
            if (entry.symbolicLink) throw new IOException("Unsafe manifest path through a symbolic link: " + display);
            if (entry.directory != (i < components.length - 1))
                throw new IOException("Manifest path has the wrong file type: " + display);
        }
        return current;
    }

    private static String checksum(FileAccess fs, FileLocation source, String algorithm,
                                    Progress progress) throws IOException {
        FileEntry before = regular(fs, source);
        MessageDigest digest = null;
        CRC32 crc = null;
        if (algorithm.equals("CRC32")) crc = new CRC32();
        else {
            try { digest = MessageDigest.getInstance(algorithm); }
            catch (NoSuchAlgorithmException e) { throw new IOException("Unavailable checksum algorithm: " + algorithm, e); }
        }
        long bytes = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = fs.read(source)) {
            int count;
            while ((count = read(in, buffer, buffer.length, progress)) != -1) {
                if (digest == null) crc.update(buffer, 0, count); else digest.update(buffer, 0, count);
                bytes += count;
                progress.add(before.name, count);
            }
        }
        progress.check();
        unchanged(fs, before);
        sizeMatches(before, bytes);
        if (digest == null) return String.format(Locale.ROOT, "%08x", crc.getValue());
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private static String line(BufferedReader reader, Progress progress) throws IOException {
        StringBuilder value = new StringBuilder();
        progress.check();
        int c;
        while ((c = reader.read()) != -1 && c != '\n') {
            if (value.length() == MAX_LINE_CHARS) throw new IOException("Manifest line exceeds " + MAX_LINE_CHARS + " characters");
            value.append((char) c);
            if ((value.length() & 1023) == 0) progress.check();
        }
        if (c == -1 && value.length() == 0) return null;
        if (value.length() > 0 && value.charAt(value.length() - 1) == '\r') value.setLength(value.length() - 1);
        return value.toString();
    }

    private static int read(InputStream in, byte[] buffer, int length, Progress progress) throws IOException {
        progress.check();
        int count = in.read(buffer, 0, length);
        if (count == 0) {
            int single = in.read();
            if (single == -1) count = -1;
            else { buffer[0] = (byte) single; count = 1; }
        }
        progress.check();
        return count;
    }

    private static void name(String name) throws IOException {
        try { FileLocation.validateName(name); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid output name", e); }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || c == '\\') throw new IOException("Invalid output name");
        }
    }

    private static void directory(FileAccess fs, FileLocation location) throws IOException {
        FileEntry entry = fs.stat(location);
        if (!entry.directory || entry.symbolicLink) throw new IOException("Choose a real destination/manifest directory");
    }

    private static FileEntry regular(FileAccess fs, FileLocation location) throws IOException {
        FileEntry entry = fs.stat(location);
        if (entry.directory || entry.symbolicLink) throw new IOException("Choose a regular file: " + location);
        return entry;
    }

    private static void unchanged(FileAccess fs, FileEntry before) throws IOException {
        FileEntry now = fs.stat(before.location);
        if (now.directory || now.symbolicLink || now.size != before.size || now.modified != before.modified)
            throw new IOException("Source changed during the operation: " + before.location);
    }

    private static void sizeMatches(FileEntry before, long bytes) throws IOException {
        if (before.size >= 0 && before.size != bytes)
            throw new IOException("Source changed or returned incomplete bytes: " + before.location);
    }

    private static void absent(FileAccess fs, FileLocation directory, String name) throws IOException {
        if (fs.exists(fs.child(directory, name))) throw new IOException("Target already exists; preserved: " + name);
    }

    /** Cleanup is unconditional: provider-owned temporaries need not be visible to exists(). */
    private static final class Pending implements AutoCloseable {
        final FileAccess fs;
        final FileLocation location;
        boolean published;
        Pending(FileAccess fs, FileLocation directory) throws IOException {
            this.fs = fs;
            location = fs.createTemporary(directory);
        }
        FileLocation publish(FileLocation directory, String name, Progress progress) throws IOException {
            progress.check();
            absent(fs, directory, name);
            FileLocation result = fs.rename(location, name);
            published = true;
            return result;
        }
        @Override public void close() throws IOException { if (!published) fs.delete(location); }
    }

    private static final class Progress {
        final FileOperations.Monitor monitor;
        long bytes, files;
        Progress(FileOperations.Monitor monitor) { this.monitor = monitor == null ? FileOperations.NONE : monitor; }
        void check() throws InterruptedIOException { monitor.checkCancelled(); }
        void add(String name, int count) throws InterruptedIOException { bytes += count; monitor.progress(name, bytes, files); check(); }
        void done(String name) { monitor.progress(name, bytes, ++files); }
    }

    private static final class Record {
        final String path, expected, algorithm;
        Record(String path, String expected, String algorithm) {
            this.path = path; this.expected = expected; this.algorithm = algorithm;
        }
    }

    private static final class ManifestInput extends FilterInputStream {
        final Progress progress;
        long bytes;
        ManifestInput(InputStream input, Progress progress) { super(input); this.progress = progress; }
        @Override public int read() throws IOException {
            progress.check();
            int value = in.read();
            if (value != -1) bytes++;
            progress.check();
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            progress.check();
            int count = in.read(buffer, offset, length);
            if (count == 0) {
                int single = in.read();
                if (single == -1) count = -1;
                else { buffer[offset] = (byte) single; count = 1; }
            }
            if (count > 0) bytes += count;
            progress.check();
            return count;
        }
    }

    private static final class Report {
        final StringBuilder value = new StringBuilder();
        boolean truncated;
        void add(String line) {
            if (value.length() + line.length() + 1 <= MAX_REPORT_CHARS) value.append(line).append('\n');
            else truncated = true;
        }
        @Override public String toString() { return value + (truncated ? "Further details omitted; summary includes every entry.\n" : ""); }
    }
}
