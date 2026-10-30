package com.termux.app.files.fs;

import android.content.Context;
import android.content.ContextWrapper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Host suite; also callable by a parent-owned instrumentation runner via run(context, fixtures). */
public final class ArchiveVolumeSmoke {
    private interface Check { void run() throws Exception; }
    private interface Scenario { void run(Env env) throws Exception; }
    private static int passed;
    private static Context context;
    private static File fixtures;

    public static void run(Context suppliedContext, File decodedFixtures) throws Exception {
        context = suppliedContext; fixtures = decodedFixtures; passed = 0;
        test("RAR4 real three-volume part1: bytes, restore, recoverable, read-only commit, cleanup", e -> {
            Node first = e.realRar4("archive.part", 1);
            FileStaging.Stage stage = e.open(first);
            e.assertCopies(stage, 3);
            check(e.staging.recoverable().size() == 1, "one recoverable primary");
            check(e.staging.restore(new File(stage.file.getParentFile(), "archive.part2.rar")) == null, "volume is not an edit lease");
            FileStaging restarted = new FileStaging(e.context, e.fs);
            FileStaging.Stage restored = restarted.restore(stage.file);
            expectIO(() -> restarted.commit(restored, FileOperations.NONE));
            check(e.fs.writes == 0, "RAR commit never writes the backend");
            restarted.discard(restored); e.empty();
        });
        test("RAR5 real eight-volume part01", e -> {
            Node first = null;
            for (int i = 1; i <= 8; i++) {
                String part = String.format(java.util.Locale.ROOT, "%02d", i);
                Node n = e.fs.add("five.part" + part + ".rar", fixture("test_read_format_rar5_multiarchive.part" + part + ".rar"));
                if (first == null) first = n;
            }
            FileStaging.Stage stage = e.open(first); e.assertCopies(stage, 8); e.staging.discard(stage); e.empty();
        });
        test("padded part0001 naming", e -> {
            Node first = e.realRar4("wide.part", 4);
            FileStaging.Stage stage = e.open(first); e.assertCopies(stage, 3); e.staging.discard(stage); e.empty();
        });
        test("literal hidden .part01.rar naming", e -> {
            Node first = e.realRar4(".part", 2);
            FileStaging.Stage stage = e.open(first); e.assertCopies(stage, 3); e.staging.discard(stage); e.empty();
        });
        test("traditional rar+r00 and optional 001 naming (real RAR bytes renamed)", e -> {
            for (String[] names : new String[][]{{"old.rar", "old.r00", "old.r01"}, {"split.001", "split.002", "split.003"}}) {
                e.fs.nodes.clear(); Node first = null;
                for (int i = 0; i < names.length; i++) {
                    Node n = e.fs.add(names[i], fixture("test_rar_multivolume_single_file.part" + (i + 1) + ".rar"));
                    if (first == null) first = n;
                }
                FileStaging.Stage stage = e.open(first); e.assertCopies(stage, 3); e.staging.discard(stage); e.empty();
            }
        });
        test("local borrowed unchanged and no backend access", e -> {
            File local = new File(e.cache, "local.part1.rar"); write(local, fixture("test_rar_multivolume_single_file.part1.rar"));
            FileStaging.Stage stage = e.staging.openArchive(FileLocation.local(local), FileOperations.NONE);
            check(!stage.owned && stage.file.equals(local), "borrowed stage");
            e.staging.discard(stage); check(local.isFile() && e.fs.reads == 0 && e.fs.lists == 0, "borrowed intact");
        });
        test("non-RAR named part1.rar does not list/stage siblings", e -> {
            byte[] zip = new byte[22]; zip[0] = 'P'; zip[1] = 'K'; zip[2] = 5; zip[3] = 6;
            Node first = e.fs.add("fake.part1.rar", zip); e.fs.add("fake.part2.rar", zip);
            FileStaging.Stage stage = e.open(first);
            check(e.fs.lists == 0 && stage.file.getParentFile().list().length == 1, "signature controls staging");
            e.staging.discard(stage); e.empty();
        });
        test("unrecognized content cleans the ordinary lease", e -> {
            Node first = e.fs.add("bad.part1.rar", new byte[]{1, 2, 3});
            expectIO(() -> e.open(first)); e.empty();
        });
        test("ordinary open stays single-file and legacy metadata restores", e -> {
            Node first = e.realRar4("normal.part", 1);
            FileStaging.Stage stage = e.staging.open(first.location, FileOperations.NONE);
            check(e.fs.lists == 0 && stage.file.getParentFile().list().length == 1, "ordinary open unchanged");
            check(!new JSONObject(readText(e.metadata(stage))).has("volumes"), "legacy manifest shape");
            e.staging.discard(e.staging.restore(stage.file)); e.empty();
        });
        test("non-first part2 does not stage a part2.r00 family", e -> {
            Node n = e.fs.add("later.part2.rar", fixture("test_rar_multivolume_single_file.part2.rar"));
            e.fs.add("later.part2.r00", n.bytes);
            FileStaging.Stage s = e.open(n); check(e.fs.lists == 0, "no false traditional family"); e.staging.discard(s); e.empty();
        });
        test("unrelated siblings are excluded", e -> {
            Node first = e.realRar4("one.part", 1);
            e.fs.add("other.part2.rar", new byte[]{42}); e.fs.add("one.part02.rar", new byte[]{43});
            e.fs.add("one.part2.rar.bak", new byte[]{44});
            FileStaging.Stage s = e.open(first); e.assertCopies(s, 3); e.staging.discard(s); e.empty();
        });
        test("directory matching a volume fails closed", e -> {
            Node first = e.realRar4("dir.part", 1); e.fs.named("dir.part2.rar").directory = true;
            expectIO(() -> e.open(first)); e.empty();
        });
        test("symlink matching a volume is never read", e -> {
            Node first = e.realRar4("link.part", 1); Node link = e.fs.named("link.part2.rar"); link.link = true;
            expectIO(() -> e.open(first)); check(link.reads == 0, "symlink unread"); e.empty();
        });
        test("source changing to symlink during copy", e -> {
            Node first = e.realRar4("race.part", 1); Node second = e.fs.named("race.part2.rar");
            second.afterRead = () -> second.link = true;
            expectIO(() -> e.open(first)); e.empty();
        });
        test("foreign-parent listing entry is rejected", e -> {
            Node first = e.realRar4("foreign.part", 1); e.fs.named("foreign.part2.rar").foreign = true;
            expectIO(() -> e.open(first)); e.empty();
        });
        test("duplicate listing entry is rejected", e -> {
            Node first = e.realRar4("dup.part", 1); e.fs.duplicate = e.fs.named("dup.part2.rar");
            expectIO(() -> e.open(first)); e.empty();
        });
        test("missing middle volume fails before sibling read", e -> {
            Node first = e.realRar4("gap.part", 1); e.fs.nodes.remove(e.fs.named("gap.part2.rar").location);
            expectIO(() -> e.open(first)); check(e.fs.named("gap.part3.rar").reads == 0, "no partial set copied"); e.empty();
        });
        test("volume count/index cap", e -> {
            Node first = e.realRar4("count.part", 1); e.fs.add("count.part1025.rar", first.bytes);
            expectIO(() -> e.open(first)); e.empty();
        });
        test("integer-overflow volume number fails closed", e -> {
            Node first = e.realRar4("overflow.part", 1); e.fs.add("overflow.part99999999999999999999.rar", first.bytes);
            expectIO(() -> e.open(first)); e.empty();
        });
        test("declared per-volume size cap", e -> {
            Node first = e.realRar4("large.part", 1); Node second = e.fs.named("large.part2.rar"); second.size = 4L * 1024 * 1024 * 1024 + 1;
            expectIO(() -> e.open(first)); check(second.reads == 0, "oversize not read"); e.empty();
        });
        test("declared aggregate size cap", e -> {
            Node first = e.fs.add("total.part1.rar", fixture("test_rar_multivolume_single_file.part1.rar"));
            for (int i = 2; i <= 6; i++) e.fs.add("total.part" + i + ".rar", first.bytes).size = 4L * 1024 * 1024 * 1024;
            expectIO(() -> e.open(first)); check(e.fs.reads == 1, "aggregate rejected before sibling reads"); e.empty();
        });
        test("actual bytes disagree with declared size", e -> {
            Node first = e.realRar4("wrong.part", 1); e.fs.named("wrong.part2.rar").size++;
            expectIO(() -> e.open(first)); e.empty();
        });
        test("unknown size/timestamp still copies with byte limits", e -> {
            Node first = e.realRar4("unknown.part", 1);
            for (Node n : e.fs.nodes.values()) { n.size = -1; n.modified = 0; }
            FileStaging.Stage s = e.open(first); e.assertCopies(s, 3); e.staging.discard(s); e.empty();
        });
        test("cancel during sibling streaming cleans all owned copies", e -> {
            Node first = e.realRar4("cancel.part", 1);
            FileOperations.Monitor monitor = new FileOperations.Monitor() {
                boolean cancelled;
                @Override public void checkCancelled() throws InterruptedIOException { if (cancelled) throw new InterruptedIOException("test cancellation"); }
                @Override public void progress(String name, long bytes, long files) { if (name.equals("cancel.part2.rar")) cancelled = true; }
            };
            expectIO(() -> e.staging.openArchive(first.location, monitor)); e.empty();
        });
        test("sibling stream IOException cleans incomplete tracked volume", e -> {
            Node first = e.realRar4("broken.part", 1); e.fs.named("broken.part2.rar").failRead = true;
            expectIO(() -> e.open(first)); e.empty();
        });
        test("restart discards persisted incomplete-volume manifest after simulated process loss", e -> {
            Node first = e.realRar4("restart.part", 1); e.fs.named("restart.part2.rar").crashRead = true;
            try { e.open(first); throw new IOException("Expected simulated process loss"); }
            catch (AssertionError loss) { check("simulated process loss".equals(loss.getMessage()), "expected injected loss"); }
            FileStaging restarted = new FileStaging(e.context, e.fs);
            List<FileStaging.Stage> saved = restarted.recoverable(); check(saved.size() == 1, "recover persisted primary");
            File partial = new File(saved.get(0).file.getParentFile(), "restart.part2.rar");
            check(partial.length() == 4, "actual partial sibling");
            check(new JSONObject(readText(e.metadata(saved.get(0)))).getJSONArray("volumes").length() == 1, "only created sibling persisted");
            restarted.discard(saved.get(0)); e.empty();
        });
        test("source mtime change detected", e -> {
            Node first = e.realRar4("mtime.part", 1); Node second = e.fs.named("mtime.part2.rar"); second.afterRead = () -> second.modified++;
            expectIO(() -> e.open(first)); e.empty();
        });
        test("primary changed with identical size/mtime detected by hash", e -> {
            Node first = e.realRar4("primary.part", 1); Node second = e.fs.named("primary.part2.rar");
            second.afterRead = () -> first.bytes[first.bytes.length - 1] ^= 1;
            expectIO(() -> e.open(first)); e.empty();
        });
        test("earlier sibling changed with identical size/mtime detected by hash", e -> {
            Node first = e.realRar4("sibling.part", 1); Node second = e.fs.named("sibling.part2.rar");
            e.fs.named("sibling.part3.rar").afterRead = () -> second.bytes[second.bytes.length - 1] ^= 1;
            expectIO(() -> e.open(first)); e.empty();
        });
        test("unknown extraction output survives discard; lease remains recoverable", e -> {
            FileStaging.Stage s = e.open(e.realRar4("output.part", 1));
            File output = new File(s.file.getParentFile(), "user-output.txt"); write(output, new byte[]{11, 12});
            expectIO(() -> e.staging.discard(s)); check(Arrays.equals(read(output), new byte[]{11, 12}) && s.file.isFile(), "preflight preserves all files");
            check(e.staging.recoverable().size() == 1, "unknown output does not hide primary");
            check(output.delete(), "remove own test output"); e.staging.discard(s); e.empty();
        });
        test("malformed/traversal manifest cannot clean unknown file", e -> {
            FileStaging.Stage s = e.open(e.realRar4("badmeta.part", 1)); File metadata = e.metadata(s); byte[] original = read(metadata);
            File unknown = new File(s.file.getParentFile(), "keep.txt"); write(unknown, new byte[]{66});
            JSONObject bad = new JSONObject(new String(original, java.nio.charset.StandardCharsets.UTF_8));
            bad.put("volumes", new JSONArray().put("../keep.txt")); write(metadata, bad.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            expectIO(() -> e.staging.restore(s.file)); expectIO(() -> e.staging.discard(s));
            check(read(unknown)[0] == 66 && s.file.isFile(), "bad metadata preserves unknown and primary");
            write(metadata, original); check(unknown.delete(), "remove own test output"); e.staging.discard(s); e.empty();
        });
        test("wrong JSON types are invalid leases", e -> {
            FileStaging.Stage s = e.open(e.realRar4("types.part", 1)); File metadata = e.metadata(s); byte[] original = read(metadata);
            for (String key : new String[]{"name", "origin", "sha256", "rarReadOnly", "volumes"}) {
                JSONObject bad = new JSONObject(new String(original, java.nio.charset.StandardCharsets.UTF_8)); bad.put(key, 123);
                write(metadata, bad.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); expectIO(() -> e.staging.restore(s.file));
            }
            write(metadata, original); e.staging.discard(s); e.empty();
        });
        test("bad metadata is isolated without hiding a healthy lease or deleting files", e -> {
            Node first = e.realRar4("bad-isolated.part", 1); Node second = e.realRar4("healthy.part", 1);
            FileStaging.Stage bad = e.open(first); FileStaging.Stage healthy = e.open(second);
            File metadata = e.metadata(bad); byte[] original = read(metadata); write(metadata, new byte[]{'{'});
            List<FileStaging.Stage> recovered = e.staging.recoverable();
            check(recovered.size() == 1 && recovered.get(0).file.equals(healthy.file), "healthy lease recovered alone");
            expectIO(() -> e.staging.restore(bad.file)); expectIO(() -> e.staging.discard(bad));
            check(bad.file.isFile() && read(metadata).length == 1, "bad lease preserved unchanged");
            e.staging.discard(recovered.get(0)); write(metadata, original); e.staging.discard(bad); e.empty();
        });
        test("unknown pending metadata is neither overwritten nor deleted", e -> {
            Node first = e.realRar4("pending.part", 1);
            e.fs.onList = () -> writeUnchecked(new File(e.onlyLease(), ".origin.pending"), new byte[]{55});
            expectIO(() -> e.open(first));
            check(read(new File(e.onlyLease(), ".origin.pending"))[0] == 55, "pending preserved");
            check(e.fs.named("pending.part2.rar").reads == 0, "failure before source read");
        });
        test("existing volume destination is never overwritten", e -> {
            Node first = e.realRar4("collision.part", 1);
            e.fs.onList = () -> writeUnchecked(new File(new File(e.onlyLease(), "data"), "collision.part2.rar"), new byte[]{77});
            expectIO(() -> e.open(first));
            check(read(new File(new File(e.onlyLease(), "data"), "collision.part2.rar"))[0] == 77, "collision retained");
        });
        test("cached sibling changed to symlink cannot delete its target", e -> {
            FileStaging.Stage s = e.open(e.realRar4("cached.part", 1)); File volume = new File(s.file.getParentFile(), "cached.part2.rar");
            File outside = new File(e.cache, "outside"); write(outside, new byte[]{88}); check(volume.delete(), "remove own cached volume");
            Files.createSymbolicLink(volume.toPath(), outside.toPath());
            expectIO(() -> e.staging.discard(s)); check(read(outside)[0] == 88 && s.file.isFile(), "symlink target and lease retained");
        });
        test("dangling metadata symlink cannot be overwritten or cleaned", e -> {
            FileStaging.Stage s = e.open(e.realRar4("meta-link.part", 1)); File metadata = e.metadata(s);
            check(metadata.delete(), "remove own test metadata"); File absent = new File(e.cache, "absent");
            Files.createSymbolicLink(metadata.toPath(), absent.toPath());
            expectIO(() -> e.staging.restore(s.file)); expectIO(() -> e.staging.discard(s));
            check(!absent.exists() && s.file.isFile(), "dangling metadata cannot write target");
        });
        test("symlinked lease directory cannot escape cleanup", e -> {
            FileStaging.Stage s = e.open(e.realRar4("lease-link.part", 1)); File lease = s.file.getParentFile().getParentFile();
            File moved = new File(e.cache, "moved"); check(lease.renameTo(moved), "move own test lease"); Files.createSymbolicLink(lease.toPath(), moved.toPath());
            expectIO(() -> e.staging.restore(s.file)); expectIO(() -> e.staging.discard(s));
            check(new File(new File(moved, "data"), s.file.getName()).isFile(), "outside primary intact");
        });
        test("corrupt metadata blocks ordinary commit before any backend write", e -> {
            Node n = e.fs.add("edit.txt", new byte[]{1, 2}); FileStaging.Stage s = e.staging.open(n.location, FileOperations.NONE);
            write(s.file, new byte[]{3, 4}); write(e.metadata(s), new byte[]{'{'});
            expectIO(() -> e.staging.commit(s, FileOperations.NONE)); check(e.fs.writes == 0 && n.bytes[0] == 1, "source intact");
        });
        test("unknown pending blocks ordinary commit before backend write", e -> {
            Node n = e.fs.add("edit.txt", new byte[]{1, 2}); FileStaging.Stage s = e.staging.open(n.location, FileOperations.NONE);
            write(s.file, new byte[]{3, 4}); File pending = new File(s.file.getParentFile().getParentFile(), ".origin.pending"); write(pending, new byte[]{99});
            expectIO(() -> e.staging.commit(s, FileOperations.NONE)); check(e.fs.writes == 0 && read(pending)[0] == 99, "source and pending intact");
        });
        System.out.println("ArchiveVolumeSmoke: " + passed + " passed");
    }
    private static void test(String name, Scenario scenario) throws Exception {
        try (Env env = new Env()) { scenario.run(env); check(env.fs.writes == 0, "unexpected backend write"); }
        passed++; System.out.println("PASS " + name);
    }
    private static final class Env implements AutoCloseable {
        final File cache = new File(ArchiveVolumeSmoke.context.getCacheDir(), "archive-volume-smoke-" + UUID.randomUUID());
        final Context context;
        final FakeFileAccess fs = new FakeFileAccess();
        final FileStaging staging;
        Env() throws IOException {
            check(cache.mkdirs(), "create isolated smoke cache");
            context = new ContextWrapper(ArchiveVolumeSmoke.context) { @Override public File getCacheDir() { return cache; } };
            staging = new FileStaging(context, fs);
        }
        Node realRar4(String prefix, int width) throws IOException {
            Node first = null;
            for (int i = 1; i <= 3; i++) {
                String part = String.format(java.util.Locale.ROOT, "%0" + width + "d", i);
                Node n = fs.add(prefix + part + ".rar", fixture("test_rar_multivolume_single_file.part" + i + ".rar"));
                if (first == null) first = n;
            }
            return first;
        }
        FileStaging.Stage open(Node first) throws IOException { return staging.openArchive(first.location, FileOperations.NONE); }
        File metadata(FileStaging.Stage s) { return new File(s.file.getParentFile().getParentFile(), ".origin.json"); }
        File onlyLease() {
            File[] leases = new File(cache, "file-workspace").listFiles(); check(leases != null && leases.length == 1, "one lease"); return leases[0];
        }
        void assertCopies(FileStaging.Stage s, int count) throws Exception {
            check(s.owned && s.file.getParentFile().list().length == count, "all volumes staged");
            for (File copy : s.file.getParentFile().listFiles()) check(Arrays.equals(read(copy), fs.named(copy.getName()).bytes), "exact fixture bytes: " + copy);
            JSONObject json = new JSONObject(readText(metadata(s)));
            check(json.getBoolean("rarReadOnly") && json.getJSONArray("volumes").length() == count - 1, "manifest only owns siblings");
        }
        void empty() { File root = new File(cache, "file-workspace"); check(!root.exists() || root.list().length == 0, "owned lease cleaned"); }
        @Override public void close() throws IOException { deleteOwnTree(cache); }
    }
    private static final class Node {
        final FileLocation location;
        final String name;
        byte[] bytes;
        long size, modified = 123;
        boolean directory, link, foreign, failRead, crashRead;
        int reads;
        Runnable afterRead;
        Node(FileLocation location, String name, byte[] bytes) { this.location = location; this.name = name; this.bytes = bytes.clone(); size = bytes.length; }
        FileEntry entry() { return new FileEntry(location, name, directory, link, size, modified); }
    }
    private static final class FakeFileAccess implements FileAccess {
        final FileLocation parent = new FileLocation("content://volume-smoke/tree/opaque");
        final Map<FileLocation, Node> nodes = new LinkedHashMap<>();
        int reads, lists, writes;
        Node duplicate;
        Runnable onList;
        Node add(String name, byte[] bytes) {
            Node n = new Node(new FileLocation("content://volume-smoke/document/" + UUID.randomUUID()), name, bytes); nodes.put(n.location, n); return n;
        }
        Node named(String name) { for (Node n : nodes.values()) if (n.name.equals(name)) return n; throw new AssertionError(name); }
        Node node(FileLocation location) throws IOException { Node n = nodes.get(location); if (n == null) throw new FileNotFoundException(location.uri); return n; }
        @Override public FileEntry stat(FileLocation location) throws IOException { return node(location).entry(); }
        @Override public List<FileEntry> list(FileLocation location) {
            lists++; if (onList != null) onList.run(); List<FileEntry> entries = new ArrayList<>();
            for (Node n : nodes.values()) entries.add(n.entry()); if (duplicate != null) entries.add(duplicate.entry()); return entries;
        }
        @Override public FileLocation parent(FileLocation location) throws IOException {
            return node(location).foreign ? new FileLocation("content://volume-smoke/other") : parent;
        }
        @Override public InputStream read(FileLocation location) throws IOException {
            Node n = node(location); reads++; n.reads++;
            return new InputStream() {
                final InputStream source = new ByteArrayInputStream(n.bytes.clone());
                int calls;
                @Override public int read() throws IOException { return source.read(); }
                @Override public int read(byte[] data, int offset, int length) throws IOException {
                    if ((n.failRead || n.crashRead) && calls++ > 0) {
                        if (n.crashRead) throw new AssertionError("simulated process loss");
                        throw new IOException("injected volume read failure");
                    }
                    return source.read(data, offset, n.failRead || n.crashRead ? Math.min(4, length) : length);
                }
                @Override public void close() throws IOException { source.close(); if (n.afterRead != null) { Runnable hook = n.afterRead; n.afterRead = null; hook.run(); } }
            };
        }
        @Override public FileLocation child(FileLocation location, String name) throws IOException { throw new IOException("write path forbidden in RAR test"); }
        @Override public OutputStream write(FileLocation location, boolean replace) throws IOException { writes++; throw new IOException("write forbidden"); }
        @Override public void mkdir(FileLocation location) throws IOException { writes++; throw new IOException("mkdir forbidden"); }
        @Override public FileLocation rename(FileLocation location, String name) throws IOException { writes++; throw new IOException("rename forbidden"); }
        @Override public void delete(FileLocation location) throws IOException { writes++; throw new IOException("delete forbidden"); }
        @Override public boolean exists(FileLocation location) { return nodes.containsKey(location); }
    }
    private static void expectIO(Check operation) throws Exception {
        try { operation.run(); } catch (IOException expected) { return; } throw new AssertionError("Expected IOException");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static byte[] fixture(String name) throws IOException { return read(new File(fixtures, name)); }
    private static byte[] read(File file) throws IOException { return Files.readAllBytes(file.toPath()); }
    private static String readText(File file) throws IOException { return new String(read(file), java.nio.charset.StandardCharsets.UTF_8); }
    private static void write(File file, byte[] bytes) throws IOException { try (OutputStream out = new FileOutputStream(file)) { out.write(bytes); } }
    private static void writeUnchecked(File file, byte[] bytes) { try { write(file, bytes); } catch (IOException e) { throw new AssertionError(e); } }
    private static void deleteOwnTree(File file) throws IOException {
        File expected = new File(file.getParentFile().getCanonicalFile(), file.getName());
        if (file.isDirectory() && file.getCanonicalFile().equals(expected)) {
            File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot inspect smoke temporary directory");
            for (File child : children) deleteOwnTree(child);
        }
        if (!file.delete()) throw new IOException("Cannot clean smoke temporary file " + file);
    }
}
