package com.termux.app.files.archive;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public CLI/stdio/files only. No Android imports, shell, PATH search or JNI.
 * The configured command is trusted executable code; see the replacement
 * contract in file-manager-rar-complete.md, including lossless-name rejection.
 */
final class CommandRar {
    interface Check { void run() throws IOException; }
    interface Read { void run(InputStream in) throws IOException; }
    static final class Member {
        final String name;
        final boolean directory, encrypted;
        final long size, modified;
        Member(String n, boolean d, boolean e, long s, long m) {
            name = n; directory = d; encrypted = e; size = s; modified = m;
        }
    }
    private static final int STDERR_LIMIT = 65536;
    private static final Pattern FIELD = Pattern.compile("^ {0,11}([A-Za-z0-9 -]+): (.*)$");
    private static final Pattern COMPRESSION = Pattern.compile("RAR (?:1\\.5|5\\.0)\\(v[0-9]+\\) -m[0-5](?: -md=([0-9]+)([kmg]))?");
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList("Name", "Type", "Size", "Packed size", "Ratio",
        "mtime", "ctime", "atime", "Attributes", "CRC32", "CRC32 MAC", "Pack-CRC32", "BLAKE2", "BLAKE2 MAC",
        "Pack-BLAKE2", "Host OS", "Compression", "Flags"));
    final List<Member> members = new ArrayList<>();
    final Set<File> volumes = new LinkedHashSet<>();
    private final File executable, archive;
    private final ArchiveTools.Options options;
    private final Check check;
    private final long timeoutMs, dictionaryKiB;
    private final int listingLimit;

    CommandRar(File executable, File archive, ArchiveTools.Options options, Check check) throws IOException {
        this(executable, archive, options, check, 120000);
    }
    CommandRar(File executable, File archive, ArchiveTools.Options options, Check check, long timeoutMs) throws IOException {
        this.executable = executable; this.archive = archive.getAbsoluteFile();
        this.options = options; this.check = check; this.timeoutMs = timeoutMs;
        // Reserve half for dictionary and half for other decoder allocations.
        dictionaryKiB = Integer.highestOneBit(Math.min(options.maxMemoryKiB, 262144) / 2);
        if (dictionaryKiB < 128) throw new IOException("RAR command memory limit too small");
        listingLimit = (int)Math.min(8L * 1024 * 1024, options.maxMemoryKiB * 1024L / 4);
        safeText(this.archive.getPath());
        if (this.archive.getPath().matches(".*[?*\\[\\]].*"))
            throw new IOException("Ambiguous wildcard in RAR archive path");
        if (options.password != null) {
            if (options.password.length > 127) throw new IOException("RAR password exceeds command limit");
            for (int i = 0; i < options.password.length; i++) {
                char c = options.password[i];
                if (Character.isHighSurrogate(c) && i + 1 < options.password.length && Character.isLowSurrogate(options.password[i + 1])) {
                    i++; continue;
                }
                if (Character.isISOControl(c) || Character.isSurrogate(c))
                    throw new IOException("RAR stdin password contains an unsupported character");
            }
        }
        run("lt", this::parseListing, listingLimit);
    }

    void test() throws IOException { run("t", in -> { if (in.read() != -1) throw new IOException("Unexpected RAR test stdout"); }, 0); }

    void copy(Member wanted, OutputStream output) throws IOException {
        // Never pass member names as wildcard patterns. Print the complete archive,
        // split by validated declared sizes, discard unselected bytes, and await
        // the command's checksum verdict BEFORE returning to atomic publication.
        // Each selected file gets a complete pass, including solid dependencies.
        long total = 0;
        for (Member m : members) total += m.size;
        run("p", in -> {
            byte[] buffer = new byte[32768];
            for (Member member : members) {
                long remaining = member.size;
                while (remaining > 0) {
                    check.run();
                    int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    if (n < 0) throw new IOException("Truncated RAR stdout");
                    if (member == wanted) output.write(buffer, 0, n);
                    remaining -= n;
                }
            }
            if (in.read() != -1) throw new IOException("RAR stdout exceeds declared entry sizes");
        }, total);
    }

    private void run(String command, Read read, long stdoutLimit) throws IOException {
        check.run();
        List<String> args = new ArrayList<>(Arrays.asList(executable.getPath(), command, "-cfg-", "-c-",
            "-mdx" + dictionaryKiB + "k", "-limm" + (dictionaryKiB * 2) + "k", "-limt120", "-y", "-scf", "-idcp"));
        // -scf is UTF-8 (the public 'f' charset), -p without text reads stdin.
        args.add(options.password == null || options.password.length == 0 ? "-p-" : "-p");
        if (command.equals("lt")) args.add("-v"); else args.add("-inul");
        args.add("--"); args.add(archive.getPath());
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(archive.getParentFile());
        builder.environment().remove("RAR");
        builder.environment().remove("RARINISWITCHES");
        builder.environment().put("LC_ALL", "C.UTF-8");
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("TZ", "UTC");
        Process process = builder.start();
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread stdout = worker("vey-rar-stdout", failure, () -> {
            try (InputStream in = new LimitedInput(process.getInputStream(), stdoutLimit)) { read.run(in); }
        });
        Thread stderr = worker("vey-rar-stderr", failure, () -> {
            try (InputStream in = new LimitedInput(process.getErrorStream(), STDERR_LIMIT)) {
                byte[] discard = new byte[1024];
                while (in.read(discard) != -1) { /* Never retain/log diagnostics or passwords. */ }
            }
        });
        long deadline = System.nanoTime() + timeoutMs * 1000000;
        IOException primary = null;
        try {
            try (Writer input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                if (options.password != null && options.password.length != 0) {
                    input.write(options.password); input.write('\n');
                }
            }
            while (true) {
                check.run();
                if (failure.get() != null) throw failure.get();
                if (System.nanoTime() - deadline >= 0) throw new IOException("RAR command timed out");
                Integer code = exitCode(process);
                if (code != null && !stdout.isAlive() && !stderr.isAlive()) {
                    if (failure.get() != null) throw failure.get();
                    if (code != 0) throw new IOException("RAR command failed (exit " + code
                        + "; wrong/missing password, corrupt/incomplete volumes, or memory limit)");
                    return;
                }
                try { Thread.sleep(20); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Archive operation cancelled"); }
            }
        } catch (IOException e) { primary = e; throw e; }
        finally {
            // Process.destroy/exitValue and Thread.join are available at Android 21.
            // No waitFor(timeout), isAlive, ProcessHandle or destroyForcibly APIs.
            process.destroy();
            close(process.getOutputStream()); close(process.getInputStream()); close(process.getErrorStream());
            boolean interrupted = Thread.interrupted();
            try {
                stdout.join(1000); stderr.join(1000);
                if ((stdout.isAlive() || stderr.isAlive() || exitCode(process) == null) && primary == null)
                    throw new IOException("RAR command did not terminate");
            } catch (InterruptedException e) { interrupted = true; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static Integer exitCode(Process p) {
        try { return p.exitValue(); } catch (IllegalThreadStateException e) { return null; }
    }
    private static void close(Closeable c) { try { c.close(); } catch (IOException ignored) {} }
    private static Thread worker(String name, AtomicReference<IOException> failure, Task task) {
        Thread t = new Thread(() -> {
            try { task.run(); }
            catch (IOException e) { failure.compareAndSet(null, e); }
            catch (RuntimeException e) { failure.compareAndSet(null, new IOException("Invalid RAR command output", e)); }
        }, name);
        t.setDaemon(true); t.start(); return t;
    }
    private interface Task { void run() throws IOException; }
    private static final class LimitedInput extends FilterInputStream {
        private long remaining;
        LimitedInput(InputStream in, long limit) { super(in); remaining = limit; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int n = in.read(bytes, offset, remaining >= length ? length : (int)remaining + 1);
            if (n > remaining) throw new IOException("RAR command output exceeds configured bound");
            if (n > 0) remaining -= n;
            return n;
        }
    }

    private void parseListing(InputStream input) throws IOException {
        Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        Map<String, String> record = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        Member pending = null;
        int pendingVolume = 0;
        boolean details = false;
        long total = 0;
        String line;
        while ((line = line(reader)) != null) {
            check.run();
            if (line.isEmpty()) {
                if (!record.isEmpty()) {
                    String type = require(record, "Type");
                    Member m = member(record);
                    if (!type.equals("Service")) {
                        String ratio = record.get("Ratio");
                        if (!m.directory && !"-->".equals(ratio) && !"<->".equals(ratio)
                            && !record.containsKey("CRC32") && !record.containsKey("CRC32 MAC")
                            && !record.containsKey("BLAKE2") && !record.containsKey("BLAKE2 MAC"))
                            throw new IOException("RAR member has no verifiable checksum");
                        if (pending != null) {
                            if (!pending.name.equals(m.name) || pending.size != m.size || pending.encrypted != m.encrypted
                                || pending.directory != m.directory || pending.modified != m.modified
                                || volumes.size() != pendingVolume + 1 || !("<->".equals(ratio) || "<--".equals(ratio)))
                                throw new IOException("Ambiguous RAR volume continuation");
                            pendingVolume = volumes.size();
                            if ("<--".equals(ratio)) pending = null;
                        } else {
                            if ("<->".equals(ratio) || "<--".equals(ratio)) throw new IOException("RAR requires the first local volume");
                            if (!names.add(m.name) || members.size() >= options.maxEntries)
                                throw new IOException("Duplicate RAR member or entry limit");
                            if (m.size > options.maxTotalBytes - total) throw new IOException("RAR total size exceeds configured limit");
                            total += m.size; members.add(m);
                            if ("-->".equals(ratio)) { pending = m; pendingVolume = volumes.size(); }
                        }
                    }
                    record.clear();
                }
                continue;
            }
            if (line.startsWith("Archive: ") && record.isEmpty()) {
                File volume = new File(line.substring(9));
                safeText(volume.getPath());
                File leaf = new File(archive.getParentFile().getCanonicalFile(), volume.getName());
                if (!volume.isAbsolute() || !volume.isFile() || !volume.getCanonicalFile().equals(leaf)
                    || (volumes.isEmpty() && !volume.equals(archive)) || !volumes.add(volume.getCanonicalFile()) || volumes.size() > 1024)
                    throw new IOException("Unsafe/ambiguous local RAR volume path");
                details = false; continue;
            }
            if (line.startsWith("Details: ") && !volumes.isEmpty() && record.isEmpty() && !details) {
                if (!line.matches("Details: RAR (?:1\\.5|5)(?:, (?:solid|volume(?: [0-9]+)?|lock|recovery record|encrypted headers))*"))
                    throw new IOException("Unsupported RAR details");
                details = true; continue;
            }
            Matcher field = FIELD.matcher(line);
            if (!details || !field.matches() || !KEYS.contains(field.group(1)) || record.put(field.group(1), field.group(2)) != null
                || (record.size() == 1 && !field.group(1).equals("Name")))
                throw new IOException("Ambiguous/unsupported RAR technical listing");
        }
        if (!record.isEmpty() || pending != null || !details) throw new IOException("Incomplete RAR listing/volume set");
    }

    private Member member(Map<String, String> r) throws IOException {
        String name = require(r, "Name"), type = require(r, "Type"), attrs = require(r, "Attributes");
        safeText(name);
        if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.length() > 4096)
            throw new IOException("Ambiguous RAR member name");
        boolean directory = type.equals("Directory"), service = type.equals("Service");
        if (!type.equals("File") && !directory && !service) throw new IOException("RAR link/special member rejected");
        if (service && !(name.equals("QO") || name.equals("RR") || name.equals("CMT")))
            throw new IOException("Unsupported RAR service record");
        if (!service) {
            String host = require(r, "Host OS");
            if (host.equals("Unix")) {
                if (!attrs.matches("[-d][rwxstST-]{9}") || directory != attrs.startsWith("d"))
                    throw new IOException("Unsupported RAR Unix type/attributes");
            } else if (Arrays.asList("Windows", "DOS", "OS/2", "WinCE").contains(host)) {
                if (!attrs.matches("[ICADSHR.]{7}") || directory != (attrs.charAt(3) == 'D'))
                    throw new IOException("Unsupported RAR DOS type/attributes");
            } else throw new IOException("Unsupported RAR host type");
        }
        long size = directory ? 0 : number(require(r, "Size"));
        if (directory && r.containsKey("Size") || size > options.maxEntryBytes)
            throw new IOException("RAR entry exceeds configured limit or invalid directory size");
        if (!directory) {
            number(require(r, "Packed size"));
            if (!require(r, "Ratio").matches("[0-9]+%|-->|<--|<->")) throw new IOException("Invalid RAR split ratio");
        }
        Matcher compression = COMPRESSION.matcher(require(r, "Compression"));
        if (!compression.matches()) throw new IOException("Unsupported RAR compression description");
        if (compression.group(1) != null) {
            long unit = compression.group(2).equals("k") ? 1 : compression.group(2).equals("m") ? 1024 : 1048576;
            if (number(compression.group(1)) > dictionaryKiB / unit) throw new IOException("RAR dictionary exceeds memory limit");
        }
        String flags = r.containsKey("Flags") ? r.get("Flags").trim() : "";
        if (!flags.matches("(?:solid(?: encrypted)?|encrypted)?")) throw new IOException("Unsupported RAR flags");
        for (String key : Arrays.asList("CRC32", "CRC32 MAC", "Pack-CRC32", "BLAKE2", "BLAKE2 MAC", "Pack-BLAKE2")) {
            String hash = r.get(key);
            if (hash != null && !hash.matches(key.contains("BLAKE2") ? "[0-9A-Fa-f]{64}" : "[0-9A-Fa-f]{8}"))
                throw new IOException("Invalid RAR checksum metadata");
        }
        long modified = 0;
        for (String key : Arrays.asList("mtime", "ctime", "atime")) if (r.containsKey(key)) {
            String value = r.get(key);
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2},[0-9]{9}"))
                throw new IOException("Invalid RAR timestamp");
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            format.setLenient(false); format.setTimeZone(TimeZone.getTimeZone("UTC"));
            try { long time = format.parse(value.substring(0, 19)).getTime(); if (key.equals("mtime")) modified = time; }
            catch (ParseException e) { throw new IOException("Invalid RAR timestamp", e); }
        }
        return new Member(name, directory, flags.contains("encrypted"), size, modified);
    }
    private static long number(String value) throws IOException {
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw new IOException("Invalid RAR size");
        try { return Long.parseLong(value); } catch (NumberFormatException e) { throw new IOException("RAR size overflow", e); }
    }
    private static String require(Map<String, String> r, String key) throws IOException {
        String s = r.get(key); if (s == null) throw new IOException("Missing RAR metadata field: " + key); return s;
    }
    private static void safeText(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || c == '\ufffd' || (c >= 0x2028 && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069))
                throw new IOException("Control/ambiguous character in RAR metadata");
        }
    }
    private static String line(Reader in) throws IOException {
        StringBuilder s = new StringBuilder(); int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return s.toString();
            if (s.length() >= 8192) throw new IOException("RAR listing line exceeds bound");
            s.append((char)c);
        }
        if (s.length() != 0) throw new IOException("Unterminated RAR listing line");
        return null;
    }
}
