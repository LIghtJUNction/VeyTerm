package com.termux.app.files.editor;

import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Typeface;
import android.widget.*;
import java.io.*;

/** Local content comparisons, with explicit modes and a bounded but disclosed report. */
public final class FileCompareActivity extends EditorActivity {
    private File other;
    private EditorCompare.Mode mode = EditorCompare.Mode.AUTO;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); if (file == null) return;
        String path = state == null ? getIntent().getStringExtra(EXTRA_COMPARE_PATH) : state.getString("compare_other");
        if (path != null && !path.isEmpty()) other = new File(path);
        if (state != null) mode = EditorCompare.Mode.valueOf(state.getString("compare_mode", "AUTO"));
        button("Second path", this::choose); button("Mode", () -> new AlertDialog.Builder(this).setTitle("Comparison mode")
            .setItems(new String[]{"Auto (directory / ZIP / binary)", "Text (line positions, encoding and endings)", "Binary (byte offsets)", "Directory (recursive contents)", "ZIP / APK (entry contents)"},
                (d, w) -> { mode = EditorCompare.Mode.values()[w]; compare(); }).show());
        button("Compare again", this::compare);
        if (other == null) choose(); else compare();
    }
    private void choose() {
        EditText path = input("Absolute local path to compare with"); if (other != null) path.setText(other.getAbsolutePath());
        new AlertDialog.Builder(this).setTitle("Second comparison path").setView(path).setPositiveButton("Compare", (d, w) -> {
            other = new File(path.getText().toString()); compare();
        }).setNegativeButton("Cancel", null).show();
    }
    private void compare() {
        if (other == null) return;
        File right = other; EditorCompare.Mode selected = mode;
        background("Comparing " + selected + " contents…", () -> EditorCompare.compare(file, right, selected), report -> {
            TextView text = label(report.toString()); text.setTypeface(Typeface.MONOSPACE); text.setTextSize(13); text.setTextIsSelectable(true);
            text.setPadding(dp(12), dp(8), dp(12), dp(12));
            ScrollView scroll = new ScrollView(this); scroll.addView(text); content.removeAllViews(); content.addView(scroll);
            status.setText(file.getAbsolutePath() + "\n↔ " + right.getAbsolutePath() + "\n" + selected + " · " + report.differences + " differences" + (report.limited ? " · report display limited" : ""));
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); if (other != null) out.putString("compare_other", other.getAbsolutePath()); out.putString("compare_mode", mode.name());
    }
}
