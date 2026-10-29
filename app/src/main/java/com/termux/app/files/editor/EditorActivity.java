package com.termux.app.files.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.view.*;
import android.widget.*;
import com.termux.R;
import java.io.*;
import java.util.concurrent.*;

/** Shared native chrome and serialized background work. Manifest registration belongs to integration. */
abstract class EditorActivity extends Activity {
    public static final String EXTRA_FILE_PATH = "file_path", EXTRA_COMPARE_PATH = "compare_path";
    protected File file;
    protected LinearLayout root, tools;
    protected FrameLayout content;
    protected TextView status;
    protected boolean busy;
    protected boolean savedOnce;
    protected EditorIo.Snapshot snapshot;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vey-file-editor"); thread.setDaemon(true); return thread;
    });
    interface Result<T> { void accept(T value) throws Exception; }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED, getIntent());
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (path == null || path.isEmpty()) { Toast.makeText(this, "Missing file_path", Toast.LENGTH_LONG).show(); finish(); return; }
        file = new File(path);
        if (state != null && state.getBoolean("editor_saved")) { savedOnce = true; setResult(RESULT_OK, getIntent()); }
        if (state != null) snapshot = (EditorIo.Snapshot) state.getSerializable("editor_snapshot");
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.vey_surface_panel));
        TextView title = label(file.getName()); title.setTextSize(18); title.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(title);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        tools = new LinearLayout(this); scroll.addView(tools); root.addView(scroll);
        status = label("Opening…"); status.setTextSize(12); status.setTextColor(color(R.color.vey_text_secondary));
        status.setPadding(dp(10), dp(4), dp(10), dp(4)); root.addView(status);
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    protected int color(int id) { return getResources().getColor(id); }
    protected int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    protected TextView label(String value) {
        TextView view = new TextView(this); view.setText(value); view.setTextColor(color(R.color.vey_text_primary)); return view;
    }
    protected EditText input(String hint) {
        EditText view = new EditText(this); view.setHint(hint); view.setTextColor(color(R.color.vey_text_primary));
        view.setHintTextColor(color(R.color.vey_text_muted)); view.setSingleLine(true); return view;
    }
    protected void button(String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setTextColor(color(R.color.vey_text_primary));
        button.setOnClickListener(v -> { if (!busy) action.run(); }); tools.addView(button);
    }
    protected LinearLayout form() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(16), 0, dp(16), 0); return form;
    }
    protected <T> void background(String message, Callable<T> action, Result<T> result) {
        if (busy) return;
        busy = true; status.setText(message); enable(root, false);
        worker.submit(() -> {
            try {
                T value = action.call();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false; enable(root, true);
                    try { result.accept(value); } catch (Exception e) { error(e); }
                });
            } catch (StackOverflowError e) {
                fail(new IllegalArgumentException("Expression exceeds the search engine stack limit; simplify it", e));
            } catch (Exception e) { fail(e); }
        });
    }
    private void fail(Exception e) {
        runOnUiThread(() -> { if (!isDestroyed()) { busy = false; enable(root, true); error(e); } });
    }
    private static void enable(View view, boolean value) {
        view.setEnabled(value);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enable(group.getChildAt(i), value);
        }
    }
    protected void error(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        status.setText(message);
        new AlertDialog.Builder(this).setTitle("File operation failed").setMessage(message)
            .setPositiveButton("OK", null).show();
    }
    protected final EditorIo.Metadata unixMetadata = (original, temporary) -> {
        try {
            int required = Os.stat(original.getPath()).st_mode & 0777;
            // Shared/emulated filesystems often expose a uniform mode and reject chmod.
            if ((Os.stat(temporary.getPath()).st_mode & 0777) != required) Os.chmod(temporary.getPath(), required);
        }
        catch (ErrnoException e) { throw new IOException("Cannot retain original file permissions", e); }
    };
    protected void saved(EditorIo.Snapshot value) {
        snapshot = value; savedOnce = true;
        setResult(RESULT_OK, getIntent());
    }
    protected boolean dirty() { return false; }
    protected void save(Runnable after) { after.run(); }
    protected void guard(Runnable action) {
        if (busy) return;
        if (!dirty()) { action.run(); return; }
        new AlertDialog.Builder(this).setTitle("Unsaved changes")
            .setMessage("Save this page before leaving it?")
            .setPositiveButton("Save", (d, w) -> save(action))
            .setNegativeButton("Discard", (d, w) -> action.run())
            .setNeutralButton("Cancel", null).show();
    }
    @Override public void onBackPressed() { guard(() -> super.onBackPressed()); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("editor_saved", savedOnce);
        if (snapshot != null) out.putSerializable("editor_snapshot", snapshot);
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
