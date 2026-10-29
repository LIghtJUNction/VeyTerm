package com.termux.app.files.archive;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

import java.io.*;
import java.util.*;

/** Local-file archive engine. All calls are blocking; run on a worker thread.
 * No method follows archive links or changes an archive in place. See dependency document.
 */
public final class ArchiveTools {
    private ArchiveTools() {}

    public enum Format {
        ZIP(true), TAR(true), TAR_GZIP(true), TAR_XZ(true), GZIP(true), SEVEN_Z(true), RAR(false);
        public final boolean writable;
        Format(boolean writable) { this.writable = writable; }
    }
    public enum Encryption { NONE, AES_256, ZIP_STANDARD }

    /** Options are snapshotted at call entry; the caller may wipe its password afterwards. */
    public static final class Options {
        public char[] password;
        public Encryption encryption = Encryption.NONE;
        public long maxEntryBytes = 4L * 1024 * 1024 * 1024;
        public long maxTotalBytes = 16L * 1024 * 1024 * 1024;
        public int maxEntries = 100000;
        public int maxMemoryKiB = 65536;
        public boolean replaceExisting = false;
        private Options snapshot() throws IOException {
            if (encryption == null || maxEntryBytes < 0 || maxTotalBytes < 0 || maxEntries < 1 || maxMemoryKiB < 1024)
                throw new IOException("Invalid archive options or limits");
            Options o = new Options();
            o.password = password == null ? null : password.clone();
            o.encryption = encryption;
            o.maxEntryBytes = maxEntryBytes; o.maxTotalBytes = maxTotalBytes;
            o.maxEntries = maxEntries; o.maxMemoryKiB = maxMemoryKiB;
            o.replaceExisting = replaceExisting;
            return o;
        }
        private void wipe() { if (password != null) Arrays.fill(password, '\0'); }
    }

    public interface Progress {
        /** Cumulative uncompressed bytes in this phase; total is -1 when unknown. */
        void update(String phase, String entry, long bytes, long total) throws IOException;
    }
    public static final class Job {
        private volatile boolean cancelled;
        private final Progress progress;
        public Job() { this(null); }
        public Job(Progress progress) { this.progress = progress; }
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
        private void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Archive operation cancelled");
        }
        private void report(String phase, String entry, long bytes) throws IOException {
            check();
            if (progress != null) progress.update(phase, entry, bytes, -1);
            check();
        }
    }

    public static final class Entry {
        public final String name;
        public final boolean directory;
        /** -1 for an unknown uncompressed size (plain gzip). */
        public final long size, modified;
        public final boolean encrypted;
        private Entry(String name, boolean directory, long size, long modified, boolean encrypted) {
            this.name = name; this.directory = directory; this.size = size;
            this.modified = modified; this.encrypted = encrypted;
        }
        private Entry renamed(String name) { return new Entry(name, directory, size, modified, encrypted); }
    }

    /** Explicit source member; directories are empty entries, not implicitly recursive. */
    public static final class Source {
        public final File file;
        public final String name;
        public Source(File file, String name) { this.file = Objects.requireNonNull(file); this.name = name; }
    }

    /** Detects by signature, including tar inside gzip/xz, never by filename extension. */
    public static Format detect(File file) throws IOException {
        byte[] header = new byte[512]; int n;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) { n = readHeader(in, header); }
        if (n >= 4 && header[0] == 'P' && header[1] == 'K'
            && ((header[2] == 3 && header[3] == 4) || (header[2] == 5 && header[3] == 6))) return Format.ZIP;
        if (n >= 6 && header[0] == 0x37 && header[1] == 0x7a && header[2] == (byte) 0xbc
            && header[3] == (byte) 0xaf && header[4] == 0x27 && header[5] == 0x1c) return Format.SEVEN_Z;
        if (n >= 7 && header[0] == 'R' && header[1] == 'a' && header[2] == 'r'
            && header[3] == '!' && header[4] == 0x1a && header[5] == 7) return Format.RAR;
        if (n >= 2 && header[0] == (byte) 0x1f && header[1] == (byte) 0x8b) {
            try (InputStream in = GzipCompressorInputStream.builder().setFile(file).get()) {
                return tarHeader(header, readHeader(in, header)) ? Format.TAR_GZIP : Format.GZIP;
            }
        }
        if (n >= 6 && header[0] == (byte) 0xfd && header[1] == '7' && header[2] == 'z'
            && header[3] == 'X' && header[4] == 'Z' && header[5] == 0) {
            try (InputStream in = new XZInputStream(new BufferedInputStream(new FileInputStream(file)), 65536)) {
                if (tarHeader(header, readHeader(in, header))) return Format.TAR_XZ;
                throw new IOException("Only tar-containing XZ streams are supported");
            }
        }
        if (tarHeader(header, n)) return Format.TAR;
        throw new IOException("Unknown or damaged archive signature");
    }

    public static List<Entry> list(File archive, Options options, Job job) throws IOException {
        Options o = options(options); Job j = job(job);
        try {
            List<Entry> entries = new ArrayList<>();
            walk(archive, detect(archive), o, j, (e, data) -> entries.add(e));
            return Collections.unmodifiableList(entries);
        } finally { o.wipe(); }
    }

    /** Reads every file fully, verifying codec/ZIP/RAR checksums. TAR has no payload checksum. */
    public static void test(File archive, Options options, Job job) throws IOException {
        Options o = options(options); Job j = job(job);
        try { verify(archive, detect(archive), o, j); } finally { o.wipe(); }
    }

    /** Null selection means all; empty selection means none. Directory selections include descendants.
     * Each extracted file is staged beside its target before replacement; failures can leave completed
     * files/directories, but never a partially overwritten existing file. No archive metadata permissions
     * or links are applied. Destination must be private from concurrent filesystem mutation.
     */
    public static void extract(File archive, File destination, Set<String> selected,
                               Options options, Job job) throws IOException {
        Options o = options(options); Job j = job(job);
        try {
            Set<String> selection = selection(selected);
            // Preflight every name before creating anything, including unselected unsafe members.
            List<Entry> entries = new ArrayList<>();
            Format format = detect(archive);
            walk(archive, format, o, j, (e, d) -> entries.add(e));
            requireSelections(entries, selection);
            rejectSourceLink(destination);
            File root = destination.getCanonicalFile();
            File archivePath = archive.getCanonicalFile();
            if (destination.exists() && !destination.isDirectory()) throw new IOException("Destination is not a directory");
            for (Entry e : entries) if (matches(e.name, selection)) {
                File target = safeTarget(root, e.name);
                if (target.equals(archivePath)) throw new IOException("Cannot extract over the source archive");
                if (target.exists() && !(e.directory && target.isDirectory()) && !o.replaceExisting)
                    throw new IOException("Destination exists: " + e.name);
                if (target.exists() && target.isDirectory() != e.directory)
                    throw new IOException("Destination type conflict: " + e.name);
            }
            mkdir(root);
            Meter meter = new Meter(o, j, "extract");
            walk(archive, format, o, j, (e, data) -> {
                if (!matches(e.name, selection)) return;
                File target = safeTarget(root, e.name);
                if (e.directory) { mkdir(target); return; }
                mkdir(target.getParentFile());
                safeTarget(root, e.name);
                if (target.exists() && !o.replaceExisting) throw new IOException("Destination exists: " + e.name);
                File staged = File.createTempFile(".vey-extract-", ".tmp", target.getParentFile());
                try {
                    try (FileOutputStream out = new FileOutputStream(staged)) { meter.transfer(e, data, out); out.getFD().sync(); }
                    j.check(); safeTarget(root, e.name);
                    if (target.exists() && !o.replaceExisting) throw new IOException("Destination exists: " + e.name);
                    atomicReplace(staged, target);
                } finally { removeTemp(staged); }
            });
        } finally { o.wipe(); }
    }

    public static void create(File archive, Format format, List<Source> sources,
                              Options options, Job job) throws IOException {
        rewrite(archive, format, false, sources, null, null, options, job);
    }
    public static void addReplace(File archive, List<Source> sources, Options options, Job job) throws IOException {
        rewrite(archive, detect(archive), true, sources, null, null, options, job);
    }
    public static void delete(File archive, Set<String> names, Options options, Job job) throws IOException {
        if (names == null || names.isEmpty()) throw new IOException("No entries selected for deletion");
        rewrite(archive, detect(archive), true, Collections.emptyList(), selection(names), null, options, job);
    }
    /** Renames a member or directory subtree, rejecting collisions with any surviving member. */
    public static void rename(File archive, String from, String to, Options options, Job job) throws IOException {
        String f = checkedName(from), t = checkedName(to);
        Map<String, String> rename = Collections.singletonMap(stripSlash(f), stripSlash(t));
        rewrite(archive, detect(archive), true, Collections.emptyList(), null, rename, options, job);
    }

    private interface Data { void copy(OutputStream out) throws IOException; }
    private interface Visitor { void visit(Entry entry, Data data) throws IOException; }
    private interface Writer extends Closeable { void put(Entry entry, Data data, Meter meter) throws IOException; }

    private static void rewrite(File archive, Format format, boolean existing, List<Source> sources,
                                Set<String> deleted, Map<String, String> renamed,
                                Options options, Job job) throws IOException {
        Options o = options(options); Job j = job(job); File staged = null;
        try {
            if (!format.writable) throw new IOException("RAR is read-only; no RAR writer is available");
            checkEncryption(format, o);
            rejectSourceLink(archive);
            if (!existing && archive.exists() && !o.replaceExisting) throw new IOException("Archive already exists");
            File target = archive.getAbsoluteFile();
            if (target.isDirectory()) throw new IOException("Archive target is a directory");
            LinkedHashMap<String, Source> added = new LinkedHashMap<>();
            Map<String, Entry> output = new LinkedHashMap<>();
            if (sources == null) throw new IOException("Sources must not be null");
            for (Source s : sources) {
                rejectSourceLink(s.file);
                if (!s.file.exists() || !(s.file.isFile() || s.file.isDirectory())) throw new IOException("Unsupported source: " + s.file);
                if (s.file.getCanonicalFile().equals(target.getCanonicalFile())) throw new IOException("Cannot add archive to itself");
                String name = checkedName(s.name);
                if (s.file.isDirectory() && !name.endsWith("/")) name += "/";
                if (!s.file.isDirectory() && name.endsWith("/")) throw new IOException("File name ends with slash");
                if (added.put(stripSlash(name), new Source(s.file, name)) != null) throw new IOException("Duplicate source: " + name);
            }
            List<Entry> original = new ArrayList<>();
            if (existing) walk(archive, format, o, j, (e, d) -> original.add(e));
            requireSelections(original, deleted);
            if (renamed != null) requireSelections(original, renamed.keySet());
            for (Entry e : original) {
                if (matches(e.name, deleted) && deleted != null) continue;
                if (added.containsKey(stripSlash(e.name))) continue;
                addUnique(output, mapped(e, renamed), o);
            }
            for (Source s : added.values()) addUnique(output, sourceEntry(s), o);
            validateTree(output.values());
            if (format == Format.GZIP && (output.size() != 1 || output.values().iterator().next().directory))
                throw new IOException("GZIP requires exactly one regular file");
            if (format == Format.GZIP && output.values().iterator().next().name.contains("/"))
                throw new IOException("GZIP member must be a basename");
            boolean encrypted = output.values().stream().anyMatch(e -> e.encrypted);
            if (encrypted && (o.password == null || o.password.length == 0)) throw new IOException("Password required to rewrite encrypted archive");
            // 7z has one output encryption policy; never silently decrypt retained encrypted files.
            if (format == Format.SEVEN_Z && encrypted) o.encryption = Encryption.AES_256;
            File parent = target.getParentFile();
            if (!parent.isDirectory()) throw new IOException("Archive parent does not exist");
            staged = File.createTempFile(".vey-archive-", ".tmp", parent);
            Meter meter = new Meter(o, j, "write");
            try (Writer writer = writer(staged, format, o)) {
                if (existing) walk(archive, format, o, j, (e, data) -> {
                    if (deleted != null && matches(e.name, deleted)) {
                        meter.transfer(e, data, DISCARD); return;
                    }
                    if (added.containsKey(stripSlash(e.name))) {
                        meter.transfer(e, data, DISCARD); return;
                    }
                    writer.put(mapped(e, renamed), data, meter);
                });
                for (Source s : added.values()) {
                    j.check(); rejectSourceLink(s.file);
                    writer.put(sourceEntry(s), out -> {
                        if (!s.file.isDirectory()) try (InputStream in = new BufferedInputStream(new FileInputStream(s.file))) { copy(in, out); }
                    }, meter);
                }
            }
            // Verify using the explicit format: the staging filename has no meaningful suffix.
            verify(staged, format, o, j);
            try (RandomAccessFile sync = new RandomAccessFile(staged, "rw")) { sync.getFD().sync(); }
            j.check(); rejectSourceLink(target);
            if (!existing && target.exists() && !o.replaceExisting) throw new IOException("Archive already exists");
            atomicReplace(staged, target);
        } finally { if (staged != null) removeTemp(staged); o.wipe(); }
    }

    private static Entry sourceEntry(Source s) {
        return new Entry(s.name, s.file.isDirectory(), s.file.isDirectory() ? 0 : s.file.length(), s.file.lastModified(), false);
    }
    private static Entry mapped(Entry e, Map<String, String> rename) {
        if (rename == null) return e;
        Map.Entry<String, String> r = rename.entrySet().iterator().next();
        String name = stripSlash(e.name), from = r.getKey();
        if (name.equals(from) || name.startsWith(from + "/"))
            return e.renamed(r.getValue() + name.substring(from.length()) + (e.directory ? "/" : ""));
        return e;
    }
    private static void checkEncryption(Format f, Options o) throws IOException {
        if (o.encryption != Encryption.NONE) {
            if (o.password == null || o.password.length == 0) throw new IOException("Encryption requires a password");
            if (f != Format.ZIP && !(f == Format.SEVEN_Z && o.encryption == Encryption.AES_256))
                throw new IOException("Requested encryption is unsupported for " + f);
        }
    }

    private static void walk(File file, Format format, Options o, Job j, Visitor visitor) throws IOException {
        Map<String, Entry> seen = new LinkedHashMap<>();
        Visitor checked = (e, d) -> {
            j.check(); checkedName(e.name);
            if (e.directory && e.size != 0) throw new IOException("Directory member has nonzero size: " + e.name);
            if (!e.directory && e.name.endsWith("/")) throw new IOException("Invalid file member: " + e.name);
            if (e.size < -1 || e.size > o.maxEntryBytes) throw new IOException("Entry exceeds configured size limit: " + e.name);
            addUnique(seen, e, o);
            j.report("scan", e.name, 0);
            visitor.visit(e, d);
        };
        if (format == Format.ZIP) {
            try (ZipFile zip = new ZipFile(file, o.password)) {
                if (zip.isSplitArchive()) throw new IOException("Split ZIP archives are unsupported");
                if (!zip.isValidZipFile()) throw new IOException("Invalid ZIP archive");
                for (FileHeader h : zip.getFileHeaders()) {
                    byte[] attr = h.getExternalFileAttributes();
                    int mode = attr == null || attr.length < 4 ? 0 : ((attr[3] & 255) << 8) | (attr[2] & 255);
                    rejectMode(mode, h.getFileName());
                    Entry e = new Entry(h.getFileName(), h.isDirectory(), h.getUncompressedSize(),
                        h.getLastModifiedTimeEpoch(), h.isEncrypted());
                    checked.visit(e, out -> {
                        if (!h.isDirectory()) try (InputStream in = zip.getInputStream(h)) { copy(in, out); }
                    });
                }
            }
        } else if (format == Format.SEVEN_Z) {
            try (SevenZFile seven = SevenZFile.builder().setFile(file).setPassword(o.password)
                .setMaxMemoryLimitKiB(o.maxMemoryKiB).get()) {
                SevenZArchiveEntry h;
                while ((h = seven.getNextEntry()) != null) {
                    if (h.isAntiItem()) throw new IOException("7z anti-items are unsupported");
                    if (h.getHasWindowsAttributes()) {
                        int attrs = h.getWindowsAttributes();
                        if ((attrs & 0x400) != 0) throw new IOException("7z reparse/link member rejected");
                        if ((attrs & 0x8000) != 0) rejectMode(attrs >>> 16, h.getName());
                    }
                    boolean encrypted = false;
                    if (h.getContentMethods() != null) for (SevenZMethodConfiguration method : h.getContentMethods())
                        if (method.getMethod() == SevenZMethod.AES256SHA256) encrypted = true;
                    Entry e = new Entry(h.getName(), h.isDirectory(), h.getSize(),
                        h.getHasLastModifiedDate() ? h.getLastModifiedDate().getTime() : 0, encrypted);
                    checked.visit(e, out -> {
                        byte[] b = new byte[32768]; int n;
                        while ((n = seven.read(b)) != -1) out.write(b, 0, n);
                    });
                }
            }
        } else if (format == Format.RAR) {
            try (NativeRar rar = new NativeRar(file, o.maxMemoryKiB, j::check)) {
                NativeRar.Member h;
                while ((h = rar.next()) != null) {
                    if (h.link || h.special) throw new IOException("RAR link/special member rejected: " + h.name);
                    Entry e = new Entry(h.name, h.directory, h.size, h.modified, h.encrypted);
                    checked.visit(e, out -> {
                        if (e.encrypted) throw new IOException("Encrypted RAR data is unsupported by libarchive");
                        if (!e.directory) rar.copy(out);
                    });
                }
            }
        } else {
            try (InputStream raw = new BufferedInputStream(new FileInputStream(file));
                 InputStream decoded = decode(raw, format, o)) {
                if (format == Format.GZIP) {
                    GzipCompressorInputStream gzip = (GzipCompressorInputStream) decoded;
                    String name = gzip.getMetaData().getFileName();
                    if (name == null || name.isEmpty()) {
                        name = file.getName();
                        if (name.toLowerCase(Locale.ROOT).endsWith(".gz")) name = name.substring(0, name.length() - 3);
                        if (name.isEmpty()) name = "content";
                    }
                    Entry e = new Entry(name, false, -1, 0, false);
                    checked.visit(e, out -> copy(decoded, out));
                } else {
                    try (TarArchiveInputStream tar = new TarArchiveInputStream(decoded)) {
                        TarArchiveEntry h;
                        while ((h = tar.getNextEntry()) != null) {
                            if (!h.isCheckSumOK()) throw new IOException("Invalid TAR header checksum");
                            if (h.isSymbolicLink() || h.isLink() || h.isSparse()
                                || (h.getLinkFlag() != 0 && h.getLinkFlag() != '0' && h.getLinkFlag() != '5'))
                                throw new IOException("TAR links/special members are unsupported: " + h.getName());
                            if (!tar.canReadEntryData(h)) throw new IOException("Unsupported TAR entry encoding");
                            Entry e = new Entry(h.getName(), h.isDirectory(), h.getSize(), h.getModTime().getTime(), false);
                            checked.visit(e, out -> copy(tar, out));
                        }
                        // Read compressor trailers too, so truncated gzip/xz wrappers cannot pass TAR test.
                        if (format != Format.TAR) { byte[] b = new byte[32768]; while (decoded.read(b) != -1) j.check(); }
                    }
                }
            }
        }
        validateTree(seen.values());
    }

    private static InputStream decode(InputStream in, Format format, Options o) throws IOException {
        if (format == Format.GZIP || format == Format.TAR_GZIP)
            return GzipCompressorInputStream.builder().setInputStream(in).setDecompressConcatenated(true).get();
        if (format == Format.TAR_XZ) return new XZInputStream(in, o.maxMemoryKiB);
        return in;
    }

    private static Writer writer(File file, Format format, Options o) throws IOException {
        if (format == Format.ZIP) {
            ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)), o.password);
            return new Writer() {
                public void put(Entry e, Data data, Meter meter) throws IOException {
                    ZipParameters p = new ZipParameters();
                    p.setFileNameInZip(e.name); p.setEntrySize(e.size);
                    if (e.modified > 0) p.setLastModifiedFileTime(e.modified);
                    boolean encrypt = !e.directory && (e.encrypted || o.encryption != Encryption.NONE);
                    p.setEncryptFiles(encrypt);
                    if (encrypt) {
                        p.setEncryptionMethod(o.encryption == Encryption.ZIP_STANDARD ? EncryptionMethod.ZIP_STANDARD : EncryptionMethod.AES);
                        p.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
                    }
                    zip.putNextEntry(p); meter.transfer(e, data, zip); zip.closeEntry();
                }
                public void close() throws IOException { zip.close(); }
            };
        }
        if (format == Format.SEVEN_Z) {
            SevenZOutputFile seven = o.encryption == Encryption.NONE ? new SevenZOutputFile(file) : new SevenZOutputFile(file, o.password);
            // Small bounded dictionary also keeps concurrent Android writers practical.
            LZMA2Options lzma = new LZMA2Options(1);
            seven.setContentMethods(Collections.singletonList(new SevenZMethodConfiguration(SevenZMethod.LZMA2, lzma)));
            return new Writer() {
                public void put(Entry e, Data data, Meter meter) throws IOException {
                    SevenZArchiveEntry h = new SevenZArchiveEntry(); h.setName(e.name);
                    h.setDirectory(e.directory); h.setSize(e.size);
                    if (e.modified > 0) h.setLastModifiedDate(new Date(e.modified));
                    seven.putArchiveEntry(h);
                    meter.transfer(e, data, new OutputStream() {
                        public void write(int b) throws IOException { seven.write(b); }
                        public void write(byte[] b, int off, int n) throws IOException { seven.write(b, off, n); }
                    });
                    seven.closeArchiveEntry();
                }
                public void close() throws IOException { seven.close(); }
            };
        }
        if (format == Format.GZIP) {
            // Open lazily: gzip headers include the sole member's name.
            return new Writer() {
                private GzipCompressorOutputStream gzip;
                public void put(Entry e, Data data, Meter meter) throws IOException {
                    if (gzip != null) throw new IOException("GZIP has only one member");
                    GzipParameters p = new GzipParameters(); p.setFileName(e.name);
                    gzip = new GzipCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(file)), p);
                    meter.transfer(e, data, gzip);
                }
                public void close() throws IOException { if (gzip != null) gzip.close(); }
            };
        }
        OutputStream raw = new BufferedOutputStream(new FileOutputStream(file));
        OutputStream encoded;
        try {
            encoded = format == Format.TAR_GZIP ? new GzipCompressorOutputStream(raw)
                : format == Format.TAR_XZ ? new XZOutputStream(raw, new LZMA2Options(1)) : raw;
        } catch (IOException | RuntimeException ex) { raw.close(); throw ex; }
        TarArchiveOutputStream tar = new TarArchiveOutputStream(encoded);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        return new Writer() {
            public void put(Entry e, Data data, Meter meter) throws IOException {
                TarArchiveEntry h = new TarArchiveEntry(e.name);
                h.setSize(e.directory ? 0 : e.size); if (e.modified > 0) h.setModTime(e.modified);
                tar.putArchiveEntry(h); meter.transfer(e, data, tar); tar.closeArchiveEntry();
            }
            public void close() throws IOException { tar.close(); }
        };
    }

    private static final OutputStream DISCARD = new OutputStream() {
        public void write(int b) {}
        public void write(byte[] b, int off, int n) {}
    };
    private static void verify(File archive, Format f, Options o, Job j) throws IOException {
        Meter meter = new Meter(o, j, "verify");
        walk(archive, f, o, j, (e, data) -> meter.transfer(e, data, DISCARD));
    }
    private static final class Meter {
        final Options options; final Job job; final String phase; long total;
        Meter(Options o, Job j, String phase) { options = o; job = j; this.phase = phase; }
        void transfer(Entry e, Data data, OutputStream out) throws IOException {
            job.report(phase, e.name, total);
            long[] count = {0};
            data.copy(new OutputStream() {
                public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
                public void write(byte[] b, int off, int len) throws IOException {
                    job.check();
                    if (len > options.maxEntryBytes - count[0] || len > options.maxTotalBytes - total)
                        throw new IOException("Uncompressed data exceeds configured limit: " + e.name);
                    out.write(b, off, len); count[0] += len; total += len;
                    job.report(phase, e.name, total);
                }
            });
            if (e.size >= 0 && count[0] != e.size) throw new IOException("Entry size mismatch: " + e.name);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[32768]; int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }
    private static Options options(Options o) throws IOException { return (o == null ? new Options() : o).snapshot(); }
    private static Job job(Job j) { return j == null ? new Job() : j; }
    private static int readHeader(InputStream in, byte[] b) throws IOException {
        int n = 0, r;
        Arrays.fill(b, (byte) 0);
        while (n < b.length && (r = in.read(b, n, b.length - n)) != -1) n += r;
        return n;
    }
    private static boolean tarHeader(byte[] h, int n) {
        if (n < 512) return false;
        boolean zero = true; for (byte b : h) if (b != 0) { zero = false; break; }
        if (zero) return true;
        try { return new TarArchiveEntry(h).isCheckSumOK(); } catch (IllegalArgumentException ex) { return false; }
    }
    private static String checkedName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
            || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0) throw new IOException("Unsafe archive member: " + name);
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(".") || parts[i].equals("..") || (parts[i].isEmpty() && i != parts.length - 1))
                throw new IOException("Unsafe archive member: " + name);
        }
        if (stripSlash(name).isEmpty()) throw new IOException("Empty archive member");
        return name;
    }
    private static String stripSlash(String name) { return name.endsWith("/") ? name.substring(0, name.length() - 1) : name; }
    private static Set<String> selection(Set<String> names) throws IOException {
        if (names == null) return null;
        Set<String> copy = new LinkedHashSet<>();
        for (String n : names) copy.add(stripSlash(checkedName(n)));
        return copy;
    }
    private static boolean matches(String name, Set<String> selected) {
        if (selected == null) return true;
        String n = stripSlash(name);
        for (String s : selected) if (n.equals(s) || n.startsWith(s + "/")) return true;
        return false;
    }
    private static void requireSelections(List<Entry> entries, Set<String> selected) throws IOException {
        if (selected == null) return;
        for (String n : selected) {
            boolean found = false;
            for (Entry e : entries) if (matches(e.name, Collections.singleton(n))) { found = true; break; }
            if (!found) throw new IOException("Archive member does not exist: " + n);
        }
    }
    private static void addUnique(Map<String, Entry> entries, Entry e, Options o) throws IOException {
        checkedName(e.name);
        if (entries.size() >= o.maxEntries) throw new IOException("Too many archive entries");
        if (entries.put(stripSlash(e.name), e) != null) throw new IOException("Duplicate/colliding member: " + e.name);
    }
    private static void validateTree(Collection<Entry> entries) throws IOException {
        Set<String> files = new HashSet<>();
        for (Entry e : entries) if (!e.directory) files.add(stripSlash(e.name));
        for (Entry e : entries) {
            String name = stripSlash(e.name); int slash;
            while ((slash = name.lastIndexOf('/')) >= 0) {
                name = name.substring(0, slash);
                if (files.contains(name)) throw new IOException("File/directory member conflict: " + name);
            }
        }
    }
    private static void rejectMode(int mode, String name) throws IOException {
        int type = mode & 0170000;
        if (type != 0 && type != 0100000 && type != 0040000)
            throw new IOException("Archive link/special member rejected: " + name);
    }
    private static File safeTarget(File root, String name) throws IOException {
        checkedName(name);
        File cursor = root;
        for (String part : stripSlash(name).split("/")) {
            cursor = new File(cursor, part);
            if (!cursor.getAbsoluteFile().equals(cursor.getCanonicalFile()))
                throw new IOException("Destination contains a symbolic link: " + name);
        }
        String prefix = root.getPath() + File.separator;
        if (!cursor.getCanonicalPath().startsWith(prefix)) throw new IOException("Destination escapes extraction root");
        return cursor;
    }
    private static void rejectSourceLink(File file) throws IOException {
        // Compare leaf against its resolved parent: ancestor symlinks (e.g. Android /sdcard) are OK.
        File absolute = file.getAbsoluteFile();
        File parent = absolute.getParentFile();
        File resolvedLeaf = parent == null ? absolute : new File(parent.getCanonicalFile(), absolute.getName());
        if (!resolvedLeaf.equals(absolute.getCanonicalFile())) throw new IOException("Symbolic source/target rejected: " + file);
    }
    private static void mkdir(File f) throws IOException {
        if (!f.isDirectory() && !f.mkdirs()) throw new IOException("Cannot create directory: " + f);
    }
    private static void atomicReplace(File staged, File target) throws IOException {
        // Android/Linux rename(2), same filesystem. No delete-then-rename or non-atomic fallback.
        if (!staged.renameTo(target)) throw new IOException("Atomic rename failed; original retained: " + target);
    }
    private static void removeTemp(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove temporary archive file: " + file);
    }
}
