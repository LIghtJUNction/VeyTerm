package com.termux.app.files;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.webkit.MimeTypeMap;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

import androidx.core.content.FileProvider;

import com.termux.BuildConfig;
import com.termux.app.TermuxActivity;
import com.termux.app.files.apk.ApkToolsActivity;
import com.termux.app.files.archive.ArchiveTools;
import com.termux.app.files.data.FileDataTools;
import com.termux.app.files.decompile.DecompiledJavaActivity;
import com.termux.app.files.editor.FileCompareActivity;
import com.termux.app.files.editor.FileViewerActivity;
import com.termux.app.files.editor.HexEditorActivity;
import com.termux.app.files.editor.TextEditorActivity;
import com.termux.app.files.fs.ArchiveFileAccess;
import com.termux.app.files.fs.DocumentFileAccess;
import com.termux.app.files.fs.FileAccess;
import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;
import com.termux.app.files.fs.FileOperations;
import com.termux.app.files.fs.FileStaging;
import com.termux.app.files.fs.RemoteFileAccess;
import com.termux.app.files.fs.RoutingFileAccess;
import com.termux.app.files.privileged.PrivilegedFiles;
import com.termux.app.files.remote.RemoteConnectionsActivity;
import com.termux.app.files.ui.FileManagerActivity;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Native workspace entry points. Storage work runs through the shared cancellable job monitor. */
public final class FileTools {
    private static final int EDIT = 9401, TREE = 9402, REMOTE = 9403, STORAGE = 9404, SHARE = 9405, PERMISSION = 9406, COMPARE = 9407;
    private FileTools() { }

    public static void restoreState(FileManagerActivity activity, Bundle state) { FileToolState.of(activity).restore(state); }
    public static void saveState(FileManagerActivity activity, Bundle state) { FileToolState.of(activity).save(state); }
    private static Context context(FileManagerActivity activity) { return activity.getApplicationContext(); }
    private static ArchiveFileAccess archives(FileManagerActivity activity) {
        return (ArchiveFileAccess) ((RoutingFileAccess) activity.access()).backend("archive");
    }
    public static void open(FileManagerActivity activity, FileEntry entry) {
        if (entry.directory) { activity.navigate(entry.location); return; }
        String ext = entry.extension();
        if (ext.equals("apk") || ext.equals("apks") || ext.equals("xapk") || ext.equals("dex") || ext.equals("arsc")) launch(activity, entry, ApkToolsActivity.class);
        else if (isArchive(ext)) activity.navigate(ArchiveFileAccess.root(entry.location));
        else if (isMedia(ext)) launch(activity, entry, FileViewerActivity.class);
        else launch(activity, entry, TextEditorActivity.class);
    }
    private static boolean isArchive(String ext) { return Arrays.asList("zip", "jar", "7z", "rar", "tar", "gz", "tgz", "xz", "txz", "apks", "xapk").contains(ext); }
    private static boolean isMedia(String ext) { return Arrays.asList("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif", "mp3", "ogg", "wav", "flac", "m4a", "aac", "mp4", "mkv", "webm", "3gp", "ttf", "otf", "ttc").contains(ext); }
    private static void launch(FileManagerActivity activity, FileEntry entry, Class<? extends Activity> type) {
        FileAccess fs = activity.access(); Context app = context(activity); FileToolState state = FileToolState.of(activity);
        FileLocation preferredOutput = activity.otherDirectory();
        activity.runOperation("Open " + entry.name, monitor -> {
            FileStaging staging = new FileStaging(app, fs);
            FileStaging.Stage stage = staging.open(entry.location, monitor);
            boolean launched = false;
            try {
                monitor.checkCancelled();
                Class<? extends Activity> chosen = type;
                if (type == TextEditorActivity.class && entry.extension().equals("xml")) {
                    try (java.io.InputStream in = new java.io.FileInputStream(stage.file)) {
                        if (in.read() == 3 && in.read() == 0 && in.read() == 8 && in.read() == 0) chosen = ApkToolsActivity.class;
                    }
                }
                Intent intent = new Intent(app, chosen).putExtra("file_path", stage.file.getAbsolutePath());
                if (stage.owned && (chosen == ApkToolsActivity.class || chosen == DecompiledJavaActivity.class)) {
                    File output = preferredOutput != null && preferredOutput.isLocal() ? preferredOutput.file() : null;
                    if (output == null || !output.isDirectory() || !output.canWrite()) output = new File(app.getFilesDir(), "home");
                    if (!output.isDirectory() && !output.mkdirs()) throw new IOException("Cannot create the VeyTerm output folder");
                    intent.putExtra("file_output_directory", output.getAbsolutePath());
                }
                state.launch(intent, EDIT); launched = true;
                return "";
            } finally {
                if (!launched) staging.discard(stage);
            }
        });
    }
    public static void showMenu(FileManagerActivity activity, List<FileEntry> entries) {
        if (entries.isEmpty()) { message(activity, "File tools", "Select an entry using its checkbox or long press."); return; }
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        FileEntry first = entries.get(0);
        if (entries.size() == 1 && !first.directory) {
            add(labels, actions, "Text editor", () -> launch(activity, first, TextEditorActivity.class));
            add(labels, actions, "Hex editor", () -> launch(activity, first, HexEditorActivity.class));
            add(labels, actions, "Preview / media / fonts", () -> launch(activity, first, FileViewerActivity.class));
            add(labels, actions, "APK / DEX / resources", () -> launch(activity, first, ApkToolsActivity.class));
            add(labels, actions, "Decompile APK / DEX to Java", () -> launch(activity, first, DecompiledJavaActivity.class));
            add(labels, actions, "Browse as archive", () -> activity.navigate(ArchiveFileAccess.root(first.location)));
            add(labels, actions, "Open encrypted archive…", () -> archivePassword(activity, first.location));
            add(labels, actions, "Test archive…", () -> testArchive(activity, first));
            add(labels, actions, "Extract to other column…", () -> extractArchive(activity, first));
            add(labels, actions, "Split file…", () -> split(activity, first));
            add(labels, actions, "Verify checksum manifest", () -> verifyManifest(activity, first));
            add(labels, actions, "Share / open in another app", () -> share(activity, first));
            if (first.location.isLocal()) add(labels, actions, "Run shell script in VeyTerm…", () -> runScript(activity, first));
        }
        add(labels, actions, "Create archive in other column…", () -> compress(activity, entries));
        add(labels, actions, "Compare with other column…", () -> compare(activity, entries));
        if (entries.size() > 1) add(labels, actions, "Merge selected files…", () -> merge(activity, entries));
        if (entries.size() == 1) add(labels, actions, "Create symbolic link in other column…", () -> symlink(activity, first));
        new AlertDialog.Builder(activity).setTitle(entries.size() == 1 ? first.name : entries.size() + " selected entries")
            .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run()).setNegativeButton("Close", null).show();
    }
    public static void showTools(FileManagerActivity activity) {
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        add(labels, actions, "Selected file tools", () -> showMenu(activity, activity.selectedEntries()));
        add(labels, actions, "Installed apps / APK tools", () -> activity.startActivity(new Intent(activity, ApkToolsActivity.class)));
        add(labels, actions, "Remote connections / HTTP transfer", () -> activity.startActivityForResult(new Intent(activity, RemoteConnectionsActivity.class), REMOTE));
        add(labels, actions, "Open terminal here", () -> terminalHere(activity));
        add(labels, actions, "Saved editing workspaces", () -> savedEdits(activity));
        if (activity.activeDirectory().uri.startsWith("archive:")) {
            add(labels, actions, "Archive password…", () -> {
                try { archivePassword(activity, ArchiveFileAccess.container(activity.activeDirectory())); }
                catch (IOException e) { message(activity, "Archive", e.getMessage()); }
            });
            add(labels, actions, "Leave archive", () -> {
                FileAccess fs = activity.access(); FileLocation location = activity.activeDirectory(); FileToolState state = FileToolState.of(activity);
                activity.runOperation("Leave archive", monitor -> { FileLocation parent = fs.parent(ArchiveFileAccess.container(location)); if (parent != null) state.navigate(parent); return ""; });
            });
        }
        add(labels, actions, "Disconnect ROOT / Shizuku", () -> { PrivilegedFiles.get(activity).disconnect(); activity.refreshPanels(); });
        new AlertDialog.Builder(activity).setTitle("Workspace tools").setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run()).setNegativeButton("Close", null).show();
    }
    public static void showLocations(FileManagerActivity activity) {
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        add(labels, actions, "Home", () -> activity.navigate(FileLocation.local(new File(activity.getFilesDir(), "home"))));
        add(labels, actions, "VeyTerm system files", () -> activity.navigate(FileLocation.local(new File(activity.getFilesDir(), "usr"))));
        add(labels, actions, "Phone storage", () -> phoneStorage(activity));
        add(labels, actions, "Grant a folder / SD card / USB…", () -> activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), TREE));
        for (android.content.UriPermission permission : activity.getContentResolver().getPersistedUriPermissions()) {
            Uri uri = permission.getUri();
            add(labels, actions, "Folder grant: " + uri.getLastPathSegment(), () -> {
                try { activity.navigate(DocumentFileAccess.tree(uri)); }
                catch (RuntimeException e) { message(activity, "Folder grant", e.getMessage()); }
            });
        }
        add(labels, actions, "Remote connections…", () -> activity.startActivityForResult(new Intent(activity, RemoteConnectionsActivity.class), REMOTE));
        add(labels, actions, "ROOT filesystem…", () -> connectPrivileged(activity, false));
        add(labels, actions, "Shizuku filesystem…", () -> connectPrivileged(activity, true));
        new AlertDialog.Builder(activity).setTitle("Locations").setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run()).setNegativeButton("Close", null).show();
    }
    private static void phoneStorage(FileManagerActivity activity) {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + BuildConfig.APPLICATION_ID)), STORAGE);
        } else if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 30 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION);
        } else activity.navigate(FileLocation.local(Environment.getExternalStorageDirectory()));
    }
    public static void onRequestPermissionsResult(FileManagerActivity activity, int request, int[] results) {
        if (request != PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) activity.navigate(FileLocation.local(Environment.getExternalStorageDirectory()));
        else message(activity, "Storage permission", "Access was not granted. Choose a folder using Android's folder picker.");
    }
    private static void connectPrivileged(FileManagerActivity activity, boolean shizuku) {
        FileToolState state = FileToolState.of(activity);
        PrivilegedFiles.Callback callback = new PrivilegedFiles.Callback() {
            @Override public void connected(int uid) { try { state.navigate(PrivilegedFiles.location(shizuku ? "shizuku" : "root", "/")); } catch (IOException impossible) { throw new IllegalStateException(impossible); } }
            @Override public void failed(String error) { Activity current = state.getActivity(); if (current != null) message(current, shizuku ? "Shizuku" : "ROOT", error); }
        };
        if (shizuku) PrivilegedFiles.get(activity).connectShizuku(activity, callback);
        else PrivilegedFiles.get(activity).connectRoot(activity, callback);
    }
    public static void onActivityResult(FileManagerActivity activity, int request, int result, Intent data) {
        if (request == TREE && result == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData(); int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                activity.getContentResolver().takePersistableUriPermission(uri, flags);
                activity.navigate(DocumentFileAccess.tree(uri));
            } catch (RuntimeException e) { message(activity, "Folder permission", e.getMessage()); }
        } else if (request == REMOTE && result == Activity.RESULT_OK && data != null) {
            try { activity.navigate(RemoteFileAccess.location(data.getStringExtra("remote_connection"), data.getStringExtra("remote_path"))); }
            catch (RuntimeException e) { message(activity, "Remote connection", e.getMessage()); }
        } else if (request == STORAGE) {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) activity.navigate(FileLocation.local(Environment.getExternalStorageDirectory()));
        } else if (request == COMPARE && data != null) {
            ArrayList<String> stages = data.getStringArrayListExtra("fm_compare_stages"), directories = data.getStringArrayListExtra("fm_compare_directories");
            FileStaging staging = new FileStaging(context(activity), activity.access()); File cache = activity.getCacheDir();
            activity.runOperation("Close comparison", monitor -> { cleanComparison(staging, cache, stages, directories); return ""; });
        } else if (request == EDIT && data != null && data.getStringExtra("file_path") != null) {
            File file = new File(data.getStringExtra("file_path"));
            if (!file.getAbsolutePath().startsWith(new File(activity.getCacheDir(), "file-workspace").getAbsolutePath() + File.separator)) return;
            FileStaging staging = new FileStaging(context(activity), activity.access());
            activity.runOperation("Finish editing " + file.getName(), monitor -> {
                FileStaging.Stage stage = staging.restore(file);
                if (stage == null) return "";
                boolean changed = result == Activity.RESULT_OK && staging.commit(stage, monitor);
                staging.discard(stage);
                return changed ? "Saved to " + stage.origin.uri : "";
            });
        }
    }
    private static void archivePassword(FileManagerActivity activity, FileLocation file) {
        EditText password = input(activity, "Password (blank clears it)", ""); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(activity).setTitle("Archive password").setView(password).setNegativeButton("Cancel", null).setPositiveButton("Open", (d, w) -> {
            char[] value = password.getText().toString().toCharArray(); archives(activity).password(file, value); Arrays.fill(value, '\0');
            activity.navigate(ArchiveFileAccess.root(file));
        }).show();
    }
    private static void testArchive(FileManagerActivity activity, FileEntry entry) {
        askPassword(activity, "Test " + entry.name, password -> {
            FileAccess fs = activity.access(); FileStaging staging = new FileStaging(context(activity), fs);
            activity.runOperation("Test archive", monitor -> {
                FileStaging.Stage stage = staging.openArchive(entry.location, monitor); ArchiveTools.Options options = options(password);
                try { ArchiveTools.test(stage.file, options, archiveJob(monitor)); return "Integrity check passed: " + entry.name; }
                finally { Arrays.fill(password, '\0'); staging.discard(stage); }
            });
        });
    }
    private static void extractArchive(FileManagerActivity activity, FileEntry entry) {
        askPassword(activity, "Extract to other column", password -> {
            FileAccess fs = activity.access(); FileLocation destination = activity.otherDirectory();
            Context app = context(activity); FileStaging staging = new FileStaging(app, fs);
            activity.runOperation("Extract " + entry.name, monitor -> {
                FileStaging.Stage stage = staging.openArchive(entry.location, monitor);
                File directory = new File(app.getCacheDir(), "extract-" + UUID.randomUUID());
                if (!directory.mkdir()) throw new IOException("Cannot create extraction workspace");
                try {
                    ArchiveTools.extract(stage.file, directory, null, options(password), archiveJob(monitor));
                    File[] children = directory.listFiles(); if (children == null) throw new IOException("Cannot read extracted files");
                    List<FileLocation> sources = new ArrayList<>(); for (File file : children) sources.add(FileLocation.local(file));
                    FileOperations.Result result = new FileOperations(fs).transfer(sources, destination, false, (source, target) -> FileOperations.Conflict.KEEP_BOTH, monitor);
                    return result.files + " entries extracted; existing names kept separately";
                } finally { Arrays.fill(password, '\0'); deleteScratch(directory); staging.discard(stage); }
            });
        });
    }
    private static void compress(FileManagerActivity activity, List<FileEntry> entries) {
        LinearLayout form = form(activity);
        EditText name = input(activity, "Archive name", entries.size() == 1 ? entries.get(0).name + ".zip" : "Archive.zip"); form.addView(name);
        Spinner format = new Spinner(activity); String[] formats = {"ZIP", "7Z", "TAR", "TAR.GZ", "TAR.XZ", "GZIP (one file)"};
        format.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, formats)); form.addView(format);
        EditText password = input(activity, "Password (optional; ZIP / 7Z)", ""); password.setInputType(129); form.addView(password);
        new AlertDialog.Builder(activity).setTitle("Create in other column").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Create", (d, w) -> {
            String outputName = name.getText().toString();
            try { FileLocation.validateName(outputName); } catch (RuntimeException e) { message(activity, "Archive name", e.getMessage()); return; }
            ArchiveTools.Format[] kinds = {ArchiveTools.Format.ZIP, ArchiveTools.Format.SEVEN_Z, ArchiveTools.Format.TAR, ArchiveTools.Format.TAR_GZIP, ArchiveTools.Format.TAR_XZ, ArchiveTools.Format.GZIP};
            ArchiveTools.Format kind = kinds[format.getSelectedItemPosition()]; char[] secret = password.getText().toString().toCharArray();
            if (secret.length > 0 && kind != ArchiveTools.Format.ZIP && kind != ArchiveTools.Format.SEVEN_Z) { Arrays.fill(secret, '\0'); message(activity, "Archive encryption", "Choose ZIP or 7Z to use a password."); return; }
            FileAccess fs = activity.access(); FileLocation destination = activity.otherDirectory(); Context app = context(activity);
            List<FileEntry> selection = new ArrayList<>(entries);
            activity.runOperation("Create archive", monitor -> {
                if (fs.exists(fs.child(destination, outputName))) throw new IOException("Destination already exists; choose another archive name");
                File workspace = new File(app.getCacheDir(), "compress-" + UUID.randomUUID());
                if (!workspace.mkdir()) throw new IOException("Cannot create compression workspace");
                File empty = new File(workspace, "empty"); if (!empty.mkdir()) throw new IOException("Cannot create directory metadata workspace");
                File packed = new File(workspace, outputName); FileStaging staging = new FileStaging(app, fs);
                List<FileStaging.Stage> stages = new ArrayList<>(); List<ArchiveTools.Source> sources = new ArrayList<>();
                try {
                    for (FileEntry entry : selection) collect(fs, staging, entry, entry.name, empty, sources, stages, monitor, 0);
                    ArchiveTools.Options options = options(secret); if (secret.length > 0) options.encryption = ArchiveTools.Encryption.AES_256;
                    ArchiveTools.create(packed, kind, sources, options, archiveJob(monitor));
                    FileOperations.Result result = new FileOperations(fs).transfer(Collections.singletonList(FileLocation.local(packed)), destination, false, (source, target) -> FileOperations.Conflict.CANCEL, monitor);
                    return "Created " + outputName + " (" + packed.length() + " bytes)";
                } finally {
                    Arrays.fill(secret, '\0'); for (FileStaging.Stage stage : stages) staging.discard(stage); deleteScratch(workspace);
                }
            });
        }).show();
    }
    private static void collect(FileAccess fs, FileStaging staging, FileEntry entry, String name, File empty,
                                List<ArchiveTools.Source> sources, List<FileStaging.Stage> stages, FileOperations.Monitor monitor, int depth) throws IOException {
        monitor.checkCancelled(); if (depth > 128) throw new IOException("Directory nesting exceeds 128 levels");
        if (entry.symbolicLink) throw new IOException("Archive input contains a symbolic link; select its target explicitly: " + entry.name);
        if (entry.directory) {
            sources.add(new ArchiveTools.Source(empty, name + "/"));
            for (FileEntry child : fs.list(entry.location)) collect(fs, staging, child, name + "/" + child.name, empty, sources, stages, monitor, depth + 1);
        } else {
            FileStaging.Stage stage = staging.open(entry.location, monitor); stages.add(stage); sources.add(new ArchiveTools.Source(stage.file, name));
        }
    }
    private static ArchiveTools.Options options(char[] password) { ArchiveTools.Options options = new ArchiveTools.Options(); options.password = password; return options; }
    private static ArchiveTools.Job archiveJob(FileOperations.Monitor monitor) {
        return new ArchiveTools.Job((phase, name, bytes, total) -> { monitor.checkCancelled(); monitor.progress(phase + ": " + name, bytes, 0); });
    }
    private interface PasswordWork { void run(char[] password); }
    private static void askPassword(FileManagerActivity activity, String title, PasswordWork action) {
        EditText password = input(activity, "Password (blank for none)", ""); password.setInputType(129);
        new AlertDialog.Builder(activity).setTitle(title).setView(password).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", (d, w) -> action.run(password.getText().toString().toCharArray())).show();
    }
    private static void compare(FileManagerActivity activity, List<FileEntry> selected) {
        FileAccess fs = activity.access(); Context app = context(activity); FileToolState state = FileToolState.of(activity);
        FileLocation other = activity.otherDirectory(); List<FileEntry> entries = new ArrayList<>(selected);
        activity.runOperation("Prepare comparison", monitor -> {
            FileEntry first = entries.get(0);
            FileEntry second = entries.size() > 1 ? entries.get(1) : fs.stat(fs.child(other, first.name));
            if (entries.size() > 2) throw new IOException("Select two entries, or one entry to compare with the same name in the other column");
            ArrayList<String> stages = new ArrayList<>(), directories = new ArrayList<>();
            try {
                File left = comparisonFile(app, fs, first, monitor, stages, directories), right = comparisonFile(app, fs, second, monitor, stages, directories);
                state.launch(new Intent(app, FileCompareActivity.class).putExtra("file_path", left.getAbsolutePath()).putExtra("compare_path", right.getAbsolutePath())
                    .putStringArrayListExtra("fm_compare_stages", stages).putStringArrayListExtra("fm_compare_directories", directories), COMPARE);
            } catch (Exception failure) {
                try { cleanComparison(new FileStaging(app, fs), app.getCacheDir(), stages, directories); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            return "";
        });
    }
    private static File comparisonFile(Context app, FileAccess fs, FileEntry entry, FileOperations.Monitor monitor, ArrayList<String> stages, ArrayList<String> directories) throws IOException {
        if (entry.location.isLocal()) return entry.location.file();
        if (!entry.directory) {
            FileStaging.Stage stage = new FileStaging(app, fs).open(entry.location, monitor); stages.add(stage.file.getAbsolutePath()); return stage.file;
        }
        File directory = new File(app.getCacheDir(), "compare-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new IOException("Cannot create comparison workspace");
        directories.add(directory.getAbsolutePath());
        FileOperations.Result result = new FileOperations(fs).transfer(Collections.singletonList(entry.location), FileLocation.local(directory), false, (source, target) -> FileOperations.Conflict.CANCEL, monitor);
        return result.outputs.get(0).file();
    }
    private static void cleanComparison(FileStaging staging, File cache, List<String> stages, List<String> directories) throws IOException {
        IOException failure = null;
        if (stages != null) for (String path : stages) try { FileStaging.Stage stage = staging.restore(new File(path)); if (stage != null) staging.discard(stage); }
            catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
        if (directories != null) for (String path : directories) try {
            File directory = new File(path);
            if (!directory.getParentFile().getCanonicalFile().equals(cache.getCanonicalFile()) || !directory.getName().matches("compare-[0-9a-f-]{36}")) throw new IOException("Refusing unrelated comparison cleanup");
            deleteScratch(directory);
        } catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
        if (failure != null) throw failure;
    }
    private static void split(FileManagerActivity activity, FileEntry entry) {
        EditText size = input(activity, "Part size in MiB", "10"); size.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(activity).setTitle("Split into other column").setView(size).setNegativeButton("Cancel", null).setPositiveButton("Split", (d, w) -> {
            long bytes;
            try { bytes = Math.multiplyExact(Long.parseLong(size.getText().toString()), 1024L * 1024L); if (bytes <= 0) throw new NumberFormatException(); }
            catch (RuntimeException e) { message(activity, "Part size", "Enter a positive whole number of MiB."); return; }
            FileAccess fs = activity.access(); FileLocation destination = activity.otherDirectory();
            activity.runOperation("Split " + entry.name, monitor -> FileDataTools.split(fs, entry.location, destination, entry.name, bytes, monitor).size() + " parts created");
        }).show();
    }
    private static void merge(FileManagerActivity activity, List<FileEntry> entries) {
        List<FileLocation> sources = new ArrayList<>(); StringBuilder order = new StringBuilder();
        for (FileEntry entry : entries) { if (entry.directory) { message(activity, "Merge", "Select files only."); return; } sources.add(entry.location); order.append(entry.name).append('\n'); }
        EditText name = input(activity, "Output name", "merged.bin");
        new AlertDialog.Builder(activity).setTitle("Merge in this order").setMessage(order + "\nDestination: other column").setView(name).setNegativeButton("Cancel", null).setPositiveButton("Merge", (d, w) -> {
            FileAccess fs = activity.access(); FileLocation destination = activity.otherDirectory(); String output = name.getText().toString();
            activity.runOperation("Merge files", monitor -> "Created " + FileDataTools.merge(fs, sources, destination, output, monitor).uri);
        }).show();
    }
    private static void verifyManifest(FileManagerActivity activity, FileEntry entry) {
        FileAccess fs = activity.access(); activity.runOperation("Verify checksum manifest", monitor -> FileDataTools.verifyManifest(fs, entry.location, monitor));
    }
    private static void symlink(FileManagerActivity activity, FileEntry entry) {
        FileLocation source = entry.location; String target;
        if (source.isLocal()) target = source.file().getAbsolutePath();
        else if (source.uri.startsWith("root:") || source.uri.startsWith("shizuku:")) target = Uri.parse(source.uri).getPath();
        else { message(activity, "Symbolic link", "A symbolic link requires a local filesystem path."); return; }
        EditText name = input(activity, "Link name", entry.name); String linkTarget = target;
        new AlertDialog.Builder(activity).setTitle("Link in other column").setMessage(target).setView(name).setNegativeButton("Cancel", null).setPositiveButton("Create link", (d, w) -> {
            FileAccess fs = activity.access(); FileLocation destination = activity.otherDirectory(); String linkName = name.getText().toString();
            activity.runOperation("Create symbolic link", monitor -> { monitor.checkCancelled(); fs.symlink(linkTarget, fs.child(destination, linkName)); return "Created " + linkName; });
        }).show();
    }
    private static void share(FileManagerActivity activity, FileEntry entry) {
        Context app = context(activity); FileAccess fs = activity.access(); FileToolState state = FileToolState.of(activity);
        activity.runOperation("Prepare share", monitor -> {
            File directory = new File(app.getCacheDir(), "file-share/" + UUID.randomUUID());
            if (!directory.mkdirs()) throw new IOException("Cannot create sharing workspace");
            FileOperations.Result result = new FileOperations(fs).transfer(Collections.singletonList(entry.location), FileLocation.local(directory), false, (source, target) -> FileOperations.Conflict.CANCEL, monitor);
            File file = result.outputs.get(0).file(); Uri uri = FileProvider.getUriForFile(app, BuildConfig.APPLICATION_ID + ".workspace.files", file);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension());
            Intent send = new Intent(Intent.ACTION_SEND).setType(mime == null ? "application/octet-stream" : mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri(entry.name, uri));
            state.launch(Intent.createChooser(send, "Share " + entry.name), SHARE); return "";
        });
    }
    private static void terminalHere(FileManagerActivity activity) {
        FileLocation directory = activity.activeDirectory();
        if (!directory.isLocal()) { message(activity, "Terminal", "Choose a folder in VeyTerm's local filesystem first."); return; }
        startTerminal(activity, directory.file(), null);
    }
    private static void runScript(FileManagerActivity activity, FileEntry entry) {
        File script = entry.location.file();
        new AlertDialog.Builder(activity).setTitle("Run in a new VeyTerm shell?").setMessage(script.getAbsolutePath())
            .setNegativeButton("Cancel", null).setPositiveButton("Run", (d, w) -> startTerminal(activity, script.getParentFile(), script)).show();
    }
    private static void startTerminal(FileManagerActivity activity, File directory, File script) {
        String token = FileWorkspaceRequests.create(directory, script);
        activity.startActivity(new Intent(activity, TermuxActivity.class).putExtra(FileWorkspaceRequests.EXTRA, token));
    }
    private static void savedEdits(FileManagerActivity activity) {
        File directory = new File(activity.getCacheDir(), "file-workspace");
        if (!directory.isDirectory()) { message(activity, "Saved editing workspaces", "No staged edits have been saved."); return; }
        activity.navigate(FileLocation.local(directory));
        message(activity, "Saved editing workspaces", "Each numbered folder contains data and its original-storage record. Open a saved file to edit it; returning from the editor writes it back only if its original has not changed.");
    }
    private static void add(List<String> labels, List<Runnable> actions, String label, Runnable action) { labels.add(label); actions.add(action); }
    private static EditText input(Context context, String hint, String initial) { EditText input = new EditText(context); input.setSingleLine(true); input.setHint(hint); input.setText(initial); input.selectAll(); return input; }
    private static LinearLayout form(Context context) { LinearLayout form = new LinearLayout(context); form.setOrientation(LinearLayout.VERTICAL); int padding = (int) (16 * context.getResources().getDisplayMetrics().density); form.setPadding(padding, 0, padding, 0); return form; }
    private static void message(Context context, String title, String text) { new AlertDialog.Builder(context).setTitle(title).setMessage(text).setPositiveButton("Close", null).show(); }
    private static void deleteScratch(File file) throws IOException {
        boolean directory;
        try { directory = android.system.OsConstants.S_ISDIR(android.system.Os.lstat(file.getAbsolutePath()).st_mode); }
        catch (android.system.ErrnoException e) { if (e.errno == android.system.OsConstants.ENOENT) return; throw new IOException("Cannot inspect tool workspace", e); }
        if (directory) { File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot inspect tool workspace"); for (File child : children) deleteScratch(child); }
        if (file.exists() && !file.delete()) throw new IOException("Tool workspace retained: " + file);
    }
}
