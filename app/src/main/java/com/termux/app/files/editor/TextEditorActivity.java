package com.termux.app.files.editor;

import android.app.AlertDialog;
import android.graphics.*;
import android.os.Bundle;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import com.termux.R;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Native paged text editor. Replace-all and undo apply to the explicitly displayed page. */
public final class TextEditorActivity extends EditorActivity {
    private CodeInput editor;
    private EditorIo.Encoding source, target;
    private EditorIo.LineEnding endings = EditorIo.LineEnding.PRESERVE;
    private EditorIo.TextPage page;
    private final EditorHistory history = new EditorHistory();
    private final ArrayDeque<Long> previous = new ArrayDeque<>();
    private boolean changing, wrap;
    private float font = 14;
    private String last = "", query = "";
    private boolean regex, caseSensitive;
    private long firstLine = 1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); if (file == null) return;
        editor = new CodeInput(); editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextColor(color(R.color.vey_text_primary));
        editor.setBackgroundColor(color(R.color.vey_surface_terminal));
        editor.setGravity(Gravity.TOP | Gravity.START); editor.setPadding(dp(70), dp(8), dp(12), dp(40));
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE |
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Native text suggestions/autocorrect are deliberately disabled for source files.
        editor.setHorizontallyScrolling(true); editor.setTextSize(font);
        editor.setSaveEnabled(false); content.addView(editor, new android.widget.FrameLayout.LayoutParams(-1, -1));
        button("Save", () -> save(() -> {}));
        button("Undo", () -> change(history.undo(editor.getText().toString())));
        button("Redo", () -> change(history.redo(editor.getText().toString())));
        button("Find / replace", this::findDialog); button("Go to line", this::jumpDialog);
        button("Previous", () -> guard(() -> {
            if (!previous.isEmpty()) load(previous.removeLast(), false);
            else status.setText("At first visited page; Go to line can navigate globally.");
        }));
        button("Next", () -> guard(() -> {
            if (page != null && page.end < snapshot.length) load(page.end, true);
            else status.setText("End of file");
        }));
        button("Format", this::formatDialog); button("Decode as…", this::decodeDialog);
        button("Wrap", () -> { wrap = !wrap; editor.setHorizontallyScrolling(!wrap); editor.requestLayout(); });
        button("A−", () -> { font = Math.max(8, font - 1); editor.setTextSize(font); });
        button("A+", () -> { font = Math.min(36, font + 1); editor.setTextSize(font); });
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable text) {
                if (changing) return;
                history.record(last); last = text.toString(); syntax(); info();
            }
        });
        if (state != null && state.containsKey("text_value")) {
            try {
                source = EditorIo.encoding(state.getString("text_source"), state.getBoolean("text_source_bom"));
                target = EditorIo.encoding(state.getString("text_target"), state.getBoolean("text_target_bom"));
                endings = EditorIo.LineEnding.valueOf(state.getString("text_endings"));
                page = new EditorIo.TextPage(state.getLong("text_start"), state.getLong("text_end"), state.getString("text_original"));
                firstLine = state.getLong("text_first_line", 1); font = state.getFloat("text_font", 14); wrap = state.getBoolean("text_wrap");
                editor.setTextSize(font); editor.setHorizontallyScrolling(!wrap);
                change(state.getString("text_value"));
                editor.setSelection(Math.min(state.getInt("text_selection"), editor.length())); info();
            } catch (Exception e) { error(e); }
        } else background("Checking file and encoding…", () -> {
            snapshot = EditorIo.snapshot(file); source = EditorIo.detectEncoding(snapshot.file); target = source;
            return EditorIo.readTextPage(snapshot.file, source, source.bom.length, EditorIo.TEXT_PAGE_BYTES);
        }, value -> show(value, 1));
    }
    private void show(EditorIo.TextPage value, long line) {
        page = value; firstLine = line; history.clear(); change(EditorIo.displayText(value.text)); editor.setSelection(0); info();
    }
    private void change(String value) {
        int cursor = Math.max(0, editor.getSelectionStart());
        changing = true; editor.setText(value); last = value; changing = false;
        editor.setSelection(Math.min(cursor, editor.length())); syntax(); info();
    }
    private void load(long offset, boolean remember) {
        final long old = page == null ? 0 : page.start;
        background("Reading text page…", () -> {
            EditorIo.verify(snapshot);
            EditorIo.TextPage value = EditorIo.readTextPage(snapshot.file, source, offset, EditorIo.TEXT_PAGE_BYTES);
            return new Object[]{value, EditorIo.linesBefore(snapshot.file, source, offset) + 1};
        }, result -> {
            if (remember) previous.addLast(old);
            show((EditorIo.TextPage) result[0], (Long) result[1]);
        });
    }
    @Override protected boolean dirty() {
        return page != null && (!EditorIo.displayText(page.text).equals(editor.getText().toString()) ||
            !source.charset.equals(target.charset) || !Arrays.equals(source.bom, target.bom) || endings != EditorIo.LineEnding.PRESERVE);
    }
    private void info() {
        if (page == null) return;
        status.setText((dirty() ? "● Unsaved · " : "") + source + " → " + target + " · " + endings +
            "\nBytes [" + page.start + ", " + page.end + ") / " + snapshot.length + " · line " + firstLine +
            " · " + EditorIo.lineEndingSummary(page.text) +
            (snapshot.length > EditorIo.TEXT_PAGE_BYTES ? "\nPAGED: search/replace/undo affect this page. Other bytes are retained on save." : ""));
        editor.invalidate();
    }
    @Override protected void save(Runnable after) {
        if (page == null) return;
        final String edited = EditorIo.retainLineEndings(page.text, editor.getText().toString());
        final boolean convert = !source.charset.equals(target.charset) || !Arrays.equals(source.bom, target.bom) || endings != EditorIo.LineEnding.PRESERVE;
        background("Saving complete file atomically…", () ->
            EditorIo.saveTextPage(snapshot, page, source, edited, target, endings, unixMetadata), value -> {
                saved(value); source = target; endings = EditorIo.LineEnding.PRESERVE;
                long offset = convert ? source.bom.length : Math.max(source.bom.length, Math.min(page.start, value.length));
                if (convert) previous.clear();
                background("Reloading saved page…", () -> new Object[]{
                    EditorIo.readTextPage(value.file, source, offset, EditorIo.TEXT_PAGE_BYTES),
                    EditorIo.linesBefore(value.file, source, offset) + 1}, result -> {
                        show((EditorIo.TextPage) result[0], (Long) result[1]); after.run();
                    });
            });
    }
    private void findDialog() {
        if (page == null) return;
        LinearLayout form = form(); EditText find = input("Find on this page"), replacement = input("Replacement");
        find.setText(query); form.addView(find); form.addView(replacement);
        CheckBox re = new CheckBox(this); re.setText("Regular expression ($1 groups in replacement)"); re.setTextColor(color(R.color.vey_text_primary)); re.setChecked(regex); form.addView(re);
        CheckBox cs = new CheckBox(this); cs.setText("Case sensitive"); cs.setTextColor(color(R.color.vey_text_primary)); cs.setChecked(caseSensitive); form.addView(cs);
        new AlertDialog.Builder(this).setTitle("Search / replace · current page").setView(form)
            .setPositiveButton("Find next", (d, w) -> {
                query = find.getText().toString(); regex = re.isChecked(); caseSensitive = cs.isChecked(); findNext();
            }).setNeutralButton("Replace all on page", (d, w) -> {
                query = find.getText().toString(); regex = re.isChecked(); caseSensitive = cs.isChecked();
                String text = editor.getText().toString(), replace = replacement.getText().toString();
                background("Replacing matches on current page…", () -> EditorIo.replace(text, query, replace, regex, caseSensitive), value -> {
                    history.record(text); change(value);
                });
            }).setNegativeButton("Cancel", null).show();
    }
    private void findNext() {
        final String text = editor.getText().toString(); final int start = editor.getSelectionEnd();
        background("Finding matches on this page…", () -> {
            Matcher matcher = EditorIo.searchMatcher(EditorIo.searchPattern(query, regex, caseSensitive), text);
            List<int[]> matches = new ArrayList<>(); int[] next = null, first = null;
            while (matcher.find()) {
                EditorIo.cancelled(); int[] range = new int[]{matcher.start(), matcher.end()};
                if (first == null) first = range;
                if (matches.size() < 2000) matches.add(range);
                if (next == null && matcher.start() >= start && !(matcher.start() == matcher.end() && matcher.start() == start)) next = range;
            }
            if (next == null) next = first;
            return new Object[]{matches, next};
        }, result -> {
            syntax();
            @SuppressWarnings("unchecked") List<int[]> matches = (List<int[]>) result[0];
            for (int[] range : matches) editor.getText().setSpan(new BackgroundColorSpan(color(R.color.vey_surface_selected)), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int[] next = (int[]) result[1];
            if (next == null) status.setText("No match on this page");
            else { editor.requestFocus(); editor.setSelection(next[0], next[1]); status.setText("Matches highlighted (up to 2000), current page only"); }
        });
    }
    private void jumpDialog() {
        if (page == null) return;
        EditText line = input("Global line number (starts at 1)"); line.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle("Go to line").setView(line).setPositiveButton("Go", (d, w) -> guard(() -> {
            try {
                long wanted = Long.parseLong(line.getText().toString());
                background("Locating line in complete file…", () -> {
                    EditorIo.verify(snapshot);
                    EditorIo.TextPage found = EditorIo.pageForLine(snapshot.file, source, wanted, EditorIo.TEXT_PAGE_BYTES);
                    return new Object[]{found, EditorIo.linesBefore(snapshot.file, source, found.start) + 1};
                }, value -> {
                    previous.addLast(page.start); show((EditorIo.TextPage) value[0], (Long) value[1]);
                    long remaining = wanted - firstLine; int position = 0;
                    while (remaining > 0 && position < editor.length()) if (editor.getText().charAt(position++) == '\n') remaining--;
                    editor.requestFocus(); editor.setSelection(position);
                });
            } catch (Exception e) { error(e); }
        })).setNegativeButton("Cancel", null).show();
    }
    private void decodeDialog() {
        if (page == null) { // Also reachable after strict initial UTF-8 decoding fails.
            if (snapshot == null) return;
        }
        EditText name = input("UTF-8, UTF-16LE, GB18030, windows-1252, Shift_JIS…");
        name.setText(source == null ? "UTF-8" : source.charset.name());
        new AlertDialog.Builder(this).setTitle("Reopen using encoding").setView(name).setPositiveButton("Reopen", (d, w) -> {
            final String requested = name.getText().toString();
            guard(() -> { background("Decoding file…", () -> {
                EditorIo.Encoding detected = EditorIo.detectEncoding(snapshot.file);
                EditorIo.Encoding chosen = EditorIo.encoding(requested, false);
                if (detected.bom.length > 0 && !detected.charset.equals(chosen.charset)) throw new IOException("Encoding conflicts with file BOM");
                EditorIo.Encoding decoded = detected.bom.length > 0 ? detected : chosen;
                EditorIo.verify(snapshot);
                return new Object[]{decoded, EditorIo.readTextPage(snapshot.file, decoded, decoded.bom.length, EditorIo.TEXT_PAGE_BYTES)};
            }, value -> { source = (EditorIo.Encoding) value[0]; target = source; endings = EditorIo.LineEnding.PRESERVE;
                previous.clear(); show((EditorIo.TextPage) value[1], 1); });
            });
        }).setNegativeButton("Cancel", null).show();
    }
    private void formatDialog() {
        if (page == null) return;
        LinearLayout form = form(); EditText charset = input("Save encoding"); charset.setText(target.charset.name()); form.addView(charset);
        CheckBox bom = new CheckBox(this); bom.setText("Write BOM"); bom.setTextColor(color(R.color.vey_text_primary)); bom.setChecked(target.bom.length > 0); form.addView(bom);
        Spinner eol = new Spinner(this); eol.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Preserve mixed line endings", "LF (Unix)", "CRLF (Windows)", "CR (classic Mac)"})); eol.setSelection(endings.ordinal()); form.addView(eol);
        form.addView(label("Encoding and line endings apply to the COMPLETE file when saved. Conversion returns to its first page."));
        new AlertDialog.Builder(this).setTitle("Save format").setView(form).setPositiveButton("Apply", (d, w) -> {
            try { target = EditorIo.encoding(charset.getText().toString(), bom.isChecked()); endings = EditorIo.LineEnding.values()[eol.getSelectedItemPosition()]; info(); }
            catch (Exception e) { error(e); }
        }).setNegativeButton("Cancel", null).show();
    }
    private static final Pattern TOKENS = Pattern.compile("\\b(?:class|public|private|protected|static|final|void|int|long|boolean|if|else|for|while|return|new|import|package|try|catch|throw|function|const|let|var|def|True|False|None|null|true|false)\\b|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|//[^\\r\\n]*|#[^\\r\\n]*");
    private void syntax() {
        Editable text = editor.getText();
        for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class)) text.removeSpan(span);
        for (BackgroundColorSpan span : text.getSpans(0, text.length(), BackgroundColorSpan.class)) text.removeSpan(span);
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.matches(".*\\.(java|kt|js|ts|tsx|jsx|json|py|sh|c|cpp|h|rs|css|xml|html|yaml|yml|toml)$")) return;
        Matcher matcher = TOKENS.matcher(text); int count = 0;
        while (matcher.find() && count++ < 2000) {
            char c = text.charAt(matcher.start());
            int resource = c == '"' || c == '\'' ? R.color.vey_warning : c == '/' || c == '#' ? R.color.vey_text_muted : R.color.vey_accent;
            text.setSpan(new ForegroundColorSpan(color(resource)), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (page == null || source == null || target == null) return;
        out.putString("text_value", editor.getText().toString()); out.putString("text_original", page.text);
        out.putLong("text_start", page.start); out.putLong("text_end", page.end); out.putLong("text_first_line", firstLine);
        out.putString("text_source", source.charset.name()); out.putBoolean("text_source_bom", source.bom.length > 0);
        out.putString("text_target", target.charset.name()); out.putBoolean("text_target_bom", target.bom.length > 0);
        out.putString("text_endings", endings.name()); out.putInt("text_selection", editor.getSelectionStart());
        out.putFloat("text_font", font); out.putBoolean("text_wrap", wrap);
    }
    /** Gutter numbers refer to logical lines; wrapped continuations receive no duplicate number. */
    private final class CodeInput extends EditText {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        CodeInput() { super(TextEditorActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            Layout layout = getLayout();
            if (layout != null) {
                paint.setColor(color(R.color.vey_surface_panel));
                canvas.drawRect(getScrollX(), getScrollY(), getScrollX() + dp(62), getScrollY() + getHeight(), paint);
                paint.setColor(color(R.color.vey_text_muted)); paint.setTypeface(Typeface.MONOSPACE); paint.setTextSize(getTextSize() * .85f);
                int first = layout.getLineForVertical(Math.max(0, getScrollY() - getTotalPaddingTop()));
                int lastVisible = layout.getLineForVertical(getScrollY() + getHeight());
                long logical = firstLine;
                for (int i = 0; i < layout.getLineStart(first); i++) if (getText().charAt(i) == '\n') logical++;
                for (int i = first; i <= lastVisible; i++) {
                    int start = layout.getLineStart(i);
                    if (start == 0 || getText().charAt(start - 1) == '\n') {
                        canvas.drawText(Long.toString(logical), getScrollX() + dp(5), getTotalPaddingTop() + layout.getLineBaseline(i), paint);
                    }
                    for (int j = start; j < layout.getLineEnd(i); j++) if (getText().charAt(j) == '\n') logical++;
                }
            }
            super.onDraw(canvas);
        }
    }
}
