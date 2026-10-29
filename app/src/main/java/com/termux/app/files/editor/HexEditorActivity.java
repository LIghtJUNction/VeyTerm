package com.termux.app.files.editor;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.*;
import android.view.Gravity;
import android.widget.*;
import com.termux.R;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded, editable 4 KiB pages backed by streaming whole-file replacement. */
public final class HexEditorActivity extends EditorActivity {
    private EditText editor;
    private TextView offsets;
    private byte[] original;
    private long offset;
    private boolean changing;
    private String last = "";
    private final EditorHistory history = new EditorHistory();
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); if (file == null) return;
        button("Save", () -> save(() -> {}));
        button("Undo", () -> change(history.undo(editor.getText().toString())));
        button("Redo", () -> change(history.redo(editor.getText().toString())));
        button("Previous", () -> guard(() -> load(Math.max(0, offset - EditorIo.HEX_PAGE_BYTES))));
        button("Next", () -> guard(() -> { if (offset + original.length < snapshot.length) load(offset + original.length); else status.setText("End of file"); }));
        button("Offset", this::offsetDialog); button("Search bytes", this::searchDialog);
        button("ASCII", () -> {
            try {
                StringBuilder text = new StringBuilder(); int i = 0;
                for (byte b : EditorIo.parseHex(editor.getText().toString())) {
                    if (i++ > 0 && (i - 1) % 16 == 0) text.append('\n');
                    text.append((b & 255) >= 32 && (b & 255) < 127 ? (char) b : '·');
                }
                new AlertDialog.Builder(this).setTitle("ASCII · current edited page").setMessage(text).setPositiveButton("OK", null).show();
            } catch (Exception e) { error(e); }
        });
        ScrollView vertical = new ScrollView(this); HorizontalScrollView horizontal = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.TOP);
        offsets = label(""); offsets.setTypeface(Typeface.MONOSPACE); offsets.setTextSize(13);
        offsets.setPadding(dp(8), dp(12), dp(10), dp(12)); row.addView(offsets);
        editor = new EditText(this); editor.setTextColor(color(R.color.vey_text_primary));
        editor.setBackgroundColor(color(R.color.vey_surface_terminal));
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE |
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setTypeface(Typeface.MONOSPACE); editor.setTextSize(13); editor.setGravity(Gravity.TOP);
        editor.setPadding(dp(4), dp(12), dp(8), dp(12)); editor.setHorizontallyScrolling(true); editor.setSaveEnabled(false);
        row.addView(editor, new LinearLayout.LayoutParams(-2, -2)); horizontal.addView(row); vertical.addView(horizontal); content.addView(vertical);
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) { if (!changing) { history.record(last); last = s.toString(); info(); } }
        });
        if (state != null && state.containsKey("hex_value")) {
            original = state.getByteArray("hex_original"); offset = state.getLong("hex_offset"); change(state.getString("hex_value")); info();
        } else background("Checking file…", () -> {
            snapshot = EditorIo.snapshot(file); return EditorIo.readPage(snapshot.file, 0, EditorIo.HEX_PAGE_BYTES);
        }, value -> show(0, value));
    }
    private void change(String value) { changing = true; editor.setText(value); last = value; changing = false; info(); }
    private void show(long start, byte[] bytes) { offset = start; original = bytes; history.clear(); change(EditorIo.hex(bytes)); info(); }
    private void load(long start) {
        if (snapshot == null) return;
        background("Reading hex page…", () -> { EditorIo.verify(snapshot); return EditorIo.readPage(snapshot.file, start, EditorIo.HEX_PAGE_BYTES); }, bytes -> show(start, bytes));
    }
    private void info() {
        if (original == null) return;
        StringBuilder rows = new StringBuilder();
        String[] lines = editor.getText().toString().split("\n", -1); long rowOffset = offset;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) rows.append('\n'); rows.append(String.format(java.util.Locale.ROOT, "%08X", rowOffset));
            rowOffset += lines[i].replaceAll("\\s+", "").length() / 2;
        }
        offsets.setText(rows);
        status.setText((dirty() ? "● Unsaved · " : "") + String.format(java.util.Locale.ROOT, "Bytes [0x%X, 0x%X) / %d", offset, offset + original.length, snapshot.length) +
            "\nEnter two hex digits per byte; row offsets follow the edited rows. Insert/delete changes file length; unseen bytes are retained.");
    }
    @Override protected boolean dirty() {
        if (original == null) return false;
        try { return !Arrays.equals(original, EditorIo.parseHex(editor.getText().toString())); }
        catch (Exception e) { return true; }
    }
    @Override protected void save(Runnable after) {
        if (original == null) return;
        try {
            byte[] bytes = EditorIo.parseHex(editor.getText().toString());
            Runnable perform = () -> background("Saving hex page atomically…", () ->
                EditorIo.saveRange(snapshot, offset, offset + original.length, bytes, unixMetadata), value -> {
                    saved(value);
                    background("Reading saved bytes…", () -> EditorIo.readPage(value.file, offset, EditorIo.HEX_PAGE_BYTES), result -> { show(offset, result); after.run(); });
                });
            if (bytes.length != original.length) new AlertDialog.Builder(this).setTitle("Change file length?")
                .setMessage("Replace " + original.length + " bytes with " + bytes.length + " bytes? Later offsets shift by " + (bytes.length - original.length) + ".")
                .setPositiveButton("Save", (d, w) -> perform.run()).setNegativeButton("Cancel", null).show();
            else perform.run();
        } catch (Exception e) { error(e); }
    }
    private void offsetDialog() {
        EditText input = input("Decimal byte offset, or 0x…");
        new AlertDialog.Builder(this).setTitle("Go to byte offset").setView(input).setPositiveButton("Go", (d, w) -> guard(() -> {
            try {
                String value = input.getText().toString().trim(); long start = value.startsWith("0x") || value.startsWith("0X") ? Long.parseLong(value.substring(2), 16) : Long.parseLong(value);
                if (start < 0 || snapshot == null || start > snapshot.length) throw new IllegalArgumentException("Offset outside file");
                load(start);
            } catch (Exception e) { error(e); }
        })).setNegativeButton("Cancel", null).show();
    }
    private void searchDialog() {
        if (snapshot == null) return;
        LinearLayout form = form(); EditText query = input("Hex: DE AD BE EF"); form.addView(query);
        CheckBox utf8 = new CheckBox(this); utf8.setText("Interpret input as UTF-8 text instead"); utf8.setTextColor(color(R.color.vey_text_primary)); form.addView(utf8);
        form.addView(label("Searches the complete saved file from this offset + 1, then wraps to its beginning."));
        new AlertDialog.Builder(this).setTitle("Find bytes").setView(form).setPositiveButton("Find next", (d, w) -> guard(() -> {
            try {
                byte[] needle = utf8.isChecked() ? query.getText().toString().getBytes(StandardCharsets.UTF_8) : EditorIo.parseHex(query.getText().toString());
                background("Searching complete file…", () -> {
                    EditorIo.verify(snapshot);
                    long found = EditorIo.findBytes(snapshot.file, needle, Math.min(snapshot.length, offset + 1));
                    if (found < 0) found = EditorIo.findBytes(snapshot.file, needle, 0);
                    return found;
                }, found -> {
                    if (found < 0) { status.setText("Byte sequence not found"); return; }
                    final long start = (found / 16) * 16;
                    background("Reading match…", () -> EditorIo.readPage(snapshot.file, start, EditorIo.HEX_PAGE_BYTES), bytes -> {
                        show(start, bytes); int character = (int) (found - start) * 3;
                        editor.requestFocus(); editor.setSelection(Math.min(character, editor.length()), Math.min(character + needle.length * 3 - 1, editor.length()));
                    });
                });
            } catch (Exception e) { error(e); }
        })).setNegativeButton("Cancel", null).show();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (original != null) { out.putString("hex_value", editor.getText().toString()); out.putByteArray("hex_original", original); out.putLong("hex_offset", offset); }
    }
}
