package com.termux.app.files.decompile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.impl.NoOpCodeCache;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.loader.JadxPluginLoader;
import jadx.core.plugins.files.SingleDirFilesGetter;
import jadx.plugins.input.dex.DexInputPlugin;
import jadx.plugins.input.dex.utils.IDexData;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.iface.value.StringEncodedValue;
import org.jf.dexlib2.util.ReferenceUtil;

/** Local, read-only DEX -> Java. All calls belong on a serial worker, never the UI thread. */
public final class DecompilerSession implements Closeable {
    public static final long MAX_DEX_BYTES = 64L * 1024 * 1024;
    public static final int MAX_CLASSES = 100000, RESULT_PAGE_SIZE = 200;
    private static final int MAX_ZIP_ENTRIES = 100000, MAX_DEX_FILES = 256;
    // JADX FileUtils owns a process-wide temporary-root pointer. Do not race load/close/codegen.
    private static final ReentrantLock JADX_LOCK = new ReentrantLock();

    public interface Cancellation { void check(); }
    public interface Progress { void update(int done, int total); }
    public static final Cancellation THREAD_CANCEL = () -> {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Cancelled");
    };
    public enum Search { CLASSES, STRINGS, REFERENCES }

    public static final class Hit {
        public final String className, detail;
        Hit(String className, String detail) { this.className = className; this.detail = detail; }
        @Override public String toString() { return className + (detail.isEmpty() ? "" : "\n" + detail); }
    }
    public static final class Results {
        public final List<Hit> hits;
        public final int offset;
        public final boolean hasMore;
        Results(List<Hit> hits, int offset, boolean more) {
            this.hits = Collections.unmodifiableList(hits); this.offset = offset; this.hasMore = more;
        }
    }
    public static final class Source {
        public final String className;
        public final File file;
        public final int errors, warnings;
        Source(String name, File file, int errors, int warnings) {
            this.className = name; this.file = file; this.errors = errors; this.warnings = warnings;
        }
    }
    private static final class Dex implements IDexData {
        final String name;
        final byte[] bytes;
        final DexBackedDexFile index;
        Dex(String name, byte[] bytes) {
            this.name = name; this.bytes = bytes;
            index = new DexBackedDexFile(Opcodes.getDefault(), bytes);
        }
        public String getFileName() { return name; }
        public byte[] getContent() { return bytes; }
    }
    private static final class Entry {
        final ClassDef cls;
        Entry(ClassDef cls) { this.cls = cls; }
    }

    private final File input, scratch;
    private final List<Dex> dexes = new ArrayList<>();
    private final SortedMap<String, Entry> entries = new TreeMap<>();
    private final SortedMap<String, JavaClass> javaClasses = new TreeMap<>();
    private JadxDecompiler jadx;
    private Source currentSource;
    private boolean closed;

    private DecompilerSession(File input, File scratch) throws IOException {
        this.input = input.getCanonicalFile(); this.scratch = scratch;
    }

    /** cacheParent must be private, app-owned. This session creates and removes its own child. */
    public static DecompilerSession open(File input, File cacheParent, Cancellation cancel) throws IOException {
        cancel.check();
        if (!input.isFile() || !input.canRead()) throw new IOException("Not a readable local APK/DEX file");
        if (!cacheParent.isDirectory()) throw new IOException("Missing private cache directory");
        File scratch = File.createTempFile("dec-session-", "", cacheParent);
        if (!scratch.delete() || !scratch.mkdir()) throw new IOException("Cannot create private decompiler workspace");
        DecompilerSession session = new DecompilerSession(input, scratch);
        try {
            session.readDexes(cancel);
            for (Dex dex : session.dexes) {
                for (ClassDef cls : dex.index.getClasses()) {
                    cancel.check();
                    String name = className(cls.getType());
                    if (session.entries.put(name, new Entry(cls)) != null)
                        throw new IOException("Duplicate class across DEX files: " + name);
                    if (session.entries.size() > MAX_CLASSES) throw new IOException("Class limit is " + MAX_CLASSES);
                }
            }
            if (session.entries.isEmpty()) throw new IOException("No DEX classes found");
            lock(cancel);
            try {
                JadxArgs args = new JadxArgs();
                args.setThreadsCount(1);
                args.setSkipResources(true);
                args.setSkipFilesSave(true);
                args.setInlineAnonymousClasses(false);
                args.setInlineMethods(false);
                args.setMoveInnerClasses(true);
                args.setCodeCache(NoOpCodeCache.INSTANCE);
                args.setFilesGetter(new SingleDirFilesGetter(scratch.toPath()));
                args.setOutDir(new File(scratch, "unused-output"));
                // Explicit in-process input only: no ServiceLoader plugins, GUI, compiler or CLI.
                args.setPluginLoader(new JadxPluginLoader() {
                    public List<JadxPlugin> load() { return Collections.emptyList(); }
                    public void close() {}
                });
                session.jadx = new JadxDecompiler(args);
                DexInputPlugin dexInput = new DexInputPlugin();
                session.jadx.addCustomCodeLoader(dexInput.loadDexData(new ArrayList<IDexData>(session.dexes)));
                session.jadx.load();
                cancel.check();
                for (JavaClass cls : session.jadx.getClassesWithInners()) session.javaClasses.put(cls.getRawName(), cls);
                if (!session.javaClasses.keySet().containsAll(session.entries.keySet()))
                    throw new IOException("JADX did not load every indexed class; input may be unsupported");
            } finally { JADX_LOCK.unlock(); }
            return session;
        } catch (IOException | RuntimeException | Error failure) {
            try { session.close(); } catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private void readDexes(Cancellation cancel) throws IOException {
        byte[] magic = new byte[8];
        try (InputStream in = new FileInputStream(input)) {
            if (in.read(magic) != magic.length) throw new IOException("Input is too short");
        }
        if (magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == '\n') {
            try (InputStream in = new FileInputStream(input)) { addDex(input.getName(), readBounded(in, MAX_DEX_BYTES, cancel)); }
        } else if (magic[0] == 'P' && magic[1] == 'K') {
            long total = 0;
            Set<String> names = new HashSet<>();
            try (ZipFile zip = new ZipFile(input)) {
                Enumeration<? extends ZipEntry> all = zip.entries();
                int count = 0;
                while (all.hasMoreElements()) {
                    cancel.check();
                    ZipEntry entry = all.nextElement();
                    if (++count > MAX_ZIP_ENTRIES) throw new IOException("APK ZIP entry limit exceeded");
                    String name = entry.getName();
                    if (!name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) continue;
                    if (!names.add(name)) throw new IOException("Duplicate APK DEX entry: " + name);
                    if (dexes.size() >= MAX_DEX_FILES) throw new IOException("APK DEX count limit exceeded");
                    if (entry.getSize() > MAX_DEX_BYTES - total) throw new IOException("Expanded DEX limit is 64 MiB total");
                    byte[] bytes;
                    try (InputStream in = zip.getInputStream(entry)) { bytes = readBounded(in, MAX_DEX_BYTES - total, cancel); }
                    CRC32 crc = new CRC32(); crc.update(bytes);
                    if (entry.getCrc() != crc.getValue()) throw new IOException("Bad APK DEX CRC: " + name);
                    addDex(name, bytes); total += bytes.length;
                }
            }
        } else throw new IOException("Expected standard DEX or APK ZIP; compact DEX/ODEX/VDEX are unsupported");
        if (dexes.isEmpty()) throw new IOException("No root classes*.dex found in APK");
    }

    private void addDex(String name, byte[] bytes) throws IOException {
        if (bytes.length < 112 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' || bytes[3] != '\n')
            throw new IOException("Invalid standard DEX: " + name);
        // The indexer is dexlib2 2.5.2, so reject newer containers rather than partially index them.
        String version = new String(bytes, 4, 3, StandardCharsets.US_ASCII);
        if (!Arrays.asList("035", "037", "038", "039").contains(version) || bytes[7] != 0)
            throw new IOException("Supported DEX versions: 035/037/038/039; found " + version);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(32) != bytes.length || header.getInt(36) != 112 || header.getInt(40) != 0x12345678)
            throw new IOException("Invalid DEX length/header/endianness: " + name);
        Adler32 adler = new Adler32(); adler.update(bytes, 12, bytes.length - 12);
        if ((int) adler.getValue() != header.getInt(8)) throw new IOException("Bad DEX Adler32 checksum: " + name);
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1"); sha1.update(bytes, 32, bytes.length - 32);
            if (!MessageDigest.isEqual(sha1.digest(), Arrays.copyOfRange(bytes, 12, 32)))
                throw new IOException("Bad DEX SHA-1 signature: " + name);
        } catch (NoSuchAlgorithmException e) { throw new IOException("SHA-1 provider unavailable", e); }
        dexes.add(new Dex(name, bytes));
    }

    public int classCount() { return entries.size(); }
    public int dexCount() { return dexes.size(); }

    /** Case-insensitive literal search. Each page rescans the immutable index; no unbounded hit list. */
    public Results search(Search mode, String query, int offset, Cancellation cancel) throws IOException {
        ensureOpen(); cancel.check();
        if (offset < 0) throw new IllegalArgumentException("Negative search offset");
        if (query.length() > 1024) throw new IOException("Search query limit is 1024 characters");
        if (mode != Search.CLASSES && query.isEmpty()) throw new IOException("Enter a string or reference to search");
        Collector out = new Collector(offset, query.toLowerCase(Locale.ROOT), cancel);
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            cancel.check();
            String name = item.getKey(); ClassDef cls = item.getValue().cls;
            if (mode == Search.CLASSES) { if (out.add(name, "", name + " " + cls.getType())) break; continue; }
            if (mode == Search.REFERENCES) {
                if (out.add(name, "extends " + cls.getSuperclass(), cls.getSuperclass())) break;
                for (String type : cls.getInterfaces()) if (out.add(name, "implements " + type, type)) break;
            }
            for (Field field : cls.getFields()) {
                cancel.check();
                if (mode == Search.STRINGS && field.getInitialValue() instanceof StringEncodedValue) {
                    String value = ((StringEncodedValue) field.getInitialValue()).getValue();
                    out.add(name, field.getName() + " = " + preview(value), value);
                } else if (mode == Search.REFERENCES) out.add(name, field.getName() + ": " + field.getType(), field.getType());
                if (out.full()) break;
            }
            if (out.full()) break;
            for (Method method : cls.getMethods()) {
                cancel.check();
                if (mode == Search.REFERENCES) {
                    String proto = ReferenceUtil.getMethodDescriptor(method);
                    // Only signature types here; a method declaration is not a call to itself.
                    String types = method.getParameterTypes().toString() + method.getReturnType();
                    out.add(name, "signature " + proto, types);
                }
                MethodImplementation body = method.getImplementation();
                if (body != null) {
                    int address = 0;
                    for (Instruction insn : body.getInstructions()) {
                        cancel.check();
                        if (insn instanceof ReferenceInstruction) {
                            Reference ref = ((ReferenceInstruction) insn).getReference();
                            reference(out, mode, name, method.getName(), address, ref);
                            if (insn instanceof DualReferenceInstruction)
                                reference(out, mode, name, method.getName(), address, ((DualReferenceInstruction) insn).getReference2());
                        }
                        if (out.full()) break;
                        address += insn.getCodeUnits();
                    }
                }
                if (out.full()) break;
            }
            if (out.full()) break;
        }
        cancel.check();
        boolean more = out.hits.size() > RESULT_PAGE_SIZE;
        if (more) out.hits.remove(out.hits.size() - 1);
        return new Results(out.hits, offset, more);
    }

    private static void reference(Collector out, Search mode, String cls, String method, int address, Reference ref) {
        boolean string = ref instanceof StringReference;
        if ((mode == Search.STRINGS) != string) return;
        String value = string ? ((StringReference) ref).getString() : ReferenceUtil.getReferenceString(ref);
        out.add(cls, method + " @" + Integer.toHexString(address) + ": " + preview(value), value);
    }
    private static String preview(String value) {
        if (value == null) return "";
        String text = value.substring(0, Math.min(value.length(), 400)).replace('\n', ' ').replace('\r', ' ');
        return text + (value.length() > 400 ? " … [open class for full value]" : "");
    }
    private static final class Collector {
        final List<Hit> hits = new ArrayList<>();
        final int offset;
        final String query;
        final Cancellation cancel;
        long matched;
        Collector(int offset, String query, Cancellation cancel) { this.offset = offset; this.query = query; this.cancel = cancel; }
        boolean add(String name, String detail, String searchable) {
            cancel.check();
            if (full()) return true;
            if (searchable != null && searchable.toLowerCase(Locale.ROOT).contains(query) && matched++ >= offset)
                hits.add(new Hit(name, preview(detail)));
            return full();
        }
        boolean full() { return hits.size() > RESULT_PAGE_SIZE; }
    }

    public Source decompile(String className, Cancellation cancel) throws IOException {
        ensureOpen(); lock(cancel);
        try {
            currentSource = null;
            JavaClass cls = findClass(className).getTopParentClass();
            File source = new File(scratch, "current.java");
            writeCode(cls, source, cancel);
            currentSource = new Source(cls.getFullName(), source, jadx.getErrorsCount(), jadx.getWarnsCount());
            return currentSource;
        } finally { JADX_LOCK.unlock(); }
    }

    private JavaClass findClass(String name) throws IOException {
        JavaClass cls = javaClasses.get(name);
        if (cls == null) throw new IOException("Class not found: " + name);
        return cls;
    }
    private void writeCode(JavaClass cls, File target, Cancellation cancel) throws IOException {
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
            String code = cls.getCode();
            cancel.check();
            if (code.isEmpty()) throw new IOException("JADX produced no Java source for " + cls.getFullName());
            for (int i = 0; i < code.length(); i += 8192) {
                cancel.check(); writer.write(code, i, Math.min(8192, code.length() - i));
            }
        } finally { cls.unload(); }
        cancel.check();
    }

    /** Export complete top-level sources as ZIP. Output must be new, with an existing writable parent. */
    public int exportAll(File output, Cancellation cancel, Progress progress) throws IOException {
        ensureOpen(); lock(cancel);
        boolean created = false, success = false;
        try {
            int done = 0;
            Set<String> names = new HashSet<>();
            List<JavaClass> classes = jadx.getClasses();
            OutputStream reserved = reserve(output); created = true;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(reserved))) {
                for (JavaClass cls : classes) {
                    cancel.check();
                    String path = safeSourcePath(cls.getFullName());
                    if (!names.add(path)) throw new IOException("Export source name collision: " + path);
                    File source = new File(scratch, "export.java");
                    try {
                        writeCode(cls, source, cancel);
                        zip.putNextEntry(new ZipEntry(path));
                        try (InputStream in = new FileInputStream(source)) { copy(in, zip, cancel); }
                        zip.closeEntry();
                    } finally { source.delete(); }
                    progress.update(++done, classes.size());
                }
            }
            cancel.check(); success = true; return done;
        } finally {
            if (created && !success) output.delete();
            JADX_LOCK.unlock();
        }
    }

    public void exportSource(Source source, File output, Cancellation cancel) throws IOException {
        ensureOpen();
        if (source == null || source != currentSource) throw new IOException("Source is no longer current; open the class again");
        exportFile(source.file, output, cancel);
    }
    private void exportFile(File source, File output, Cancellation cancel) throws IOException {
        cancel.check(); OutputStream reserved = reserve(output);
        boolean success = false;
        try {
            try (OutputStream out = reserved; InputStream in = new FileInputStream(source)) { copy(in, out, cancel); }
            cancel.check(); success = true;
        } finally { if (!success) output.delete(); }
    }
    public void exportSelection(String selection, File output, Cancellation cancel) throws IOException {
        ensureOpen(); cancel.check();
        if (selection.isEmpty()) throw new IOException("Select source text first");
        OutputStream reserved = reserve(output); boolean success = false;
        try {
            try (Writer out = new OutputStreamWriter(reserved, StandardCharsets.UTF_8)) { out.write(selection); }
            cancel.check(); success = true;
        } finally { if (!success) output.delete(); }
    }
    private OutputStream reserve(File output) throws IOException {
        if (!output.isAbsolute()) throw new IOException("Enter an absolute app-writable output path");
        File parent = output.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("Output parent directory must exist");
        File canonical = output.getCanonicalFile();
        if (canonical.equals(input) || canonical.toPath().startsWith(scratch.getCanonicalFile().toPath()))
            throw new IOException("Output must be a new file; existing files and the original are never overwritten");
        // CREATE_NEW checks and opens atomically: never follow/clobber an existing file or symlink.
        return Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    private static String safeSourcePath(String name) throws IOException {
        String[] parts = name.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0)))
                throw new IOException("Unsafe generated source name: " + name);
            for (int i = 0; i < part.length();) {
                int cp = part.codePointAt(i);
                if (!Character.isJavaIdentifierPart(cp) || Character.isISOControl(cp)) throw new IOException("Unsafe generated source name: " + name);
                i += Character.charCount(cp);
            }
        }
        return name.replace('.', '/') + ".java";
    }
    private static byte[] readBounded(InputStream in, long limit, Cancellation cancel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int n;
        while ((n = in.read(block)) != -1) {
            cancel.check();
            if ((long) out.size() + n > limit) throw new IOException("Expanded DEX limit is 64 MiB total");
            out.write(block, 0, n);
        }
        return out.toByteArray();
    }
    private static void copy(InputStream in, OutputStream out, Cancellation cancel) throws IOException {
        byte[] block = new byte[8192]; int n;
        while ((n = in.read(block)) != -1) { cancel.check(); out.write(block, 0, n); }
    }
    private static String className(String descriptor) { return descriptor.substring(1, descriptor.length() - 1).replace('/', '.'); }
    private static void lock(Cancellation cancel) {
        cancel.check();
        try { JADX_LOCK.lockInterruptibly(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("Cancelled while waiting for JADX"); }
    }
    private void ensureOpen() throws IOException { if (closed) throw new IOException("Decompiler session is closed; reopen the file"); }
    @Override public void close() {
        JADX_LOCK.lock();
        try {
            if (closed) return;
            closed = true;
            try { if (jadx != null) jadx.close(); }
            finally { jadx = null; currentSource = null; entries.clear(); javaClasses.clear(); dexes.clear(); removeTree(scratch); }
        } finally { JADX_LOCK.unlock(); }
    }
    private static void removeTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeTree(child);
        file.delete();
    }
}
