package com.termux.app.files.apk;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Native, local-only APK workspace. Launch with an app-readable local file_path extra. */
public final class ApkToolsActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Thread worker;
    private volatile boolean cancelRequested;
    private boolean homeScreen;
    private boolean busy, destroyed, dirty, modified;
    private File apk, scratch, importedStore;
    private static final String EXTRA_OUTPUT_DIRECTORY = "file_output_directory";
    private String outputDirectory, rawKind;
    private LinearLayout root, body;
    private TextView heading, status;
    private EditText editor;
    private ProgressDialog progress;
    private Runnable editorReturn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private File draftsDirectory;
    private String workspaceFingerprint, editKind, editEntry, editDescriptor, draftLoadError;
    private Runnable draftSave;
    private String shownInstallConfirmation;
    private final Runnable installPoll = new Runnable() {
        public void run() {
            if (destroyed) return;
            String installStatus = ApkInstaller.status(ApkToolsActivity.this);
            if (!busy && !installStatus.isEmpty()) status.setText(installStatus);
            if (!busy) continueInstall(false);
            handler.postDelayed(this, 1000);
        }
    };
    private static final int PICK_KEYSTORE = 7001, INSTALL_SETTINGS = 7002;

    private interface Result<T> { void accept(T value) throws Exception; }
    private interface SourceCompiler { byte[] compile(String text) throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(12, 8, 12, 8);
        heading = new TextView(this); heading.setTextSize(18); root.addView(heading);
        status = new TextView(this); root.addView(status);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        String path = state == null ? getIntent().getStringExtra(ApkTools.EXTRA_FILE_PATH) : state.getString("apk_path");
        if (path != null) apk = new File(path);
        outputDirectory = state != null && state.containsKey(EXTRA_OUTPUT_DIRECTORY)
            ? state.getString(EXTRA_OUTPUT_DIRECTORY) : getIntent().getStringExtra(EXTRA_OUTPUT_DIRECTORY);
        // Keep the returned Intent's source/lease metadata and restored routing together.
        if (path != null) getIntent().putExtra(ApkTools.EXTRA_FILE_PATH, path);
        if (outputDirectory == null) getIntent().removeExtra(EXTRA_OUTPUT_DIRECTORY);
        else getIntent().putExtra(EXTRA_OUTPUT_DIRECTORY, outputDirectory);
        modified = state != null && state.getBoolean("apk_modified", false);
        setResult(modified ? RESULT_OK : RESULT_CANCELED, getIntent());
        scratch = new File(getCacheDir(), "apk-session-" + UUID.randomUUID());
        if (!scratch.mkdirs()) { error(new IOException("Cannot create private APK workspace")); finish(); return; }
        draftsDirectory = new File(getFilesDir(), "apk-editor-drafts");
        loadWorkspace();
    }

    private boolean packageSet() {
        if (apk == null) return false;
        String name = apk.getName().toLowerCase(Locale.ROOT);
        return apk.isDirectory() || name.endsWith(".apks") || name.endsWith(".xapk");
    }

    private void loadWorkspace() {
        rawKind = null;
        home();
        if (apk != null && !packageSet()) work("Checking opened file and saved draft", () -> {
            rawKind = sourceKind(apk);
            workspaceFingerprint = ApkZip.fingerprint(apk);
            draftLoadError = null;
            try { return ApkDrafts.load(draftsDirectory, apk); }
            catch (Exception e) { draftLoadError = e.getMessage(); return null; }
        }, draft -> {
            home();
            if (draftLoadError != null) {
                new AlertDialog.Builder(this).setTitle("Saved draft could not be read").setMessage(draftLoadError)
                    .setPositiveButton("Discard draft", (d, which) -> discardDraft())
                    .setNegativeButton("Close workspace", (d, which) -> finish()).setCancelable(false).show();
                return;
            }
            if (draft == null) return;
            boolean matching = workspaceFingerprint.equals(draft.fingerprint);
            AlertDialog.Builder prompt = new AlertDialog.Builder(this).setTitle("Recover APK editor draft")
                .setMessage(draft.entry + "\n" + (matching ? "Restore saved private editor text?" : "The APK changed. This draft cannot safely be compiled against it."))
                .setNegativeButton("Discard draft", (d, which) -> discardDraft())
                .setNeutralButton("Close workspace", (d, which) -> finish()).setCancelable(false);
            if (matching) prompt.setPositiveButton("Restore", (d, which) -> restoreDraft(draft));
            prompt.show();
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (apk != null) out.putString("apk_path", apk.getAbsolutePath());
        out.putBoolean("apk_modified", modified);
        out.putString(EXTRA_OUTPUT_DIRECTORY, outputDirectory);
        super.onSaveInstanceState(out);
    }

    private void page(String title) {
        homeScreen = false; body.removeAllViews(); heading.setText(title); editor = null; dirty = false; editorReturn = null;
    }

    private void home() {
        page(rawKind == null ? "APK tools" : rawKind + " tools"); homeScreen = true; status.setText(apk == null ? "No APK selected" : apk.getAbsolutePath());
        ScrollView scroll = new ScrollView(this); LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL); scroll.addView(actions); body.addView(scroll);
        if (rawKind != null) rawActions(actions);
        if (apk != null && !packageSet() && rawKind == null) {
            button(actions, "APK information, permissions and components", () -> work("Reading manifest", () -> ApkTools.metadata(this, apk), text -> text("APK information", text)));
            button(actions, "Verify signatures and certificates", () -> work("Verifying signatures", () -> ApkSignatures.report(apk), text -> text("Signature verification", text)));
            button(actions, "Browse ZIP / DEX / resources", this::zip);
            button(actions, "Compare with another APK", () -> path("Other APK", "", file -> work("Comparing inflated SHA-256", () -> ApkZip.diff(apk, file), lines -> text("ZIP diff", lines.isEmpty() ? "Identical entry contents" : join(lines)))));
            button(actions, "Test every ZIP entry (CRC)", () -> work("Testing ZIP", () -> { ApkZip.test(apk); return "Every ZIP entry passed CRC and size checks."; }, value -> text("ZIP test", value)));
            button(actions, "Re-sign with device-generated key", this::signDevice);
            button(actions, "Re-sign with imported user keystore", this::pickKeystore);
            button(actions, "Install APK through Android session", this::install);
        }
        if (apk != null && packageSet()) button(actions, "Install selected base + splits from this set", this::install);
        button(actions, "Install APK / APK directory / APKS / XAPK", () -> path("Local APK, package-set directory, APKS or XAPK", "", this::installSet));
        button(actions, "Continue Android install confirmation", () -> continueInstall(true));
        button(actions, "Extract installed app + all splits", this::installed);
        if (apk == null) button(actions, "Open a local APK", () -> path("APK path", "", file -> {
            apk = file; getIntent().putExtra(ApkTools.EXTRA_FILE_PATH, file.getAbsolutePath());
            outputDirectory = null; getIntent().removeExtra(EXTRA_OUTPUT_DIRECTORY); loadWorkspace();
        }));
        button(actions, "Open-source licenses", () -> work("Reading bundled licenses", () -> {
            int id = getResources().getIdentifier("apk_third_party_notices", "raw", getPackageName());
            if (id == 0) throw new IOException("APK dependency license resource was not integrated");
            try (InputStream in = getResources().openRawResource(id); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                ApkZip.copy(in, out, 1024 * 1024); return out.toString("UTF-8");
            }
        }, value -> text("APK tools: open-source licenses", value)));
        button(actions, "Close", this::finish);
    }

    private static String sourceKind(File file) throws IOException {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".dex")) return "DEX";
        if (name.endsWith(".arsc")) return "ARSC";
        try (InputStream in = new FileInputStream(file)) {
            int a = in.read(), b = in.read(), c = in.read(), d = in.read();
            if (a == 3 && b == 0 && c == 8 && d == 0) return "AXML";
            if (a == 'd' && b == 'e' && c == 'x' && d == '\n') return "DEX";
        }
        return null;
    }

    private byte[] rawBytes() throws IOException {
        ApkZip.requireFingerprint(apk, workspaceFingerprint);
        byte[] bytes = readRaw(apk);
        ApkZip.requireFingerprint(apk, workspaceFingerprint);
        return bytes;
    }

    private static byte[] readRaw(File input) throws IOException {
        try (InputStream in = new FileInputStream(input); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ApkZip.copy(in, out, ApkFormats.EDIT_LIMIT); return out.toByteArray();
        }
    }

    private void rawActions(LinearLayout actions) {
        if (rawKind.equals("DEX")) {
            button(actions, "DEX classes, members, strings and Smali tools", () ->
                work("Reading standalone DEX", this::rawBytes, bytes -> dexMenu(apk.getName(), bytes)));
        } else if (rawKind.equals("ARSC")) {
            button(actions, "Edit resource table as typed JSON", () -> work("Decoding standalone resources.arsc", () ->
                ApkFormats.decodeArsc(rawBytes()), json -> edit("ARSC typed JSON", json, apk.getName(),
                    ApkFormats::compileArsc, this::home, "ARSC", "")));
        } else {
            button(actions, "Read decoded binary XML", () -> work("Decoding standalone binary XML", () ->
                ApkFormats.xmlPreview(rawBytes()), xml -> text(apk.getName(), xml)));
            button(actions, "Edit binary XML as typed JSON", () -> work("Decoding standalone binary XML", () ->
                ApkFormats.decodeAxml(rawBytes()), json -> edit("AXML typed JSON", json, apk.getName(),
                    ApkFormats::compileAxml, this::home, "AXML", "")));
        }
    }

    private void button(LinearLayout target, String title, Runnable click) {
        Button b = new Button(this); b.setText(title); target.addView(b, new LinearLayout.LayoutParams(-1, -2));
        b.setOnClickListener(view -> { if (!busy) click.run(); });
    }

    private <T> void work(String label, Callable<T> action, Result<T> done) {
        if (busy || destroyed) return;
        busy = true; cancelRequested = false; status.setText(label);
        progress = new ProgressDialog(this); progress.setMessage(label); progress.setCancelable(false);
        progress.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> {
            status.setText("Cancellation requested; waiting for the format library to stop");
            cancelRequested = true; Thread active = worker; if (active != null) active.interrupt();
        });
        progress.show();
        executor.execute(() -> {
            worker = Thread.currentThread();
            T value = null; Exception failure = null;
            try {
                if (cancelRequested) throw new InterruptedIOException("Cancelled");
                ApkZip.checkCancelled(); value = action.call();
            }
            catch (Exception e) { failure = e; }
            catch (LinkageError e) { failure = new IOException("APK format dependency is missing or incompatible: " + e, e); }
            catch (OutOfMemoryError e) { failure = new IOException("Not enough memory for this format; use a smaller file", e); }
            worker = null; Thread.interrupted();
            final T result = value; final Exception problem = failure;
            runOnUiThread(() -> {
                busy = false;
                if (progress != null) { progress.dismiss(); progress = null; }
                if (destroyed) return;
                if (problem != null) { status.setText("Operation failed; original APK preserved"); error(problem); }
                else try { status.setText("Done"); done.accept(result); }
                catch (Exception e) { error(e); }
            });
        });
    }

    private void error(Throwable e) {
        if (!destroyed) new AlertDialog.Builder(this).setTitle("APK operation failed")
            .setMessage(e.getMessage() == null ? e.toString() : e.getMessage()).setPositiveButton("OK", null).show();
    }

    private void path(String title, String initial, Result<File> done) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(initial);
        new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", (dialog, which) -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) { error(new IOException("Enter a local path")); return; }
                try { done.accept(new File(value)); } catch (Exception e) { error(e); }
            }).show();
    }

    private void text(String title, String content) {
        if (content.length() > 2 * 1024 * 1024) {
            error(new IOException("Native text display limit is 2 MiB. Extract the entry to inspect a larger file.")); return;
        }
        page(title); button(body, rawKind == null ? "Back to APK tools" : "Back to " + rawKind + " tools", this::home);
        ScrollView scroll = new ScrollView(this); TextView view = new TextView(this);
        view.setTextIsSelectable(true); view.setTypeface(Typeface.MONOSPACE); view.setText(content);
        scroll.addView(view); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void list(String title, List<String> values, Result<String> selected, Runnable back) {
        page(title); button(body, "Back", back);
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint("Filter"); body.addView(search);
        ListView view = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, values);
        view.setAdapter(adapter); body.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { adapter.getFilter().filter(s); }
            public void afterTextChanged(Editable s) {}
        });
        view.setOnItemClickListener((parent, row, position, id) -> {
            if (busy) return;
            try { selected.accept(adapter.getItem(position)); } catch (Exception e) { error(e); }
        });
    }

    private void zip() {
        work("Reading ZIP directory", () -> ApkZip.list(apk), entries -> {
            List<String> values = new ArrayList<>(); for (ApkZip.Entry e : entries) values.add(e.name);
            list("ZIP entries (" + entries.size() + ")", values, name -> {
                if (name.endsWith("/")) return;
                new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Open DEX / binary XML / resources", "Extract entry", "Replace with local file", "Delete entry"}, (dialog, index) -> {
                    if (index == 0) inspect(name);
                    else if (index == 1) newOutputPath("Extract entry to new file", new File(apk.getParentFile(), new File(name).getName()),
                        output -> work("Extracting entry", () -> { ApkZip.extract(apk, name, output); return output; }, file -> text("Extracted", file.getAbsolutePath())));
                    else if (index == 2) path("Replacement local file", "", replacement -> outputPath(output ->
                        work("Writing unsigned APK", () -> { ApkZip.rewrite(apk, output, Collections.singletonMap(name, replacement), Collections.emptySet()); return output; }, this::outputReady)));
                    else outputPath(output -> work("Deleting entry and stripping old signatures", () -> {
                        ApkZip.rewrite(apk, output, Collections.emptyMap(), Collections.singleton(name)); return output;
                    }, this::outputReady));
                }).show();
            }, this::home);
        });
    }

    private void inspect(String name) {
        if (name.endsWith(".dex")) {
            work("Reading DEX", () -> ApkZip.read(apk, name, ApkFormats.EDIT_LIMIT), bytes -> dexMenu(name, bytes));
        } else if (name.equals("resources.arsc")) {
            work("Decoding resources.arsc", () -> ApkFormats.decodeArsc(ApkZip.read(apk, name, ApkFormats.EDIT_LIMIT)),
                json -> edit("ARSC typed JSON", json, name, ApkFormats::compileArsc, this::zip, "ARSC", ""));
        } else if (name.endsWith(".xml")) {
            work("Decoding binary XML", () -> ApkZip.read(apk, name, ApkFormats.EDIT_LIMIT), bytes ->
                new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Read decoded XML", "Edit typed JSON and compile binary XML", "Edit source XML and compile binary XML"}, (d, index) -> {
                    if (index == 0) work("Decoding XML", () -> ApkFormats.xmlPreview(bytes), xml -> text(name, xml));
                    else if (index == 1) work("Decoding typed XML", () -> ApkFormats.decodeAxml(bytes), json -> edit("AXML typed JSON", json, name, ApkFormats::compileAxml, this::zip, "AXML", ""));
                    else work("Resolving XML resource symbols", () -> ApkSourceXml.decode(apk, name), xml ->
                        edit("Source XML", xml, name, value -> ApkSourceXml.compile(apk, value), this::zip, "XML", ""));
                }).show());
        } else error(new IOException("This entry is not DEX, binary Android XML or resources.arsc. Use Extract or Replace for other files."));
    }

    private void dexMenu(String name, byte[] bytes) {
        Runnable back = rawKind == null ? this::zip : this::home;
        new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Browse classes and members / edit Smali", "Browse string pool", "Export all classes as Smali", "Assemble Smali directory and replace DEX"}, (dialog, index) -> {
            if (index == 0) work("Indexing DEX classes", () -> ApkFormats.classes(bytes), values ->
                list(name + " classes", values, type -> classMenu(name, bytes, type), back));
            else if (index == 1) work("Reading DEX strings", () -> ApkFormats.strings(bytes), values ->
                list(name + " strings", values, value -> new AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK", null).show(), back));
            else if (index == 2) newOutputPath("New Smali directory", new File(apk.getAbsolutePath() + (rawKind == null ? "-" + name : "") + "-smali"), output ->
                work("Disassembling DEX", () -> { ApkFormats.disassemble(bytes, output); return output; }, file -> text("Smali exported", file.getAbsolutePath())));
            else path("Smali source directory", "", input -> outputPath(output -> work(rawKind == null ? "Assembling DEX and writing unsigned APK" : "Assembling standalone DEX", () -> {
                ApkZip.requireFingerprint(apk, workspaceFingerprint);
                if (rawKind != null) {
                    ApkFormats.assemble(input, output, ApkFormats.dex(bytes).getOpcodes().api); return output;
                }
                File dex = new File(scratch, "assembled-" + UUID.randomUUID() + ".dex");
                try {
                    ApkFormats.assemble(input, dex, ApkFormats.dex(bytes).getOpcodes().api);
                    ApkZip.rewrite(apk, output, Collections.singletonMap(name, dex), Collections.emptySet()); return output;
                } finally { dex.delete(); }
            }, this::outputReady)));
        }).show();
    }

    private void classMenu(String name, byte[] bytes, String type) {
        new AlertDialog.Builder(this).setTitle(type).setItems(new String[]{"Members", "Edit Smali and rebuild DEX"}, (dialog, which) -> {
            if (which == 0) work("Reading members", () -> ApkFormats.members(bytes, type), members ->
                list(type + " members", members, member -> new AlertDialog.Builder(this).setMessage(member).setPositiveButton("OK", null).show(), () -> dexMenu(name, bytes)));
            else work("Disassembling class", () -> ApkFormats.smali(bytes, type), source ->
                edit(type, source, name, value -> ApkFormats.replaceClass(bytes, type, value, scratch), rawKind == null ? this::zip : this::home, "SMALI", type));
        }).show();
    }

    private void edit(String title, String initial, String entry, SourceCompiler compiler, Runnable back, String kind, String descriptor) {
        editKind = kind; editEntry = entry; editDescriptor = descriptor;
        if (initial.length() > 2 * 1024 * 1024) { error(new IOException("Native editor limit is 2 MiB text. Export/replace the entry for larger edits.")); return; }
        page(title); editorReturn = back;
        TextView help = new TextView(this); help.setText(rawKind == null ? "Compile creates a separate unsigned APK. Apply commits it to the opened file. Keep class descriptors/resource IDs consistent." : "Compile creates a separate " + rawKind + " file. The opened file stays unchanged. Keep class descriptors/resource IDs consistent."); body.addView(help);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); body.addView(actions);
        Button save = new Button(this); save.setText(rawKind == null ? "Compile to new APK" : "Compile to new " + rawKind); actions.addView(save);
        Button cancel = new Button(this); cancel.setText("Back"); actions.addView(cancel); cancel.setOnClickListener(v -> leaveEditor(back));
        editor = new EditText(this); editor.setTypeface(Typeface.MONOSPACE); editor.setTextSize(13);
        editor.setGravity(Gravity.TOP | Gravity.START); editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSaveEnabled(false); editor.setText(initial); body.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout findRow = new LinearLayout(this); findRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText find = new EditText(this); find.setSingleLine(true); find.setHint("Find literal text");
        findRow.addView(find, new LinearLayout.LayoutParams(0, -2, 1));
        Button next = new Button(this); next.setText("Find next"); findRow.addView(next);
        body.addView(findRow, 2);
        next.setOnClickListener(v -> {
            String needle = find.getText().toString(); if (needle.isEmpty()) return;
            String haystack = editor.getText().toString();
            int position = haystack.indexOf(needle, Math.max(0, editor.getSelectionEnd()));
            if (position < 0) position = haystack.indexOf(needle);
            if (position < 0) { status.setText("Text not found"); return; }
            editor.requestFocus(); editor.setSelection(position, position + needle.length());
        });
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                dirty = true;
                if (draftSave != null) handler.removeCallbacks(draftSave);
                draftSave = () -> persistDraft(); handler.postDelayed(draftSave, 500);
            }
            public void afterTextChanged(Editable s) {}
        });
        save.setOnClickListener(v -> {
            if (busy) return;
            final String source = editor.getText().toString();
            outputPath(output -> work(rawKind == null ? "Compiling binary format and rebuilding unsigned APK" : "Compiling standalone " + rawKind, () -> {
                ApkZip.requireFingerprint(apk, workspaceFingerprint);
                byte[] compiled = compiler.compile(source);
                ApkZip.requireFingerprint(apk, workspaceFingerprint);
                if (rawKind != null) { ApkZip.writeNew(output, compiled); return output; }
                File replacement = File.createTempFile("apk-compiled-", ".bin", scratch);
                try {
                    try (FileOutputStream out = new FileOutputStream(replacement)) { out.write(compiled); out.getFD().sync(); }
                    ApkZip.rewrite(apk, output, Collections.singletonMap(entry, replacement), Collections.emptySet()); return output;
                } finally { replacement.delete(); }
            }, this::outputReady));
        });
    }

    private void leaveEditor(Runnable back) {
        if (busy) return;
        if (!dirty) { back.run(); return; }
        new AlertDialog.Builder(this).setMessage("Discard unsaved changes?").setNegativeButton("Keep editing", null)
            .setPositiveButton("Discard", (dialog, which) -> { dirty = false; discardDraft(); back.run(); }).show();
    }

    // Only explicit staged-source routing changes defaults. Local borrowed files keep theirs.
    private static File defaultOutput(File original, String directory) throws IOException {
        if (directory == null) return original;
        File parent = new File(directory);
        if (directory.isEmpty() || !parent.isAbsolute()) throw new IOException("Output directory must be an absolute local path: " + directory);
        return new File(parent, original.getName());
    }

    private static void prepareOutput(File output, String directory) throws IOException {
        if (!output.isAbsolute()) throw new IOException("Enter an absolute local output path");
        File parent = output.getParentFile();
        if (directory != null && parent != null && parent.getCanonicalFile().equals(new File(directory).getCanonicalFile())) {
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
                throw new IOException("Cannot create output directory: " + parent);
        }
        if (parent == null || !parent.isDirectory() || !parent.canWrite())
            throw new IOException("Output directory is not writable: " + parent);
        // Also refuse dangling symlinks; format writers retain their own publish checks.
        if (java.nio.file.Files.exists(output.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Output already exists: " + output);
    }

    private void newOutputPath(String title, File original, Result<File> action) {
        newOutputPath(title, original, outputDirectory, action);
    }

    private void newOutputPath(String title, File original, String directory, Result<File> action) {
        try {
            path(title, defaultOutput(original, directory).getAbsolutePath(), output -> {
                prepareOutput(output, directory); action.accept(output);
            });
        } catch (Exception e) { error(e); }
    }

    private static File editedOutput(File input, String kind) {
        if (kind == null) return new File(input.getAbsolutePath().replaceFirst("(?i)\\.apk$", "") + "-edited.apk");
        String name = input.getName(); int dot = name.lastIndexOf('.');
        String edited = dot > 0 ? name.substring(0, dot) + "-edited" + name.substring(dot)
            : name + "-edited." + kind.toLowerCase(Locale.ROOT);
        return new File(input.getParentFile(), edited);
    }

    private void outputPath(Result<File> action) {
        newOutputPath("New output " + (rawKind == null ? "APK" : rawKind) + " (must not exist)", editedOutput(apk, rawKind), action);
    }

    private void outputReady(File output) { outputReady(output, false); }

    private void outputReady(File output, boolean signed) {
        if (rawKind != null) {
            new AlertDialog.Builder(this).setTitle(rawKind + " output written")
                .setMessage(output.getAbsolutePath() + "\nSeparate output; the opened file is unchanged.")
                .setPositiveButton("Keep separate output", null).show();
            return;
        }
        File idsig = new File(output.getPath() + ".idsig");
        if (signed && idsig.isFile()) {
            new AlertDialog.Builder(this).setTitle("Signed APK + v4 sidecar verified")
                .setMessage(output.getAbsolutePath() + "\n" + idsig.getAbsolutePath()
                    + "\nBoth files are separate outputs. Keep them together. The opened file has not changed. Pair apply is unavailable because two-file replacement is not atomic.")
                .setPositiveButton("Keep separate APK + .idsig", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(signed ? "Signed APK: requested schemes verified" : "Unsigned APK copy written")
            .setMessage(output.getAbsolutePath() + "\nApply this copy to the opened file to commit the edit. The opened file is preserved if applying fails."
                + (signed ? "" : "\nRe-sign before installing an edited APK."))
            .setNegativeButton("Keep separate copy", null)
            .setNeutralButton("Apply and stay", (dialog, which) -> applyOutput(output, false))
            .setPositiveButton("Apply and return", (dialog, which) -> applyOutput(output, true)).show();
    }

    private void applyOutput(File output, boolean close) {
        work("Atomically applying APK to the opened file", () -> {
            if (new File(apk.getPath() + ".idsig").exists()) throw new IOException("Opened APK has a detached .idsig. Keep separate outputs to avoid leaving a stale sidecar.");
            workspaceFingerprint = ApkZip.apply(output, apk, workspaceFingerprint); return apk;
        }, file -> {
            modified = true; dirty = false; discardDraft(); setResult(RESULT_OK, getIntent());
            if (close) finish(); else home();
        });
    }

    private void signDevice() {
        new AlertDialog.Builder(this).setTitle("Sign with device key")
            .setMessage("Generate or reuse your device's private APK key. It cannot be exported and is lost if VeyTerm app data is deleted. Android will reject updates signed with a different key.")
            .setNegativeButton("Cancel", null).setPositiveButton("Choose schemes", (dialog, which) ->
                work("Loading AndroidKeyStore identity", () -> ApkTools.deviceIdentity(this), this::signWith)).show();
    }

    private void signWith(ApkSignatures.Identity identity) {
        new AlertDialog.Builder(this).setTitle("Signature schemes")
            .setItems(new String[]{"v1 + v2", "v1 + v2 + v3", "v1 + v2 + v3 and detached v4 .idsig"}, (d, index) ->
                newOutputPath("New signed APK (must not exist)", new File(apk.getAbsolutePath().replaceFirst("(?i)\\.apk$", "") + "-signed.apk"), output -> {
                    prepareOutput(new File(output.getPath() + ".idsig"), outputDirectory);
                    Runnable sign = () -> work("Signing and verifying requested schemes", () -> {
                        ApkZip.requireFingerprint(apk, workspaceFingerprint);
                        ApkSignatures.sign(apk, output, identity, index >= 1, index == 2); return output;
                    }, this::signedReady);
                    if (index == 2) new AlertDialog.Builder(this).setTitle("Create two separate outputs")
                        .setMessage(output.getAbsolutePath() + "\n" + output.getAbsolutePath() + ".idsig\nKeep both files together. The original will stay unchanged.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Create and verify both", (confirm, which) -> sign.run()).show();
                    else sign.run();
                })).show();
    }
    private void signedReady(File output) { outputReady(output, true); }

    private void pickKeystore() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(pick, PICK_KEYSTORE); } catch (ActivityNotFoundException e) { error(e); }
    }

    private EditText field(LinearLayout form, String hint, boolean password) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint(hint);
        if (password) { input.setInputType(129); input.setTransformationMethod(PasswordTransformationMethod.getInstance()); }
        input.setSaveEnabled(false); form.addView(input); return input;
    }

    private void importForm(File store) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        EditText type = field(form, "Keystore type: PKCS12 / BKS / JKS", false); type.setText("PKCS12");
        EditText alias = field(form, "Private-key alias", false);
        EditText storePass = field(form, "Store password", true), keyPass = field(form, "Key password", true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Import user signing identity").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Load key", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String storeType = type.getText().toString().trim(), keyAlias = alias.getText().toString().trim();
            char[] sp = new char[storePass.length()], kp = new char[keyPass.length()];
            storePass.getText().getChars(0, sp.length, sp, 0); keyPass.getText().getChars(0, kp.length, kp, 0);
            storePass.setText(""); keyPass.setText(""); dialog.dismiss();
            work("Loading user keystore", () -> {
                try { return ApkSignatures.importKey(store, storeType, sp, keyAlias, kp); }
                finally { Arrays.fill(sp, '\0'); Arrays.fill(kp, '\0'); store.delete(); importedStore = null; }
            }, this::signWith);
        }));
        dialog.setOnCancelListener(d -> { store.delete(); importedStore = null; });
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> { storePass.setText(""); keyPass.setText(""); store.delete(); importedStore = null; dialog.dismiss(); });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_KEYSTORE && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            work("Reading selected keystore locally", () -> {
                File store = File.createTempFile("apk-import-", ".keystore", scratch);
                try {
                    try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(store)) {
                        if (in == null) throw new IOException("Cannot read selected keystore"); ApkZip.copy(in, out, 8 * 1024 * 1024);
                    }
                    return store;
                } catch (Exception e) { store.delete(); throw e; }
            }, store -> { importedStore = store; importForm(store); });
        } else if (request == INSTALL_SETTINGS) status.setText("Install permission settings returned. Tap Install again to continue.");
    }

    private void installed() {
        work("Reading visible installed packages", () -> ApkTools.installedApps(this), apps -> {
            Map<String, ApkTools.InstalledApp> lookup = new HashMap<>(); List<String> labels = new ArrayList<>();
            for (ApkTools.InstalledApp app : apps) { labels.add(app.toString()); lookup.put(app.toString(), app); }
            list("Installed apps (Android package visibility applies)", labels, label -> {
                ApkTools.InstalledApp app = lookup.get(label);
                File external = apk == null ? new File(getFilesDir(), "home") : getExternalFilesDir(null);
                if (external == null) external = getFilesDir();
                // With no input, Home is also a visible default and may need creating.
                String directory = outputDirectory != null ? outputDirectory : apk == null ? external.getAbsolutePath() : null;
                newOutputPath("New output directory for base + every split", new File(external, app.packageName + "-apks"), directory, output ->
                    work("Extracting base and " + app.splitCount + " splits", () -> ApkTools.extractInstalled(this, app.packageName, output), files -> {
                        List<String> paths = new ArrayList<>(); for (File f : files) paths.add(f.getAbsolutePath());
                        text("Extracted installed APK set", join(paths) + "\nUse Install APK / APK directory / APKS / XAPK to install this directory.");
                    }));
            }, this::home);
        });
    }

    private void install() { installSet(apk); }

    private void installSet(File input) {
        if (Build.VERSION.SDK_INT >= 26 && !Api26.canInstall(this)) {
            try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), INSTALL_SETTINGS); }
            catch (ActivityNotFoundException e) { error(e); } return;
        }
        File directory = new File(scratch, "install-" + UUID.randomUUID());
        work("Copying and verifying package-set signatures", () -> ApkPackageSet.prepare(input, directory), parts -> {
            String[] labels = new String[parts.size()]; boolean[] checked = new boolean[parts.size()];
            for (int i = 0; i < parts.size(); i++) { labels[i] = parts.get(i).toString(); checked[i] = true; }
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Select ONE base + matching splits")
                .setMultiChoiceItems(labels, checked, (d, which, value) -> checked[which] = value)
                .setNegativeButton("Cancel", (d, which) -> queueScratchCleanup(directory))
                .setPositiveButton("Validate selection", null).create();
            dialog.setOnCancelListener(d -> queueScratchCleanup(directory));
            dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<ApkPackageSet.Part> selected = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(parts.get(i));
                work("Validating package/version/signer/split dependencies", () ->
                    ApkPackageSet.validate(selected, Build.VERSION.SDK_INT, Arrays.asList(Build.SUPPORTED_ABIS)), validated -> {
                    dialog.dismiss();
                    new AlertDialog.Builder(this).setTitle("Submit package set to Android?")
                        .setMessage(validated.get(0).packageName + "\n" + validated.size() + " APK(s). Android will request confirmation and report the final result.")
                        .setNegativeButton("Cancel", (confirm, which) -> queueScratchCleanup(directory))
                        .setOnCancelListener(confirm -> queueScratchCleanup(directory))
                        .setPositiveButton("Install through Android", (confirm, which) -> work("Writing Android installation session", () -> {
                            try { return ApkInstaller.install(this, validated); }
                            finally { ApkFormats.deleteScratch(directory); }
                        }, id -> status.setText("Session " + id + " submitted. Installation is not yet confirmed."))).show();
                });
            }));
            dialog.show();
        });
    }

    private void queueScratchCleanup(File directory) { executor.execute(() -> ApkFormats.deleteScratch(directory)); }

    private void continueInstall(boolean explicit) {
        try {
            Intent confirmation = ApkInstaller.confirmation(this);
            if (confirmation == null) { if (explicit) status.setText(ApkInstaller.status(this)); return; }
            String key = confirmation.toUri(Intent.URI_INTENT_SCHEME);
            if (!explicit && key.equals(shownInstallConfirmation)) return;
            shownInstallConfirmation = key; startActivity(confirmation);
        } catch (Exception e) { if (explicit) error(e); }
    }

    private static final class Api26 {
        static boolean canInstall(Context context) { return context.getPackageManager().canRequestPackageInstalls(); }
    }

    private void persistDraft() {
        if (editor == null || !dirty || workspaceFingerprint == null || executor.isShutdown()) return;
        try {
            ApkDrafts.Draft draft = new ApkDrafts.Draft(apk, workspaceFingerprint, editKind, editEntry, editDescriptor, editor.getText().toString());
            final File opened = apk;
            executor.execute(() -> {
                try { ApkDrafts.save(draftsDirectory, opened, draft); }
                catch (Exception e) { runOnUiThread(() -> { if (!destroyed) status.setText("Draft was not saved: " + e.getMessage()); }); }
            });
        } catch (Exception e) { error(e); }
    }

    private void discardDraft() {
        if (draftSave != null) handler.removeCallbacks(draftSave);
        File opened = apk;
        executor.execute(() -> {
            try { ApkDrafts.discard(draftsDirectory, opened); }
            catch (Exception e) { runOnUiThread(() -> error(e)); }
        });
    }

    private void restoreDraft(ApkDrafts.Draft draft) {
        work("Checking draft fingerprint", () -> {
            if (!draft.matches(apk)) throw new IOException("APK changed; draft restore refused");
            return draft.kind.equals("SMALI") ? (rawKind == null ? ApkZip.read(apk, draft.entry, ApkFormats.EDIT_LIMIT) : rawBytes()) : null;
        }, bytes -> {
            SourceCompiler compiler;
            switch (draft.kind) {
                case "ARSC": compiler = ApkFormats::compileArsc; break;
                case "AXML": compiler = ApkFormats::compileAxml; break;
                case "XML": compiler = source -> ApkSourceXml.compile(apk, source); break;
                case "SMALI": compiler = source -> ApkFormats.replaceClass(bytes, draft.descriptor, source, scratch); break;
                default: throw new IOException("Unknown saved editor mode");
            }
            edit("Recovered " + draft.kind, draft.text, draft.entry, compiler, rawKind == null ? this::zip : this::home, draft.kind, draft.descriptor);
            dirty = true;
        });
    }

    @Override protected void onResume() {
        super.onResume(); handler.removeCallbacks(installPoll); handler.post(installPoll);
    }
    @Override protected void onPause() {
        persistDraft(); handler.removeCallbacks(installPoll); super.onPause();
    }

    private static String join(List<String> lines) { StringBuilder result = new StringBuilder(); for (String line : lines) result.append(line).append('\n'); return result.toString(); }

    @Override public void onBackPressed() {
        if (busy) { status.setText("Cancel the running operation before leaving"); return; }
        if (editor != null) leaveEditor(editorReturn == null ? this::home : editorReturn);
        else if (homeScreen) finish(); else home();
    }

    @Override public void finish() {
        setResult(modified ? RESULT_OK : RESULT_CANCELED, getIntent());
        super.finish();
    }

    @Override protected void onDestroy() {
        persistDraft();
        if (draftSave != null) handler.removeCallbacks(draftSave);
        handler.removeCallbacks(installPoll);
        destroyed = true; cancelRequested = true; Thread active = worker; if (active != null) active.interrupt();
        if (progress != null) { progress.dismiss(); progress = null; }
        // Queue cleanup AFTER the active writer; deleting scratch while a library writes can lose artifacts.
        executor.execute(() -> ApkFormats.deleteScratch(scratch)); executor.shutdown();
        super.onDestroy();
    }
}
