package com.termux.app.files.decompile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import com.termux.R;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native read-only local APK/DEX browser. Registration/routing belong to the parent integration. */
public final class DecompiledJavaActivity extends Activity {
    public static final String EXTRA_FILE_PATH = "file_path";
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vey-file-decompiler"); thread.setDaemon(true); return thread;
    });
    private final List<View> actions = new ArrayList<>();
    private final List<Long> previousPages = new ArrayList<>();
    private volatile Thread running;
    private volatile Job cancellation;
    private volatile boolean destroyed;
    private boolean busy, viewingSource;
    private File input;
    // session is owned only by the worker; source/results are immutable result objects.
    private DecompilerSession session;
    private DecompilerSession.Source source;
    private SourcePages.Page page;
    private DecompilerSession.Results results;
    private DecompilerSession.Search searchMode = DecompilerSession.Search.CLASSES;
    private String searchQuery = "", selectedClass;
    private int searchOffset;
    private LinearLayout searchRow;
    private FrameLayout content;
    private TextView status, code;
    private EditText query;
    private Spinner mode;
    private ListView list;
    private View codeScroll;
    private Button cancelButton;

    private static final class Job implements DecompilerSession.Cancellation {
        final AtomicBoolean cancelled = new AtomicBoolean();
        public void check() {
            if (cancelled.get()) throw new CancellationException("Cancelled");
            DecompilerSession.THREAD_CANCEL.check();
        }
    }
    private interface Result<T> { void accept(T result) throws Exception; }

    @Override protected void onCreate(Bundle state) {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        setResult(RESULT_CANCELED, getIntent());
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (path == null || path.isEmpty()) { Toast.makeText(this, "Missing local file_path", Toast.LENGTH_LONG).show(); finish(); return; }
        input = new File(path);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.dec_surface));
        TextView title = label(input.getName() + " · Java"); title.setTextSize(18);
        title.setPadding(dp(12), dp(8), dp(12), dp(8)); root.addView(title);
        HorizontalScrollView toolbarScroll = new HorizontalScrollView(this);
        LinearLayout toolbar = new LinearLayout(this); toolbarScroll.addView(toolbar); root.addView(toolbarScroll);
        button(toolbar, "Classes", () -> showList());
        button(toolbar, "Previous", () -> previous());
        button(toolbar, "Next", () -> next());
        button(toolbar, "Export class", () -> export(0));
        button(toolbar, "Export selection", () -> export(1));
        button(toolbar, "Export all ZIP", () -> export(2));
        button(toolbar, "Reopen", () -> reopen(null));
        cancelButton = new Button(this); cancelButton.setText("Cancel"); cancelButton.setEnabled(false);
        cancelButton.setTextColor(color(R.color.dec_text));
        cancelButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.dec_control)));
        cancelButton.setOnClickListener(v -> cancel()); toolbar.addView(cancelButton);
        searchRow = new LinearLayout(this);
        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Classes", "Strings", "References"}));
        searchRow.addView(mode); actions.add(mode);
        query = new EditText(this); query.setSingleLine(true); query.setTextColor(color(R.color.dec_text));
        query.setHintTextColor(color(R.color.dec_secondary)); query.setHint("Literal search in all DEX");
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1024)});
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH && !busy) { newSearch(); return true; } return false; });
        searchRow.addView(query, new LinearLayout.LayoutParams(0, -2, 1)); actions.add(query);
        button(searchRow, "Find", () -> newSearch()); root.addView(searchRow);
        status = label("Opening…"); status.setTextColor(color(R.color.dec_secondary)); status.setTextSize(12);
        status.setPadding(dp(12), dp(6), dp(12), dp(6)); root.addView(status);
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new ListView(this); list.setBackgroundColor(color(R.color.dec_surface));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (!busy && results != null && position < results.hits.size()) openClass(results.hits.get(position).className, 0);
        });
        content.addView(list, new FrameLayout.LayoutParams(-1, -1)); actions.add(list);
        code = label(""); code.setTextIsSelectable(true); code.setTypeface(Typeface.MONOSPACE); code.setTextSize(14);
        code.setHorizontallyScrolling(true); code.setPadding(dp(12), dp(8), dp(12), dp(16));
        ScrollView vertical = new ScrollView(this); vertical.addView(code, new ScrollView.LayoutParams(-2, -2));
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.addView(vertical, new HorizontalScrollView.LayoutParams(-2, -1)); codeScroll = horizontal;
        content.addView(codeScroll, new FrameLayout.LayoutParams(-1, -1)); codeScroll.setVisibility(View.GONE);
        setContentView(root);
        if (state != null) {
            mode.setSelection(Math.max(0, Math.min(2, state.getInt("dec_mode"))));
            query.setText(state.getString("dec_query", ""));
            searchQuery = state.getString("dec_search_query", "");
            searchMode = DecompilerSession.Search.values()[Math.max(0, Math.min(2, state.getInt("dec_search_mode")))];
            searchOffset = Math.max(0, state.getInt("dec_offset"));
        }
        reopen(state);
    }

    private void reopen(Bundle restore) {
        if (busy) return;
        source = null; page = null; results = null; selectedClass = null; previousPages.clear();
        showList();
        list.setAdapter(null);
        background("Loading DEX index with JADX 1.5.6…", job -> {
            closeSession();
            session = DecompilerSession.open(input, getCacheDir(), job);
            return session.dexCount() + " DEX · " + session.classCount() + " classes";
        }, summary -> {
            status.setText("Read only · " + summary);
            if (restore != null && restore.getString("dec_class") != null) {
                // Rebuild the list first, then reopen the prior source at its explicit byte offset.
                search(searchOffset, () -> openClass(restore.getString("dec_class"), restore.getLong("dec_source_offset", 0)));
            } else search(searchOffset, null);
        });
    }

    private void newSearch() {
        searchMode = DecompilerSession.Search.values()[mode.getSelectedItemPosition()];
        searchQuery = query.getText().toString(); searchOffset = 0;
        search(0, null);
    }
    private void search(int offset, Runnable after) {
        final String text = searchQuery; final DecompilerSession.Search kind = searchMode;
        background("Searching all DEX " + kind.name().toLowerCase(java.util.Locale.ROOT) + "…", job -> {
            requireSession(); return session.search(kind, text, offset, job);
        }, value -> {
            results = value; searchOffset = offset;
            ArrayAdapter<DecompilerSession.Hit> adapter = new ArrayAdapter<DecompilerSession.Hit>(this, android.R.layout.simple_list_item_1, value.hits) {
                @Override public View getView(int position, View convert, ViewGroup parent) {
                    TextView row = (TextView) super.getView(position, convert, parent);
                    row.setTextColor(color(R.color.dec_text)); row.setTextSize(14); row.setPadding(dp(12), dp(10), dp(12), dp(10)); return row;
                }
            };
            list.setAdapter(adapter); showList();
            if (after != null) after.run();
        });
    }
    private void showList() {
        viewingSource = false;
        if (list == null) return;
        list.setVisibility(View.VISIBLE); codeScroll.setVisibility(View.GONE); searchRow.setVisibility(View.VISIBLE);
        if (results != null) status.setText("Read only · " + searchMode + " · results " +
            (results.hits.isEmpty() ? "0" : (results.offset + 1) + "–" + (results.offset + results.hits.size())) +
            (results.hasMore ? " · more: Next" : " · end") +
            (searchMode == DecompilerSession.Search.STRINGS ? "\nString constants in fields/instructions; tap to open class" :
             searchMode == DecompilerSession.Search.REFERENCES ? "\nDEX type/member references; not reflective/runtime references" : "\nTap a class to decompile"));
    }
    private void openClass(String name, long offset) {
        source = null; page = null; selectedClass = null; previousPages.clear();
        background("Decompiling " + name + "…", job -> {
            requireSession();
            DecompilerSession.Source value = session.decompile(name, job);
            SourcePages.Page first = SourcePages.read(value.file, Math.min(offset, value.file.length()));
            job.check(); return new Object[]{value, first};
        }, value -> {
            source = (DecompilerSession.Source) value[0]; selectedClass = name;
            showPage((SourcePages.Page) value[1]);
        });
    }
    private void showPage(SourcePages.Page value) {
        page = value; viewingSource = true;
        searchRow.setVisibility(View.GONE); list.setVisibility(View.GONE); codeScroll.setVisibility(View.VISIBLE);
        code.setText(value.text);
        ((HorizontalScrollView) codeScroll).scrollTo(0, 0);
        ((ScrollView) ((HorizontalScrollView) codeScroll).getChildAt(0)).scrollTo(0, 0);
        status.setText("Read only · " + source.className + "\nUTF-8 bytes [" + value.start + ", " + value.end + ") / " + value.total +
            (value.end < value.total ? " · more: Next" : " · end") +
            "\nJADX diagnostics this session: " + source.errors + " errors, " + source.warnings + " warnings. Inspect source comments; export keeps all pages.");
    }
    private void next() {
        if (!viewingSource) { if (results != null && results.hasMore) search(searchOffset + DecompilerSession.RESULT_PAGE_SIZE, null); return; }
        if (page == null || page.end >= page.total) return;
        final long before = page.start, next = page.end;
        background("Reading next source page…", job -> { job.check(); return SourcePages.read(source.file, next); }, value -> {
            previousPages.add(before); showPage(value);
        });
    }
    private void previous() {
        if (!viewingSource) { if (searchOffset > 0) search(Math.max(0, searchOffset - DecompilerSession.RESULT_PAGE_SIZE), null); return; }
        if (page == null || page.start == 0) return;
        // After recreation, locate the preceding UTF-8 page without storing the whole source in Bundle.
        final long target = page.start;
        background("Reading previous source page…", job -> {
            long offset = 0;
            if (!previousPages.isEmpty()) offset = previousPages.get(previousPages.size() - 1);
            else {
                while (true) {
                    job.check(); SourcePages.Page candidate = SourcePages.read(source.file, offset);
                    if (candidate.end >= target || candidate.end == candidate.start) break;
                    offset = candidate.end;
                }
            }
            job.check(); return SourcePages.read(source.file, offset);
        }, value -> { if (!previousPages.isEmpty()) previousPages.remove(previousPages.size() - 1); showPage(value); });
    }

    private void export(int kind) {
        if (kind != 2 && source == null) { status.setText("Open a class first"); return; }
        final String selection;
        if (kind == 1) {
            int a = Math.min(code.getSelectionStart(), code.getSelectionEnd()), b = Math.max(code.getSelectionStart(), code.getSelectionEnd());
            if (!viewingSource || a < 0 || b <= a) { status.setText("Long press and select source text on this page first"); return; }
            selection = code.getText().subSequence(a, b).toString();
        } else selection = null;
        EditText destination = new EditText(this); destination.setSingleLine(true);
        destination.setTextColor(color(R.color.dec_text));
        File parent = getExternalFilesDir(null); if (parent == null) parent = getFilesDir();
        String name = kind == 2 ? input.getName() + "-sources.zip" :
            source.className.substring(source.className.lastIndexOf('.') + 1) + (kind == 1 ? "-selection.txt" : ".java");
        destination.setText(new File(parent, name).getAbsolutePath()); destination.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(kind == 2 ? "Export all Java sources" : kind == 1 ? "Export selected text" : "Export complete class")
            .setMessage("Choose a new absolute path in an existing app-writable directory. Existing files are refused. The original APK/DEX stays unchanged.")
            .setView(destination).setNegativeButton("Cancel", null).setPositiveButton("Export", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            File output = new File(destination.getText().toString());
            dialog.dismiss();
            background("Exporting complete output…", job -> {
                requireSession();
                if (kind == 2) return session.exportAll(output, job, (done, total) -> {
                    if (done == 1 || done % 10 == 0 || done == total) runOnUiThread(() -> {
                        if (!destroyed && cancellation == job && !job.cancelled.get()) status.setText("Exporting " + done + " / " + total + " classes…");
                    });
                }) + " Java source files → " + output;
                if (kind == 1) session.exportSelection(selection, output, job);
                else session.exportSource(source, output, job);
                return "Exported " + output.length() + " bytes → " + output;
            }, message -> status.setText(message + "\nOriginal is unchanged · read only"));
        }));
        dialog.show();
    }

    private interface Work<T> { T call(Job cancellation) throws Exception; }
    private <T> void background(String message, Work<T> work, Result<T> result) {
        if (busy || destroyed) return;
        busy = true; status.setText(message); enable(false); cancelButton.setEnabled(true);
        Job job = new Job(); cancellation = job;
        worker.execute(() -> {
            running = Thread.currentThread();
            try {
                job.check(); T value = work.call(job); job.check();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    complete();
                    try { result.accept(value); } catch (Exception e) { error(e); }
                });
            } catch (Exception | LinkageError | OutOfMemoryError | StackOverflowError e) {
                boolean cancelled = job.cancelled.get() || e instanceof CancellationException || Thread.currentThread().isInterrupted();
                // Interrupted codegen may have partial upstream state: discard the session before reuse.
                Thread.interrupted();
                try { closeSession(); } catch (RuntimeException | LinkageError cleanupFailure) { e.addSuppressed(cleanupFailure); }
                runOnUiThread(() -> {
                    if (destroyed) return;
                    complete(); source = null; page = null; results = null; selectedClass = null;
                    previousPages.clear(); code.setText(""); list.setAdapter(null); showList();
                    if (cancelled) status.setText("Cancelled. Current library work has stopped; Reopen to continue.");
                    else error(e);
                });
            } finally { running = null; Thread.interrupted(); }
        });
    }
    private void complete() { busy = false; cancellation = null; enable(true); cancelButton.setEnabled(false); }
    private void enable(boolean value) { for (View action : actions) action.setEnabled(value); }
    private void cancel() {
        Job job = cancellation;
        if (job == null) return;
        job.cancelled.set(true); Thread thread = running; if (thread != null) thread.interrupt();
        cancelButton.setEnabled(false); status.setText("Cancelling… waiting for the current JADX operation to return");
    }
    private void requireSession() throws IOException { if (session == null) throw new IOException("Reopen the file to start a decompiler session"); }
    private void closeSession() { try { if (session != null) session.close(); } finally { session = null; } }
    private void error(Throwable failure) {
        String message = failure.getMessage();
        if (failure instanceof OutOfMemoryError) message = "Not enough memory for this APK/class. Use a smaller DEX input.";
        if (message == null || message.isEmpty()) message = failure.getClass().getSimpleName();
        status.setText(message + "\nReopen to retry. No changes were made to the original.");
        new AlertDialog.Builder(this).setTitle("Decompilation failed").setMessage(message).setPositiveButton("OK", null).show();
    }
    private void button(LinearLayout row, String name, Runnable action) {
        Button button = new Button(this); button.setText(name); button.setTextColor(color(R.color.dec_text));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.dec_control)));
        button.setOnClickListener(v -> { if (!busy) action.run(); }); row.addView(button); actions.add(button);
    }
    private TextView label(String text) { TextView view = new TextView(this); view.setText(text); view.setTextColor(color(R.color.dec_text)); return view; }
    private int color(int id) { return getResources().getColor(id); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onBackPressed() {
        if (!busy && viewingSource) showList();
        else { cancel(); super.onBackPressed(); }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("dec_query", query == null ? "" : query.getText().toString());
        state.putInt("dec_mode", mode == null ? 0 : mode.getSelectedItemPosition());
        state.putString("dec_search_query", searchQuery); state.putInt("dec_search_mode", searchMode.ordinal()); state.putInt("dec_offset", searchOffset);
        if (viewingSource && selectedClass != null) { state.putString("dec_class", selectedClass); state.putLong("dec_source_offset", page == null ? 0 : page.start); }
    }
    @Override protected void onDestroy() {
        destroyed = true; cancel();
        // Queue cleanup after active work, even if that work ignores interruption temporarily.
        worker.execute(() -> closeSession()); worker.shutdown();
        super.onDestroy();
    }
}
