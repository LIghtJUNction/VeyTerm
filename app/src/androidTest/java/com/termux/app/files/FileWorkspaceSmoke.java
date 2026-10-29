package com.termux.app.files;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;

import com.termux.app.files.archive.ArchiveTools;
import com.termux.app.files.fs.ArchiveFileAccess;
import com.termux.app.files.fs.DocumentFileAccess;
import com.termux.app.files.fs.FileAccess;
import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;
import com.termux.app.files.fs.FileOperations;
import com.termux.app.files.fs.FileStaging;
import com.termux.app.files.fs.LocalFileAccess;
import com.termux.app.files.fs.RoutingFileAccess;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Run with am instrument -w; assertions execute against Android, never host mock stubs. */
public final class FileWorkspaceSmoke extends Instrumentation {
    private interface Checked { void run() throws Exception; }
    private final StringBuilder report = new StringBuilder();
    private final LocalFileAccess local = new LocalFileAccess();
    private File root;
    private Context isolated;
    private int assertions, passed, failed;
    private static final FileOperations.ConflictResolver REJECT = (s, t) -> FileOperations.Conflict.CANCEL;
    private static final byte[] TEXT = "VeyTerm：目录/雪 ❄ /🙂\n\u0000binary\n".getBytes(StandardCharsets.UTF_8);

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        File reportFile = new File(getTargetContext().getCacheDir(), "file-workspace-smoke-last-report.txt");
        try {
            root = new File(getTargetContext().getCacheDir(), "file-workspace-smoke-" + UUID.randomUUID());
            mkdir(root);
            isolated = new ContextWrapper(getTargetContext()) {
                @Override public File getCacheDir() { return root; }
                @Override public Context getApplicationContext() { return this; }
            };
            report.append("FileWorkspaceSmoke target=").append(getTargetContext().getPackageName())
                .append(" API=").append(android.os.Build.VERSION.SDK_INT).append('\n');
            check("target context is VeyTerm", "com.veyterm".equals(getTargetContext().getPackageName()));
            runCase("local Unicode nested copy/move", this::localTransfers);
            runCase("local cancellation and no-clobber races", this::cancellationAndCollision);
            runCase("local batch rename cycle and rollback", this::batchRename);
            runCase("delete preflight before first mutation", this::deletePreflight);
            runCase("native descriptor ownership and FIFO rejection", this::descriptorOwnership);
            for (ArchiveTools.Format format : new ArchiveTools.Format[]{ArchiveTools.Format.ZIP,
                ArchiveTools.Format.SEVEN_Z, ArchiveTools.Format.TAR, ArchiveTools.Format.TAR_GZIP,
                ArchiveTools.Format.TAR_XZ, ArchiveTools.Format.GZIP}) {
                runCase("archive roundtrip " + format, () -> archiveRoundtrip(format));
            }
            for (ArchiveTools.Format format : new ArchiveTools.Format[]{ArchiveTools.Format.ZIP,
                ArchiveTools.Format.SEVEN_Z, ArchiveTools.Format.TAR, ArchiveTools.Format.TAR_GZIP,
                ArchiveTools.Format.TAR_XZ}) {
                runCase("virtual archive copy/edit/move " + format, () -> virtualArchive(format));
            }
            runCase("malformed/traversal archive rejection", this::malformedArchives);
            runCase("real SAF cross-UID tree/copy/edit/conflict", this::saf);
        } catch (Throwable e) { failure("runner setup", e); }
        finally {
            if (root != null) {
                try { remove(root); check("only this run's target cache removed", !root.exists()); }
                catch (Throwable e) { failure("target cache cleanup", e); }
            }
        }
        report.append("RESULT ").append(failed == 0 ? "PASS" : "FAIL").append(" cases_passed=")
            .append(passed).append(" failures=").append(failed).append(" assertions=").append(assertions).append('\n');
        try (FileOutputStream out = new FileOutputStream(reportFile)) {
            out.write(report.toString().getBytes(StandardCharsets.UTF_8));
            result.putString("report_path", reportFile.getAbsolutePath());
        } catch (IOException e) {
            failure("write textual report", e);
            report.append("RESULT FAIL: report persistence failed\n");
        }
        result.putString("stream", "\n" + report);
        result.putString("report", report.toString());
        result.putInt("smoke_status", failed == 0 ? 0 : 1);
        result.putInt("assertions", assertions);
        result.putInt("passed", passed);
        result.putInt("failed", failed);
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void runCase(String name, Checked test) {
        try { test.run(); passed++; report.append("PASS case: ").append(name).append('\n'); }
        catch (Throwable e) { failure(name, e); }
        Bundle status = new Bundle();
        status.putString("stream", name + ": passed=" + passed + " failed=" + failed + "\n");
        sendStatus(0, status);
    }

    private void failure(String name, Throwable e) {
        failed++;
        report.append("FAIL ").append(name).append(": ").append(e.getClass().getName())
            .append(": ").append(e.getMessage()).append('\n');
        StackTraceElement[] stack = e.getStackTrace();
        for (int i = 0; i < Math.min(5, stack.length); i++) report.append("  at ").append(stack[i]).append('\n');
        for (Throwable suppressed : e.getSuppressed()) report.append("  suppressed: ").append(suppressed).append('\n');
    }

    private void check(String message, boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError(message);
        report.append("  PASS ").append(message).append('\n');
    }

    private void rejects(String message, Class<? extends Throwable> type, Checked action) throws Exception {
        try { action.run(); }
        catch (Exception e) {
            if (!type.isInstance(e)) throw e;
            check(message, true); return;
        }
        check(message, false);
    }

    private File directory(String name) throws IOException { File file = new File(root, name); mkdir(file); return file; }
    private static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create smoke directory " + file);
    }
    private static void remove(File file) throws IOException {
        // Never follow a link during cleanup (the runner also creates a FIFO fixture).
        try {
            if (OsConstants.S_ISDIR(Os.lstat(file.getPath()).st_mode)) {
                File[] files = file.listFiles();
                if (files == null) throw new IOException("Cannot list smoke directory " + file);
                for (File child : files) remove(child);
            }
        } catch (android.system.ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return;
            throw new IOException(e);
        }
        if (!file.delete()) throw new IOException("Cannot remove smoke fixture " + file);
    }
    private static FileLocation loc(File file) { return FileLocation.local(file); }
    private static List<FileLocation> one(FileLocation location) { return Collections.singletonList(location); }
    private static void put(File file, byte[] bytes) throws IOException {
        mkdir(file.getParentFile());
        try (OutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }
    private static void put(FileAccess fs, FileLocation file, byte[] bytes) throws IOException {
        try (OutputStream out = fs.write(file, true)) { out.write(bytes); }
    }
    private static byte[] bytes(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 2 * 1024 * 1024) throw new IOException("Unexpectedly large smoke fixture");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
    private void content(String message, FileAccess fs, FileLocation file, byte[] expected) throws IOException {
        check(message, Arrays.equals(expected, bytes(fs.read(file))));
    }
    private static FileLocation path(FileAccess fs, FileLocation base, String... segments) throws IOException {
        for (String segment : segments) base = fs.child(base, segment);
        return base;
    }

    private void localTransfers() throws Exception {
        File base = directory("local");
        File source = new File(base, "来源 雪🙂");
        put(new File(source, "层一/层二/内容.txt"), TEXT);
        put(new File(source, ".origin.json"), new byte[]{1, 2, 3});
        mkdir(new File(source, "空目录"));
        File destination = new File(base, "copy"), moved = new File(base, "moved");
        mkdir(destination); mkdir(moved);
        FileOperations ops = new FileOperations(local);
        ops.transfer(one(loc(source)), loc(destination), false, REJECT, FileOperations.NONE);
        File copy = new File(destination, source.getName());
        content("nested Unicode copy has exact bytes", local, loc(new File(copy, "层一/层二/内容.txt")), TEXT);
        check("copy preserves empty directories", new File(copy, "空目录").isDirectory());
        check("copy preserves source", source.isDirectory());
        ops.transfer(one(loc(copy)), loc(moved), true, REJECT, FileOperations.NONE);
        check("move deletes complete source subtree", !copy.exists());
        content("move retains hidden user file", local, loc(new File(moved, source.getName() + "/.origin.json")), new byte[]{1, 2, 3});
        rejects("copy into descendant rejected", IOException.class,
            () -> ops.transfer(one(loc(source)), loc(new File(source, "层一")), false, REJECT, FileOperations.NONE));
    }

    private void cancellationAndCollision() throws Exception {
        File base = directory("cancel"); File source = new File(base, "large.bin");
        byte[] payload = new byte[256 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + i / 251);
        put(source, payload);
        File dest = new File(base, "destination"); mkdir(dest);
        FileOperations ops = new FileOperations(local);
        FileOperations.Monitor cancel = new FileOperations.Monitor() {
            boolean copied;
            @Override public void checkCancelled() throws InterruptedIOException {
                if (copied) throw new InterruptedIOException("Deterministic cancellation after data progress");
            }
            @Override public void progress(String name, long count, long files) { copied |= count > 0; }
        };
        rejects("cancelled move reports interruption", InterruptedIOException.class,
            () -> ops.transfer(one(loc(source)), loc(dest), true, REJECT, cancel));
        content("cancelled move preserves source bytes", local, loc(source), payload);
        check("cancelled copy publishes no file or temp", local.list(loc(dest)).isEmpty());
        File target = new File(dest, source.getName()); byte[] sentinel = {11, 22, 33};
        FileOperations.Monitor racing = new FileOperations.Monitor() {
            boolean created;
            @Override public void checkCancelled() { }
            @Override public void progress(String name, long count, long files) {
                if (!created && count > 0) {
                    created = true;
                    try { put(target, sentinel); } catch (IOException e) { throw new IllegalStateException(e); }
                }
            }
        };
        rejects("late destination collision refuses clobber", IOException.class,
            () -> ops.transfer(one(loc(source)), loc(dest), true, REJECT, racing));
        content("late destination winner survives", local, loc(target), sentinel);
        content("collision preserves move source", local, loc(source), payload);
        check("late collision cleans temporary output", local.list(loc(dest)).size() == 1);
        rejects("exclusive local write rejects existing file", IOException.class, () -> {
            try (OutputStream ignored = local.write(loc(target), false)) { /* must not open */ }
        });
        ops.transfer(one(loc(source)), loc(dest), true, (s, t) -> FileOperations.Conflict.SKIP, FileOperations.NONE);
        check("skipped move keeps source", source.exists());
        content("skipped move keeps target", local, loc(target), sentinel);
    }

    private void batchRename() throws Exception {
        File base = directory("rename");
        File a = new File(base, "雪.txt"), b = new File(base, "月.txt");
        put(a, new byte[]{1}); put(b, new byte[]{2});
        Map<FileLocation, String> cycle = new LinkedHashMap<>();
        cycle.put(loc(a), b.getName()); cycle.put(loc(b), a.getName());
        FileOperations ops = new FileOperations(local);
        ops.batchRename(cycle, FileOperations.NONE);
        content("batch cycle first name contains second payload", local, loc(a), new byte[]{2});
        content("batch cycle second name contains first payload", local, loc(b), new byte[]{1});
        FileOperations.Monitor cancel = new FileOperations.Monitor() {
            boolean published;
            @Override public void checkCancelled() throws InterruptedIOException {
                if (published) throw new InterruptedIOException("Cancel after first final rename");
            }
            @Override public void progress(String name, long bytes, long files) { published = true; }
        };
        rejects("cancelled batch cycle reports interruption", InterruptedIOException.class, () -> ops.batchRename(cycle, cancel));
        content("rollback restores first payload", local, loc(a), new byte[]{2});
        content("rollback restores second payload", local, loc(b), new byte[]{1});
        check("batch rollback leaves no temporary names", local.list(loc(base)).size() == 2);
    }

    private void deletePreflight() throws Exception {
        File base = directory("preflight"); File a = new File(base, "allowed"), b = new File(base, "denied");
        put(a, new byte[]{1}); put(b, new byte[]{2});
        File dest = new File(base, "dest"); mkdir(dest);
        RoutingFileAccess guarded = new RoutingFileAccess().register("file", new GuardedLocal(local, loc(b)));
        FileOperations ops = new FileOperations(guarded);
        List<FileLocation> selected = Arrays.asList(loc(a), loc(b));
        rejects("move preflights every selected source", IOException.class,
            () -> ops.transfer(selected, loc(dest), true, REJECT, FileOperations.NONE));
        check("denied move leaves destination empty", local.list(loc(dest)).isEmpty());
        check("denied move preserves both sources", a.exists() && b.exists());
        rejects("delete preflights every selection", IOException.class, () -> ops.remove(selected, FileOperations.NONE));
        check("denied delete preserves earlier source", a.exists() && b.exists());
        Map<FileLocation, String> plan = new LinkedHashMap<>(); plan.put(loc(a), "new-a"); plan.put(loc(b), "new-b");
        rejects("batch rename preflights every selection", IOException.class, () -> ops.batchRename(plan, FileOperations.NONE));
        content("denied batch preserves first source content", local, loc(a), new byte[]{1});
        content("denied batch preserves second source content", local, loc(b), new byte[]{2});
        check("preflight has no rename debris", local.list(loc(base)).size() == 3);
    }

    private int descriptors() throws IOException {
        String[] names = new File("/proc/self/fd").list();
        if (names == null) throw new IOException("Cannot count process descriptors");
        return names.length;
    }
    private void descriptorOwnership() throws Exception {
        File base = directory("descriptors"), file = new File(base, "data"); put(file, TEXT);
        // Warm up Android's descriptor classes before the baseline. No GC: close must own the fd.
        for (int i = 0; i < 8; i++) { put(local, loc(file), TEXT); bytes(local.read(loc(file))); }
        int before = descriptors();
        for (int i = 0; i < 256; i++) {
            put(local, loc(file), TEXT);
            byte[] actual = bytes(local.read(loc(file)));
            if (!Arrays.equals(actual, TEXT)) throw new AssertionError("descriptor iteration bytes " + i);
        }
        int after = descriptors();
        report.append("  fd baseline=").append(before).append(" after=").append(after).append(" margin=8\n");
        check("256 native stream pairs close without fd growth", after <= before + 8);
        File fifo = new File(base, "pipe"); Os.mkfifo(fifo.getPath(), 0600);
        rejects("FIFO read rejected without blocking", IOException.class, () -> {
            try (InputStream ignored = local.read(loc(fifo))) { }
        });
        rejects("FIFO replace rejected without blocking", IOException.class, () -> {
            try (OutputStream ignored = local.write(loc(fifo), true)) { }
        });
        check("FIFO remains a FIFO", OsConstants.S_ISFIFO(Os.lstat(fifo.getPath()).st_mode));
        check("rejected FIFO opens close descriptors", descriptors() <= after + 2);
    }

    private ArchiveTools.Options options() {
        ArchiveTools.Options options = new ArchiveTools.Options();
        options.maxEntryBytes = 2 * 1024 * 1024; options.maxTotalBytes = 8 * 1024 * 1024;
        options.maxEntries = 64; return options;
    }
    private List<ArchiveTools.Source> archiveSources(File base) throws IOException {
        File payload = new File(base, "source.bin"), empty = new File(base, "empty");
        put(payload, TEXT); mkdir(empty);
        return Arrays.asList(new ArchiveTools.Source(empty, "显式目录/"),
            new ArchiveTools.Source(empty, "显式目录/空目录/"),
            new ArchiveTools.Source(payload, "显式目录/雪🙂.txt"));
    }

    private void archiveRoundtrip(ArchiveTools.Format format) throws Exception {
        File base = directory("roundtrip-" + format), archive = new File(base, "opaque.archive");
        List<ArchiveTools.Source> sources = archiveSources(base);
        if (format == ArchiveTools.Format.GZIP)
            sources = Collections.singletonList(new ArchiveTools.Source(sources.get(2).file, "payload.txt"));
        ArchiveTools.create(archive, format, sources, options(), new ArchiveTools.Job());
        check(format + " detected from signature without extension", ArchiveTools.detect(archive) == format);
        ArchiveTools.test(archive, options(), new ArchiveTools.Job());
        List<ArchiveTools.Entry> entries = ArchiveTools.list(archive, options(), new ArchiveTools.Job());
        check(format + " lists expected member count", entries.size() == sources.size());
        File output = new File(base, "extracted");
        ArchiveTools.extract(archive, output, null, options(), new ArchiveTools.Job());
        String member = format == ArchiveTools.Format.GZIP ? "payload.txt" : "显式目录/雪🙂.txt";
        content(format + " extracted bytes match", local, loc(new File(output, member)), TEXT);
        if (format != ArchiveTools.Format.GZIP)
            check(format + " explicit empty directory survives", new File(output, "显式目录/空目录").isDirectory());
        rejects(format + " extraction refuses existing targets", IOException.class,
            () -> ArchiveTools.extract(archive, output, null, options(), new ArchiveTools.Job()));
        content(format + " refused extraction preserves bytes", local, loc(new File(output, member)), TEXT);
    }

    private RoutingFileAccess routing() {
        RoutingFileAccess fs = new RoutingFileAccess().register("file", local);
        DocumentFileAccess documents = new DocumentFileAccess(isolated);
        fs.register("content", documents).register("pending-document", documents);
        return fs.register("archive", new ArchiveFileAccess(isolated, fs));
    }

    private void virtualArchive(ArchiveTools.Format format) throws Exception {
        File base = directory("virtual-" + format), archive = new File(base, "container");
        ArchiveTools.create(archive, format, archiveSources(base), options(), new ArchiveTools.Job());
        RoutingFileAccess fs = routing(); FileOperations ops = new FileOperations(fs);
        FileLocation virtual = ArchiveFileAccess.root(loc(archive));
        FileLocation explicit = fs.child(virtual, "显式目录");
        check(format + " virtual explicit directory is a directory", fs.stat(explicit).directory);
        File incoming = new File(base, "传入🙂");
        put(new File(incoming, "深层/.origin.json"), TEXT); mkdir(new File(incoming, "空"));
        ops.transfer(one(loc(incoming)), virtual, false, REJECT, FileOperations.NONE);
        FileLocation member = path(fs, virtual, incoming.getName(), "深层", ".origin.json");
        content(format + " local to virtual archive bytes", fs, member, TEXT);
        check(format + " virtual nested empty directory survives", fs.stat(path(fs, virtual, incoming.getName(), "空")).directory);
        File output = new File(base, "out"); mkdir(output);
        ops.transfer(one(fs.child(virtual, incoming.getName())), loc(output), false, REJECT, FileOperations.NONE);
        content(format + " virtual archive to local bytes", local, loc(new File(output, incoming.getName() + "/深层/.origin.json")), TEXT);
        FileStaging staging = new FileStaging(isolated, fs);
        FileStaging.Stage stage = staging.open(member, FileOperations.NONE);
        byte[] edited = "saved archive edit 雪".getBytes(StandardCharsets.UTF_8);
        try {
            check(format + " archive edit owns a private lease", stage.owned && stage.file.getCanonicalPath().startsWith(root.getCanonicalPath() + "/"));
            put(stage.file, edited);
            FileStaging.Stage restored = staging.restore(stage.file);
            check(format + " .origin.json user file restores edit metadata", restored != null && restored.origin.equals(member));
            check(format + " archive staged edit commits", staging.commit(restored, FileOperations.NONE));
            RoutingFileAccess reopened = routing();
            content(format + " fresh archive session sees saved bytes", reopened, member, edited);
            FileStaging.Stage again = staging.open(member, FileOperations.NONE);
            try { content(format + " re-opened edit has saved bytes", local, loc(again.file), edited); }
            finally { staging.discard(again); }
        } finally { staging.discard(stage); }
        // Moving the last file out must leave the explicit parent and its empty sibling.
        FileLocation lastFile = fs.child(explicit, "雪🙂.txt");
        ops.transfer(one(lastFile), loc(output), true, REJECT, FileOperations.NONE);
        check(format + " moved archive entry is deleted", !fs.exists(lastFile));
        check(format + " last-member move preserves explicit parent", fs.exists(explicit) && fs.stat(explicit).directory);
        check(format + " last-member move preserves empty sibling", fs.stat(fs.child(explicit, "空目录")).directory);
        content(format + " moved archive member arrives intact", local, loc(new File(output, "雪🙂.txt")), TEXT);
        File moveIn = new File(base, "move-in.txt"); put(moveIn, TEXT);
        ops.transfer(one(loc(moveIn)), explicit, true, REJECT, FileOperations.NONE);
        check(format + " moving into archive deletes local source", !moveIn.exists());
        content(format + " moving into archive publishes content", fs, fs.child(explicit, "move-in.txt"), TEXT);
        ops.remove(one(fs.child(virtual, incoming.getName())), FileOperations.NONE);
        check(format + " virtual recursive deletion removes subtree", !fs.exists(fs.child(virtual, incoming.getName())));
        ArchiveTools.test(archive, options(), new ArchiveTools.Job());
        check(format + " archive remains valid after virtual mutations", true);
    }

    private void malformedArchives() throws Exception {
        File base = directory("malformed");
        for (byte[] bad : new byte[][]{{1, 2, 3}, {'P', 'K', 3, 4, 0}, {0x37, 0x7a, (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c},
            {(byte) 0x1f, (byte) 0x8b, 8}, {(byte) 0xfd, '7', 'z', 'X', 'Z', 0}}) {
            File archive = new File(base, "bad-" + assertions); put(archive, bad);
            rejects("damaged archive integrity rejected " + archive.getName(), IOException.class,
                () -> ArchiveTools.test(archive, options(), new ArchiveTools.Job()));
            File out = new File(base, "out-" + assertions);
            rejects("damaged archive extraction rejected", IOException.class,
                () -> ArchiveTools.extract(archive, out, null, options(), new ArchiveTools.Job()));
            check("damaged archive creates no extraction output", !out.exists());
        }
        File traversal = new File(base, "traversal.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(traversal))) {
            zip.putNextEntry(new ZipEntry("../escaped.txt")); zip.write(TEXT); zip.closeEntry();
        }
        File output = new File(base, "traversal-out");
        rejects("archive traversal member rejected", IOException.class,
            () -> ArchiveTools.extract(traversal, output, null, options(), new ArchiveTools.Job()));
        check("traversal cannot escape or create output", !output.exists() && !new File(base, "escaped.txt").exists());
    }

    private void saf() throws Exception {
        String token = UUID.randomUUID().toString();
        boolean begun = false;
        try {
            Bundle fixture = getTargetContext().getContentResolver().call(SmokeDocumentsProvider.CONTROL, "begin", token, null);
            begun = true;
            check("provider returns a real tree grant", fixture != null && fixture.getString("tree") != null);
            check("provider fixture executes outside target UID", fixture.getInt("provider_uid") != android.os.Process.myUid());
            check("provider fixture executes outside instrumented process", fixture.getInt("provider_pid") != android.os.Process.myPid());
            FileLocation tree = DocumentFileAccess.tree(Uri.parse(fixture.getString("tree")));
            RoutingFileAccess fs = routing(); FileOperations ops = new FileOperations(fs);
            check("DocumentFileAccess.tree resolves actual provider root", fs.stat(tree).directory && fs.list(tree).isEmpty());
            File base = directory("saf"), source = new File(base, "来源🙂");
            put(new File(source, "子目录/雪.txt"), TEXT); put(new File(source, ".origin.json"), TEXT);
            mkdir(new File(source, "空目录"));
            ops.transfer(one(loc(source)), tree, false, REJECT, FileOperations.NONE);
            FileLocation copied = fs.child(tree, source.getName());
            content("local to SAF preserves nested Unicode bytes", fs, path(fs, copied, "子目录", "雪.txt"), TEXT);
            check("SAF copy preserves empty directories", fs.stat(fs.child(copied, "空目录")).directory);
            File output = new File(base, "out"); mkdir(output);
            ops.transfer(one(copied), loc(output), false, REJECT, FileOperations.NONE);
            content("SAF to local preserves .origin.json user file", local, loc(new File(output, source.getName() + "/.origin.json")), TEXT);
            FileLocation document = fs.child(copied, ".origin.json");
            repeatedSafEdit(fs, document);
            sameSizeConflict(fs, fs.child(copied, ".origin.json"));
            FileLocation renamed = fs.rename(fs.child(copied, ".origin.json"), "改名.json");
            check("SAF rename changes document URI", !renamed.uri.equals(document.uri));
            check("SAF original URI becomes stale", !fs.exists(document));
            content("SAF renamed content is readable", fs, renamed, new byte[]{'B', 'B', 'B', 'B'});
            rejects("SAF directory traversal name rejected", IllegalArgumentException.class,
                () -> fs.createFile(tree, "../outside"));
            ops.remove(one(copied), FileOperations.NONE);
            check("SAF recursive delete removes fixture subtree", fs.list(tree).isEmpty());
            safArchive(fs, tree, base);
        } finally {
            if (begun) {
                Bundle cleanup = getTargetContext().getContentResolver().call(SmokeDocumentsProvider.CONTROL, "end", token, null);
                check("provider-owned fixture removed and grants revoked", cleanup != null && cleanup.getBoolean("removed"));
            }
        }
    }

    private void repeatedSafEdit(RoutingFileAccess fs, FileLocation document) throws Exception {
        FileStaging staging = new FileStaging(isolated, fs);
        FileStaging.Stage stage = staging.open(document, FileOperations.NONE);
        try {
            check("SAF stage does not commit unchanged contents", !staging.commit(stage, FileOperations.NONE));
            put(stage.file, new byte[]{'1', '1', '1', '1'});
            FileStaging.Stage first = staging.restore(stage.file);
            check(".origin.json data keeps independent lease metadata", first != null && first.origin.equals(document));
            check("first SAF staged save commits", staging.commit(first, FileOperations.NONE));
            check("first save tracks changed origin URI", !first.origin.uri.equals(document.uri));
            check("first save invalidates previous document URI", !fs.exists(document));
            content("first SAF save visible through new URI", fs, first.origin, new byte[]{'1', '1', '1', '1'});
            FileStaging.Stage second = staging.restore(first.file);
            check("restored lease persists changed origin URI", second != null && second.origin.equals(first.origin));
            put(second.file, new byte[]{'2', '2', '2', '2'});
            check("second SAF staged save commits", staging.commit(second, FileOperations.NONE));
            check("second save tracks another new origin URI", !second.origin.equals(first.origin));
            check("second save invalidates first published URI", !fs.exists(first.origin));
            FileStaging.Stage third = staging.restore(second.file);
            check("second origin URI survives lease restore", third != null && third.origin.equals(second.origin));
            content("second save is readable using persisted origin", fs, third.origin, new byte[]{'2', '2', '2', '2'});
            check("saved SAF lease is recoverable", staging.recoverable().stream().anyMatch(s -> s.file.equals(stage.file)));
            check("second unchanged save is a no-op", !staging.commit(third, FileOperations.NONE));
        } finally { staging.discard(stage); }
        check("discard removes data and metadata lease", staging.restore(stage.file) == null && !stage.file.exists());
    }

    private void sameSizeConflict(RoutingFileAccess fs, FileLocation document) throws Exception {
        byte[] original = {'A', 'A', 'A', 'A'}, external = {'B', 'B', 'B', 'B'}, edited = {'C', 'C', 'C', 'C'};
        put(fs, document, original);
        FileStaging staging = new FileStaging(isolated, fs);
        FileStaging.Stage stage = staging.open(document, FileOperations.NONE);
        try {
            FileEntry before = fs.stat(document);
            put(stage.file, edited); put(fs, document, external);
            check("external conflict fixture has identical size", fs.stat(document).size == before.size);
            check("external conflict fixture has identical timestamp", fs.stat(document).modified == before.modified);
            rejects("same-size external edit prevents staged overwrite", IOException.class,
                () -> staging.commit(stage, FileOperations.NONE));
            content("conflict preserves external source", fs, document, external);
            content("conflict preserves edited private copy", local, loc(stage.file), edited);
            check("conflicting lease remains restorable", staging.restore(stage.file) != null);
        } finally { staging.discard(stage); }
    }

    private void safArchive(RoutingFileAccess fs, FileLocation tree, File base) throws Exception {
        File archive = new File(base, "saf-container.zip");
        ArchiveTools.create(archive, ArchiveTools.Format.ZIP, archiveSources(base), options(), new ArchiveTools.Job());
        new FileOperations(fs).transfer(one(loc(archive)), tree, false, REJECT, FileOperations.NONE);
        FileLocation stored = fs.child(tree, archive.getName());
        FileLocation virtual = ArchiveFileAccess.root(stored);
        FileLocation member = path(fs, virtual, "显式目录", "雪🙂.txt");
        content("virtual archive reads through real SAF stream", fs, member, TEXT);
        FileStaging staging = new FileStaging(isolated, fs);
        FileStaging.Stage stage = staging.open(member, FileOperations.NONE);
        byte[] edited = "SAF archive save".getBytes(StandardCharsets.UTF_8);
        try {
            put(stage.file, edited);
            check("archive entry staged save writes through SAF", staging.commit(stage, FileOperations.NONE));
            FileLocation current = fs.child(tree, archive.getName());
            check("SAF archive replacement changes container URI", !current.equals(stored));
            RoutingFileAccess reopened = routing();
            content("fresh SAF archive session sees saved entry", reopened,
                path(reopened, ArchiveFileAccess.root(current), "显式目录", "雪🙂.txt"), edited);
        } finally { staging.discard(stage); }
        new FileOperations(fs).remove(one(fs.child(tree, archive.getName())), FileOperations.NONE);
        check("SAF archive test cleans published document", fs.list(tree).isEmpty());
    }

    /** Only the authorization boundary is injected; every allowed operation uses real local storage. */
    private static final class GuardedLocal implements FileAccess {
        private final FileAccess files;
        private final FileLocation denied;
        GuardedLocal(FileAccess files, FileLocation denied) { this.files = files; this.denied = denied; }
        @Override public void checkDelete(FileLocation l) throws IOException {
            if (l.equals(denied)) throw new IOException("Fixture denies destructive access");
        }
        @Override public FileEntry stat(FileLocation l) throws IOException { return files.stat(l); }
        @Override public List<FileEntry> list(FileLocation l) throws IOException { return files.list(l); }
        @Override public FileLocation parent(FileLocation l) throws IOException { return files.parent(l); }
        @Override public FileLocation child(FileLocation l, String n) throws IOException { return files.child(l, n); }
        @Override public InputStream read(FileLocation l) throws IOException { return files.read(l); }
        @Override public OutputStream write(FileLocation l, boolean r) throws IOException { return files.write(l, r); }
        @Override public void mkdir(FileLocation l) throws IOException { files.mkdir(l); }
        @Override public FileLocation rename(FileLocation l, String n) throws IOException { return files.rename(l, n); }
        @Override public void delete(FileLocation l) throws IOException { files.delete(l); }
        @Override public boolean exists(FileLocation l) throws IOException { return files.exists(l); }
    }
}
