package com.termux.app.files.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.files.FileTools;
import com.termux.app.files.fs.FileAccess;
import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;
import com.termux.app.files.fs.FileOperations;
import com.termux.app.files.fs.FileStores;
import com.termux.app.files.fs.FileSearch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Native two-column workspace. All filesystem operations are delegated off the UI thread. */
public final class FileManagerActivity extends Activity implements FileJobs.Listener {
    public interface FileWork { String run(FileOperations.Monitor monitor) throws Exception; }
    private WorkspaceModel model;
    private FileJobs jobs;
    private final PaneView[] views = new PaneView[2];
    private TextView jobStatus, direction;
    private View jobStrip;
    private AlertDialog conflictDialog;
    private AlertDialog renameConfirmation;
    private long conflictId = -1;
    private String conflictUri;
    private boolean shift;
    private boolean compactChrome;
    private int background, panel, selected, text, muted, accent;

    @Override public void onCreate(Bundle saved) {
        setTheme(R.style.fm_WorkspaceTheme);
        super.onCreate(saved);
        background = getResources().getColor(R.color.vey_surface_terminal);
        panel = getResources().getColor(R.color.vey_surface_panel);
        selected = getResources().getColor(R.color.vey_surface_selected);
        text = getResources().getColor(R.color.vey_text_primary);
        muted = getResources().getColor(R.color.vey_text_secondary);
        accent = getResources().getColor(R.color.vey_accent);
        compactChrome = getResources().getConfiguration().screenHeightDp < 500;
        model = (WorkspaceModel) getFragmentManager().findFragmentByTag("fm_workspace");
        boolean fresh = model == null;
        if (fresh) {
            model = new WorkspaceModel();
            model.access = FileStores.create(getApplicationContext());
            model.loadBookmarks(getApplicationContext());
            FileLocation initial = localDirectory(new File(getFilesDir(), "home"));
            model.panes[0] = new PaneState(initial);
            model.panes[1] = new PaneState(initial);
            if (saved != null) {
                restorePane(model.panes[0], saved.getBundle("fm_left"));
                restorePane(model.panes[1], saved.getBundle("fm_right"));
                model.active = saved.getInt("fm_active", 0) == 1 ? 1 : 0;
            } else if (getIntent().getStringExtra("file_path") != null) {
                File requested = new File(getIntent().getStringExtra("file_path"));
                File parent = requested.isDirectory() ? requested : requested.getParentFile();
                if (parent != null) model.panes[0] = new PaneState(localDirectory(parent));
            }
            getFragmentManager().beginTransaction().add(model, "fm_workspace").commit();
            getFragmentManager().executePendingTransactions();
        }
        jobs = FileJobs.get(this);
        FileTools.restoreState(this, saved);
        buildWorkspace();
        if (fresh) refreshPanels();
        else renderPanels();
        if (saved != null && saved.getBoolean("fm_running") && !jobs.snapshot().running
            && jobs.snapshot().result == null && jobs.snapshot().error == null)
            message("Task interrupted", "Android ended the previous process. The task was not replayed. Review both columns before retrying; temporary or completed files may remain.");
    }

    public FileAccess access() { return model.access; }
    public FileLocation activeDirectory() { return model.panes[model.active].directory; }
    public FileLocation otherDirectory() { return model.panes[1 - model.active].directory; }
    public List<FileEntry> selectedEntries() { return new ArrayList<>(model.panes[model.active].selection()); }
    public void refreshPanels() { model.load(0); model.load(1); }
    public void navigate(FileLocation directory) { navigate(model.active, directory); }
    public void runOperation(String title, FileWork work) {
        try { jobs.start(title, work); }
        catch (RuntimeException failure) { message("Cannot start task", failure.getMessage()); }
    }

    private void navigate(int side, FileLocation location) {
        activate(side);
        model.panes[side].navigate(location);
        model.load(side);
    }

    @Override protected void onResume() {
        super.onResume();
        FileTools.restoreState(this, null);
        jobs.attach(this);
        jobsChanged();
        if (model.renamePreview != null && renameConfirmation == null && !jobs.snapshot().running)
            showRenamePreview(model.renamePreview);
    }
    @Override protected void onPause() {
        jobs.detach(this);
        if (conflictDialog != null) { conflictDialog.dismiss(); conflictDialog = null; conflictId = -1; conflictUri = null; }
        if (renameConfirmation != null) { renameConfirmation.dismiss(); renameConfirmation = null; }
        super.onPause();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        FileTools.onActivityResult(this, request, result, data);
        refreshPanels();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        FileTools.onRequestPermissionsResult(this, request, results);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        rememberScroll();
        state.putBundle("fm_left", savePane(model.panes[0]));
        state.putBundle("fm_right", savePane(model.panes[1]));
        state.putInt("fm_active", model.active);
        state.putBoolean("fm_running", jobs.snapshot().running);
        FileTools.saveState(this, state);
        super.onSaveInstanceState(state);
    }

    private static Bundle savePane(PaneState pane) {
        Bundle state = new Bundle();
        state.putString("directory", pane.directory.token());
        state.putStringArrayList("back", tokens(pane.back));
        state.putStringArrayList("forward", tokens(pane.forward));
        state.putStringArrayList("selected", new ArrayList<>(pane.selected));
        state.putInt("sort", pane.sort.ordinal());
        state.putBoolean("descending", pane.descending);
        state.putBoolean("hidden", pane.hidden);
        state.putString("filter", pane.filter);
        state.putBoolean("regex", pane.regex);
        state.putInt("scroll", pane.scrollPosition);
        state.putInt("offset", pane.scrollOffset);
        return state;
    }
    private static ArrayList<String> tokens(List<FileLocation> locations) {
        ArrayList<String> result = new ArrayList<>();
        for (FileLocation location : locations) result.add(location.token());
        return result;
    }
    private static void restorePane(PaneState pane, Bundle state) {
        if (state == null) return;
        try {
            pane.directory = FileLocation.fromToken(state.getString("directory"));
            ArrayList<String> history = state.getStringArrayList("back");
            if (history != null) for (String token : history) pane.back.add(FileLocation.fromToken(token));
            history = state.getStringArrayList("forward");
            if (history != null) for (String token : history) pane.forward.add(FileLocation.fromToken(token));
            history = state.getStringArrayList("selected");
            if (history != null) pane.selected.addAll(history);
            int sort = state.getInt("sort", 0);
            pane.sort = PaneState.Sort.values()[Math.max(0, Math.min(sort, PaneState.Sort.values().length - 1))];
            pane.descending = state.getBoolean("descending"); pane.hidden = state.getBoolean("hidden");
            pane.filter = state.getString("filter", ""); pane.regex = state.getBoolean("regex");
            if (pane.regex) Pattern.compile(pane.filter);
            pane.scrollPosition = state.getInt("scroll"); pane.scrollOffset = state.getInt("offset");
        } catch (RuntimeException invalid) { pane.filter = ""; pane.regex = false; pane.error = "Some saved navigation state could not be restored: " + invalid.getMessage(); }
    }

    private void buildWorkspace() {
        LinearLayout root = column(); root.setBackgroundColor(background);
        LinearLayout top = row(); top.setPadding(dp(8), 0, dp(4), 0);
        TextView title = label("Files", 21, accent); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(compactChrome ? 48 : 52), 1));
        top.addView(button("Locations", "Choose storage location", v -> FileTools.showLocations(this)));
        top.addView(button("Tools", "Archive, editor and advanced tools", v -> FileTools.showTools(this)));
        top.addView(button("⋮", "Workspace menu", this::workspaceMenu));
        root.addView(top);
        direction = label("", 12, muted); direction.setPadding(dp(12), 0, dp(12), dp(5)); root.addView(direction);
        if (compactChrome) direction.setVisibility(View.GONE);
        LinearLayout columns = row(); columns.setGravity(Gravity.TOP);
        for (int i = 0; i < 2; i++) {
            if (i == 1) { View line = new View(this); line.setBackgroundColor(muted); columns.addView(line, new LinearLayout.LayoutParams(dp(1), -1)); }
            views[i] = new PaneView(i);
            columns.addView(views[i].container, new LinearLayout.LayoutParams(0, -1, 1));
        }
        root.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout strip = row(); strip.setBackgroundColor(panel);
        ProgressBar busy = new ProgressBar(this); strip.addView(busy, new LinearLayout.LayoutParams(dp(30), dp(30)));
        jobStatus = label("", 12, text); jobStatus.setMaxLines(3); strip.addView(jobStatus, new LinearLayout.LayoutParams(0, -2, 1));
        strip.addView(button("Cancel", "Cancel active file task", v -> jobs.cancel()));
        jobStrip = strip; root.addView(strip); strip.setVisibility(View.GONE);
        LinearLayout actions = row();
        actions.addView(button("Copy", "Copy selection to the other column, F5", v -> transfer(false)));
        actions.addView(button("Move", "Move selection to the other column, F6", v -> transfer(true)));
        actions.addView(button("Select", "Selection actions", this::selectionMenu));
        actions.addView(button("More", "File operations", this::operationMenu));
        root.addView(horizontal(actions));
        setContentView(root);
    }

    private final class PaneView {
        final int side;
        final LinearLayout container, crumbs;
        final TextView heading, path, status;
        final Button back, forward;
        final EditText filter;
        final ListView list;
        final EntriesAdapter adapter;
        boolean updating;
        FileLocation lastDirectory;
        PaneState lastPane;
        PaneView(int side) {
            this.side = side;
            container = column(); container.setBackgroundColor(panel);
            heading = label("", 13, accent); heading.setPadding(dp(8), dp(6), dp(8), dp(6));
            if (compactChrome) { heading.setPadding(dp(8), dp(2), dp(8), dp(2)); heading.setMinHeight(dp(24)); }
            heading.setOnClickListener(v -> activate(side)); container.addView(heading);
            path = label("", 11, text); path.setTypeface(Typeface.MONOSPACE); path.setMaxLines(3);
            path.setPadding(dp(8), dp(2), dp(8), dp(4)); path.setOnClickListener(v -> pathDialog(side));
            if (compactChrome) { path.setSingleLine(true); path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); path.setMinHeight(dp(28)); }
            path.setContentDescription((side == 0 ? "Left" : "Right") + " path, tap to enter a path"); container.addView(path);
            crumbs = row(); View breadcrumbBar = horizontal(crumbs); container.addView(breadcrumbBar);
            if (compactChrome) breadcrumbBar.setVisibility(View.GONE);
            LinearLayout navigation = row();
            back = button("‹", "Back in this column, Alt+Left", v -> history(side, true));
            forward = button("›", "Forward in this column, Alt+Right", v -> history(side, false));
            addEqual(navigation, back); addEqual(navigation, forward);
            addEqual(navigation, button("↑", "Parent folder, Alt+Up", v -> { activate(side); model.up(side); }));
            addEqual(navigation, button("⋮", "Column display and sorting", v -> paneMenu(side, v)));
            container.addView(navigation);
            filter = input("Filter names"); filter.setTextSize(12); filter.setSingleLine(true);
            filter.setImeOptions(EditorInfo.IME_ACTION_DONE); filter.setText(model.panes[side].filter);
            filter.setOnFocusChangeListener((v, focus) -> { if (focus) activate(side); });
            filter.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (updating) return;
                    PaneState pane = model.panes[side];
                    if (pane.regex) try { Pattern.compile(s.toString()); } catch (PatternSyntaxException bad) { filter.setError(bad.getDescription()); return; }
                    filter.setError(null); pane.filter = s.toString(); pane.scrollPosition = 0; pane.scrollOffset = 0; renderPanels();
                }
                @Override public void afterTextChanged(Editable s) { }
            });
            container.addView(filter, new LinearLayout.LayoutParams(-1, dp(44)));
            if (compactChrome) filter.setVisibility(View.GONE);
            status = label("", 11, muted); status.setPadding(dp(8), dp(3), dp(8), dp(3));
            status.setMaxLines(1); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            status.setOnClickListener(v -> { activate(side); if (model.panes[side].error != null) message("Folder access failed", model.panes[side].error); else model.load(side); });
            container.addView(status);
            list = new ListView(FileManagerActivity.this); list.setDividerHeight(dp(1)); list.setFastScrollEnabled(true);
            list.setContentDescription((side == 0 ? "Left" : "Right") + " file list");
            adapter = new EntriesAdapter(side); list.setAdapter(adapter);
            list.setOnItemClickListener((parent, view, position, id) -> {
                activate(side);
                FileEntry entry = adapter.entries.get(position);
                if (shift) { model.panes[side].select(entry, true); renderPanels(); }
                else if (!model.panes[side].selection().isEmpty()) { model.panes[side].select(entry, false); renderPanels(); }
                else if (entry.directory) navigate(side, entry.location);
                else FileTools.open(FileManagerActivity.this, entry);
            });
            list.setOnItemLongClickListener((parent, view, position, id) -> {
                activate(side); FileEntry entry = adapter.entries.get(position);
                model.panes[side].mark(entry);
                renderPanels(); entryMenu(side, entry, view); return true;
            });
            list.setOnFocusChangeListener((v, focus) -> { if (focus) activate(side); });
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView view, int state) { }
                @Override public void onScroll(AbsListView view, int first, int count, int total) {
                    if (!updating) {
                        model.panes[side].scrollPosition = first;
                        View child = list.getChildAt(0); model.panes[side].scrollOffset = child == null ? 0 : child.getTop();
                    }
                }
            });
            container.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        void render() {
            PaneState pane = model.panes[side];
            updating = true;
            heading.setText((side == 0 ? "LEFT" : "RIGHT") + (model.active == side ? "  ● ACTIVE" : "  ○"));
            heading.setBackgroundColor(model.active == side ? selected : panel);
            path.setText(display(pane.directory));
            path.setContentDescription((side == 0 ? "Left" : "Right") + " folder " + display(pane.directory) + ", tap to enter path");
            if (lastPane != pane || !pane.directory.equals(lastDirectory)) { breadcrumbs(side, crumbs); lastDirectory = pane.directory; lastPane = pane; }
            back.setEnabled(!pane.back.isEmpty()); forward.setEnabled(!pane.forward.isEmpty());
            if (!filter.getText().toString().equals(pane.filter)) filter.setText(pane.filter);
            filter.setHint(pane.regex ? "Name regex" : "Filter names");
            adapter.entries = pane.visible();
            status.setText(pane.loading ? "Loading…" : pane.error != null ? "Access error · tap for details / retry from menu"
                : (pane.searchLabel != null ? "Search: " + pane.searchLabel + " · " : "")
                    + adapter.entries.size() + " entries · " + pane.selection().size() + " selected"
                    + (compactChrome && !pane.filter.isEmpty() ? " · filtered" : ""));
            status.setTextColor(pane.error != null ? accent : muted);
            adapter.notifyDataSetChanged();
            list.setSelectionFromTop(pane.scrollPosition, pane.scrollOffset);
            updating = false;
        }
    }

    private final class EntriesAdapter extends BaseAdapter {
        final int side;
        List<FileEntry> entries = new ArrayList<>();
        EntriesAdapter(int side) { this.side = side; }
        @Override public int getCount() { return entries.size(); }
        @Override public FileEntry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            FileEntry entry = entries.get(position);
            LinearLayout row;
            EntryViews holder;
            if (convert instanceof LinearLayout && convert.getTag() instanceof EntryViews) {
                row = (LinearLayout) convert; holder = (EntryViews) convert.getTag();
            } else {
                row = row(); row.setPadding(dp(3), dp(4), dp(5), dp(4)); row.setMinimumHeight(dp(60));
                holder = new EntryViews();
                holder.check = new CheckBox(FileManagerActivity.this);
                holder.check.setButtonTintList(android.content.res.ColorStateList.valueOf(accent)); holder.check.setFocusable(false);
                row.addView(holder.check, new LinearLayout.LayoutParams(dp(44), dp(48)));
                LinearLayout labels = column();
                holder.name = label("", 13, text); holder.name.setMaxLines(3); labels.addView(holder.name);
                holder.metadata = label("", 10, muted); holder.metadata.setMaxLines(3); labels.addView(holder.metadata);
                row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS); row.setTag(holder);
            }
            boolean checked = model.panes[side].selected.contains(entry.location.uri);
            row.setBackgroundColor(checked ? selected : panel);
            holder.check.setChecked(checked); holder.check.setContentDescription("Select " + entry.name);
            holder.check.setOnClickListener(v -> { activate(side); model.panes[side].select(entry, shift); renderPanels(); });
            holder.name.setText((entry.directory ? "▸ " : "") + entry.name + (entry.symbolicLink ? " ↗" : ""));
            holder.name.setTextColor(entry.directory ? accent : text);
            String detail = (entry.directory ? "Folder" : bytes(entry.size)) + " · " + (entry.modified > 0 ? android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", new Date(entry.modified)) : "Date unavailable");
            if (model.panes[side].searchLabel != null) detail += "\n" + display(entry.location);
            holder.metadata.setText(detail);
            return row;
        }
    }
    private static final class EntryViews { CheckBox check; TextView name, metadata; }

    void renderPanels() {
        if (views[0] == null || views[1] == null) return;
        views[0].render(); views[1].render();
        direction.setText((model.active == 0 ? "Left → Right" : "Right → Left") + " · operations use the other column");
    }
    private void activate(int side) {
        if (model.active == side) return;
        model.active = side;
        renderPanels();
    }
    private void rememberScroll() {
        for (int i = 0; i < 2; i++) if (views[i] != null) {
            model.panes[i].scrollPosition = views[i].list.getFirstVisiblePosition();
            View child = views[i].list.getChildAt(0); model.panes[i].scrollOffset = child == null ? 0 : child.getTop();
        }
    }
    private void history(int side, boolean backwards) {
        activate(side);
        if (model.panes[side].history(backwards)) model.load(side);
    }

    private void breadcrumbs(int side, LinearLayout target) {
        target.removeAllViews(); FileLocation location = model.panes[side].directory;
        if (location.isLocal()) {
            String path = location.file().getAbsolutePath();
            File cursor = new File("/");
            addCrumb(target, side, "/", localDirectory(cursor));
            for (String segment : path.split("/")) if (!segment.isEmpty()) {
                cursor = new File(cursor, segment); addCrumb(target, side, segment, localDirectory(cursor));
            }
        } else {
            for (int i = 0; i < location.ancestors.size(); i++) {
                FileLocation ancestor = new FileLocation(location.ancestors.get(i), location.treeUri, location.ancestors.subList(0, i));
                addCrumb(target, side, i == 0 ? "Root" : "…" + i, ancestor);
            }
            addCrumb(target, side, "Current", location);
        }
    }
    private void addCrumb(LinearLayout target, int side, String name, FileLocation location) {
        TextView crumb = label(name + " ›", 11, accent); crumb.setPadding(dp(7), dp(8), dp(7), dp(8));
        crumb.setContentDescription("Navigate to " + display(location)); crumb.setOnClickListener(v -> navigate(side, location)); target.addView(crumb);
    }

    private void workspaceMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        item(menu, "Sync other column to active", () -> {
            int other = 1 - model.active;
            model.panes[other].navigate(activeDirectory()); model.load(other);
        });
        item(menu, "Swap columns", () -> {
            rememberScroll();
            PaneState left = model.panes[0]; model.panes[0] = model.panes[1]; model.panes[1] = left;
            // In-flight readers belong to their original slot; invalidate and reload both new slots.
            model.panes[0].revision++; model.panes[1].revision++;
            model.active = 1 - model.active; refreshPanels();
        });
        item(menu, "Refresh both columns", this::refreshPanels);
        item(menu, "Bookmarks", this::bookmarks);
        item(menu, "Recursive search", this::searchDialog);
        item(menu, "Keyboard shortcuts", () -> message("Keyboard shortcuts", "Tab: switch column (outside text input)\nAlt+Left / Right: back / forward\nAlt+Up: parent\nCtrl+L: enter path\nCtrl+F: name filter\nCtrl+A: select visible entries\nSpace: toggle focused row\nShift+click / Shift+Space: select range\nF2: rename preview\nF5 / F6: copy / move to other column\nF7: new folder\nF8 / Delete: confirm deletion\nEscape: clear selection"));
        menu.show();
    }
    private void paneMenu(int side, View anchor) {
        activate(side); PaneState pane = model.panes[side];
        PopupMenu menu = new PopupMenu(this, anchor);
        item(menu, "Enter path…", () -> pathDialog(side));
        item(menu, "Refresh / exit search", () -> model.load(side));
        item(menu, "Filter names…", () -> filterDialog(side));
        if (!pane.filter.isEmpty()) item(menu, "Clear name filter", () -> { pane.filter = ""; renderPanels(); });
        item(menu, (pane.hidden ? "✓ " : "") + "Show hidden files", () -> { pane.hidden = !pane.hidden; renderPanels(); });
        item(menu, (pane.regex ? "✓ " : "") + "Regex name filter", () -> {
            try { if (!pane.regex) Pattern.compile(pane.filter); pane.regex = !pane.regex; renderPanels(); }
            catch (PatternSyntaxException bad) { message("Invalid regex", bad.getDescription()); }
        });
        for (PaneState.Sort sort : PaneState.Sort.values()) item(menu, (pane.sort == sort ? "✓ " : "") + "Sort: " + sort.name().toLowerCase(Locale.ROOT), () -> { pane.sort = sort; renderPanels(); });
        item(menu, (pane.descending ? "✓ " : "") + "Descending order", () -> { pane.descending = !pane.descending; renderPanels(); });
        item(menu, "Bookmarks", this::bookmarks); item(menu, "Recursive search", this::searchDialog);
        menu.show();
    }
    private void filterDialog(int side) {
        activate(side);
        PaneState pane = model.panes[side];
        EditText value = input(pane.regex ? "Name regex" : "Filter names");
        value.setSingleLine(true); value.setText(pane.filter); value.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(pane.regex ? "Name regex" : "Filter names")
            .setView(value).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String query = value.getText().toString();
            if (pane.regex) try { Pattern.compile(query); }
            catch (PatternSyntaxException invalid) { value.setError(invalid.getDescription()); return; }
            pane.filter = query; pane.scrollPosition = 0; pane.scrollOffset = 0;
            renderPanels(); dialog.dismiss();
        }));
        dialog.show();
    }
    private void selectionMenu(View anchor) {
        PaneState pane = model.panes[model.active]; PopupMenu menu = new PopupMenu(this, anchor);
        item(menu, "Select all visible", () -> { pane.selectAll(false); renderPanels(); });
        item(menu, "Invert visible selection", () -> { pane.selectAll(true); renderPanels(); });
        item(menu, "Clear selection", () -> { pane.selected.clear(); renderPanels(); });
        item(menu, "Same extension as first selected", () -> {
            List<FileEntry> selection = pane.selection();
            FileEntry reference = null;
            for (FileEntry entry : selection) if (!entry.directory) { reference = entry; break; }
            if (reference == null) tip("Select a reference file first");
            else { pane.sameExtension(reference); renderPanels(); }
        });
        item(menu, "Range to focused row", () -> {
            int position = views[model.active].list.getSelectedItemPosition();
            if (position < 0 || position >= views[model.active].adapter.entries.size()) tip("Shift+click the last row to select a range");
            else { pane.select(views[model.active].adapter.entries.get(position), true); renderPanels(); }
        });
        menu.show();
    }
    private void operationMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        item(menu, "Open with / file tools", () -> toolsForSelection());
        item(menu, "Copy to other column", () -> transfer(false)); item(menu, "Move to other column", () -> transfer(true));
        item(menu, "New folder", () -> create(true)); item(menu, "New empty file", () -> create(false));
        item(menu, "Rename / batch rename…", this::renameDialog); item(menu, "Delete…", this::deleteSelection);
        item(menu, "Properties / permissions", this::properties); item(menu, "Checksums", this::checksumDialog);
        item(menu, "Search recursively…", this::searchDialog); item(menu, "Bookmarks", this::bookmarks);
        menu.show();
    }
    private void entryMenu(int side, FileEntry entry, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor); PaneState pane = model.panes[side];
        item(menu, entry.directory ? "Open folder" : "Open", () -> { if (entry.directory) navigate(side, entry.location); else FileTools.open(this, entry); });
        item(menu, "Open with / tools", () -> FileTools.showMenu(this, new ArrayList<>(java.util.Collections.singletonList(entry))));
        item(menu, "Select range to this row", () -> { pane.select(entry, true); renderPanels(); });
        if (!entry.directory) item(menu, "Select same extension", () -> { pane.sameExtension(entry); renderPanels(); });
        item(menu, "Properties / permissions", this::properties);
        item(menu, "Rename…", this::renameDialog); item(menu, "Delete selected…", this::deleteSelection);
        menu.show();
    }
    private void toolsForSelection() { List<FileEntry> entries = requireSelection(); if (!entries.isEmpty()) FileTools.showMenu(this, entries); }
    private List<FileEntry> requireSelection() {
        List<FileEntry> entries = selectedEntries();
        if (entries.isEmpty()) tip("Select files using the checkboxes or long press");
        return entries;
    }
    private static List<FileLocation> locations(List<FileEntry> entries) {
        List<FileLocation> result = new ArrayList<>(); for (FileEntry entry : entries) result.add(entry.location); return result;
    }
    private void pathDialog(int side) {
        activate(side); EditText input = input("Absolute path or storage URI"); input.setText(display(activeDirectory()));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Go to folder").setView(padded(input))
            .setNegativeButton("Cancel", null).setPositiveButton("Go", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String value = input.getText().toString().trim();
                FileLocation location = value.startsWith("/") ? localDirectory(new File(value)) : new FileLocation(value);
                navigate(side, location); dialog.dismiss();
            } catch (RuntimeException bad) { input.setError(bad.getMessage()); }
        })); dialog.show();
    }

    private void transfer(boolean move) {
        List<FileEntry> entries = requireSelection(); if (entries.isEmpty()) return;
        FileAccess fs = access(); FileLocation destination = otherDirectory(); FileJobs resolver = jobs;
        List<FileLocation> sources = locations(entries);
        Runnable action = () -> runOperation(move ? "Move to other column" : "Copy to other column", monitor -> {
            FileOperations.Result result = new FileOperations(fs).transfer(sources, destination, move, resolver, monitor);
            return result.files + " items · " + result.bytes + " bytes · " + result.skipped + " skipped"
                + (result.warnings.isEmpty() ? "" : "\nWarnings:\n" + join(result.warnings));
        });
        if (move) confirm("Move " + entries.size() + " selected entries?", "Destination: " + display(destination)
            + "\nSources are removed only after copying. Completed changes remain if the task is cancelled.", "Move", action);
        else action.run();
    }
    private void create(boolean directory) {
        EditText name = input("Name"); FileAccess fs = access(); FileLocation parent = activeDirectory();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(directory ? "New folder" : "New empty file")
            .setView(padded(name)).setNegativeButton("Cancel", null).setPositiveButton("Create", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString();
            try { FileLocation.validateName(value); } catch (RuntimeException bad) { name.setError(bad.getMessage()); return; }
            dialog.dismiss(); runOperation(directory ? "Create folder" : "Create file", monitor -> {
                monitor.checkCancelled();
                if (directory) fs.createDirectory(parent, value); else fs.createFile(parent, value);
                return "Created " + value;
            });
        })); dialog.show();
    }
    private void deleteSelection() {
        List<FileEntry> entries = requireSelection(); if (entries.isEmpty()) return;
        FileAccess fs = access(); List<FileLocation> targets = locations(entries);
        confirm("Permanently delete " + entries.size() + " selected entries?", names(entries)
            + "\nFolders include their contents. This cannot be undone. Cancellation keeps deletions already completed.", "Delete", () ->
            runOperation("Delete selected entries", monitor -> new FileOperations(fs).remove(targets, monitor).files + " entries deleted"));
    }

    static final class RenamePreview implements FileWork {
        final FileAccess fs;
        final List<FileEntry> entries;
        final String regex, replacement;
        final int first;
        Map<FileLocation, String> plan;
        RenamePreview(FileAccess fs, List<FileEntry> entries, String regex, String replacement, int first) {
            this.fs = fs; this.entries = entries; this.regex = regex; this.replacement = replacement; this.first = first;
        }
        @Override public String run(FileOperations.Monitor monitor) throws Exception {
            monitor.checkCancelled();
            plan = new FileOperations(fs).renamePlan(locations(entries), regex, replacement, first);
            monitor.checkCancelled(); return "Rename preview ready";
        }
    }
    private void renameDialog() {
        List<FileEntry> entries = requireSelection(); if (entries.isEmpty()) return;
        LinearLayout form = column();
        EditText regex = input("Name regex"); regex.setText("^.*$");
        EditText replacement = input(entries.size() == 1 ? "New name (escape $ and \\)" : "Replacement: $1 groups; {n} numbering");
        if (entries.size() == 1) replacement.setText(java.util.regex.Matcher.quoteReplacement(entries.get(0).name));
        EditText first = input("First number"); first.setInputType(InputType.TYPE_CLASS_NUMBER); first.setText("1");
        form.addView(label("Names are checked before confirmation.\nUse $1 for captured groups and {n} for numbering.", 13, muted));
        form.addView(regex); form.addView(replacement); form.addView(first);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Rename " + entries.size() + " entries")
            .setView(padded(form)).setNegativeButton("Cancel", null).setPositiveButton("Preview", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Pattern.compile(regex.getText().toString()); int start = Integer.parseInt(first.getText().toString());
                RenamePreview preview = new RenamePreview(access(), entries, regex.getText().toString(), replacement.getText().toString(), start);
                dialog.dismiss(); runOperation("Preparing rename preview", preview);
            } catch (RuntimeException bad) { message("Invalid rename", bad.getMessage()); }
        })); dialog.show();
    }
    private void showRenamePreview(RenamePreview preview) {
        model.renamePreview = preview;
        StringBuilder names = new StringBuilder();
        for (FileEntry entry : preview.entries) names.append(entry.name).append(" → ").append(preview.plan.get(entry.location)).append('\n');
        FileAccess fs = preview.fs; Map<FileLocation, String> plan = new LinkedHashMap<>(preview.plan);
        renameConfirmation = new AlertDialog.Builder(this).setTitle("Confirm rename preview").setView(scrollText(names.toString()))
            .setNegativeButton("Cancel", (dialog, which) -> model.renamePreview = null)
            .setPositiveButton("Rename", (dialog, which) -> {
                model.renamePreview = null;
                runOperation("Rename entries", monitor -> {
                    for (FileEntry expected : preview.entries) {
                        monitor.checkCancelled();
                        FileEntry current = fs.stat(expected.location);
                        if (current.directory != expected.directory || current.symbolicLink != expected.symbolicLink
                            || current.size != expected.size || current.modified != expected.modified)
                            throw new IOException("Entry changed since the preview; generate a new preview: " + expected.name);
                    }
                    FileOperations operations = new FileOperations(fs);
                    Map<FileLocation, String> rechecked = operations.renamePlan(locations(preview.entries), preview.regex, preview.replacement, preview.first);
                    if (!plan.equals(rechecked)) throw new IOException("Rename plan changed; generate a new preview");
                    operations.batchRename(plan, monitor); return plan.size() + " entries renamed";
                });
            }).create();
        renameConfirmation.setOnCancelListener(dialog -> model.renamePreview = null);
        renameConfirmation.setOnDismissListener(dialog -> renameConfirmation = null);
        renameConfirmation.show();
    }

    private void properties() {
        List<FileEntry> entries = requireSelection(); if (entries.isEmpty()) return;
        FileAccess fs = access();
        runOperation("Properties", new PropertiesWork(fs, entries));
    }
    private static final class PropertiesWork implements FileWork {
        final FileAccess fs; final List<FileEntry> entries;
        PropertiesWork(FileAccess fs, List<FileEntry> entries) { this.fs = fs; this.entries = entries; }
        @Override public String run(FileOperations.Monitor monitor) throws Exception {
            StringBuilder value = new StringBuilder();
            for (FileEntry selected : entries) {
                monitor.checkCancelled(); FileEntry entry = fs.stat(selected.location);
                value.append(entry.name).append('\n').append(display(entry.location)).append('\n')
                    .append(entry.directory ? "Directory" : "File").append(entry.symbolicLink ? " · symbolic link" : "")
                    .append(" · ").append(entry.size).append(" bytes\nModified: ").append(new Date(entry.modified))
                    .append("\nPermissions: ").append(fs.permissions(entry.location));
                if (entry.symbolicLink) value.append("\nTarget: ").append(fs.readLink(entry.location));
                value.append("\n\n");
            }
            return value.toString();
        }
    }
    private void permissions(List<FileEntry> entries) {
        EditText mode = input("Octal mode, e.g. 0644"); mode.setInputType(InputType.TYPE_CLASS_NUMBER);
        FileAccess fs = access(); List<FileLocation> targets = locations(entries);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Change Unix permissions")
            .setMessage("Applies to selected entries only. Unsupported storage reports an error.")
            .setView(padded(mode)).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = mode.getText().toString();
            if (!raw.matches("[0-7]{3,4}")) { mode.setError("Enter 3 or 4 octal digits"); return; }
            int value = Integer.parseInt(raw, 8); dialog.dismiss();
            runOperation("Change permissions", monitor -> {
                for (FileLocation location : targets) { monitor.checkCancelled(); fs.chmod(location, value); }
                return targets.size() + " entries updated";
            });
        })); dialog.show();
    }
    private void checksumDialog() {
        List<FileEntry> entries = requireSelection(); if (entries.isEmpty()) return;
        for (FileEntry entry : entries) if (entry.directory || entry.symbolicLink) { tip("Choose regular files for checksums"); return; }
        String[] algorithms = {"SHA-256", "SHA-512", "SHA-1", "MD5", "CRC32"}; FileAccess fs = access();
        new AlertDialog.Builder(this).setTitle("Checksum algorithm").setItems(algorithms, (dialog, which) -> runOperation(algorithms[which], monitor -> {
            FileOperations operations = new FileOperations(fs); StringBuilder results = new StringBuilder();
            for (FileEntry entry : entries) {
                monitor.checkCancelled(); results.append(entry.name).append('\n').append(operations.checksum(entry.location, algorithms[which], monitor)).append("\n\n");
            }
            return results.toString();
        })).setNegativeButton("Cancel", null).show();
    }

    private static final class SearchWork implements FileWork {
        final FileAccess fs; final FileLocation root; final String query;
        final boolean regex, content, hidden; final int side; final long revision;
        FileSearch.Result found;
        SearchWork(FileAccess fs, FileLocation root, String query, boolean regex, boolean content, boolean hidden, int side, long revision) {
            this.fs = fs; this.root = root; this.query = query; this.regex = regex; this.content = content; this.hidden = hidden; this.side = side; this.revision = revision;
        }
        @Override public String run(FileOperations.Monitor monitor) throws Exception {
            found = new FileSearch(fs).search(root, content ? "" : query, content ? query : "", regex,
                true, true, monitor, null);
            return found.hits.size() + " matches · " + found.examined + " entries inspected"
                + (found.errors.isEmpty() ? "" : "\nRead / decoding errors:\n" + join(found.errors));
        }
    }
    private void searchDialog() {
        LinearLayout form = column(); EditText query = input("Search query");
        CheckBox regex = check("Regular expression"), content = check("Search UTF-8 line content");
        form.addView(query); form.addView(regex); form.addView(content);
        form.addView(label("Searches recursively, including hidden folders, without following symbolic links. Matching is case sensitive. Content matches within a line; decoding and read failures are reported. Results replace this column until Refresh.", 12, muted));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Search active folder")
            .setView(padded(form)).setNegativeButton("Cancel", null).setPositiveButton("Search", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = query.getText().toString();
            if (value.isEmpty()) { query.setError("Enter a query"); return; }
            try { if (regex.isChecked()) Pattern.compile(value); }
            catch (PatternSyntaxException bad) { query.setError(bad.getDescription()); return; }
            if (jobs.snapshot().running) { tip("Wait for the active task, or cancel it first"); return; }
            int side = model.active; model.invalidate(side); PaneState pane = model.panes[side];
            SearchWork search = new SearchWork(access(), pane.directory, value, regex.isChecked(), content.isChecked(), true, side, pane.revision);
            dialog.dismiss(); runOperation("Recursive search", search);
        })); dialog.show();
    }

    private JSONArray readBookmarks() {
        if (model.bookmarkError != null && model.bookmarkJson == null) { message("Bookmarks could not be loaded", model.bookmarkError); return null; }
        if (model.bookmarkJson == null || model.savingBookmarks) { tip("Bookmarks are loading / saving; try again shortly"); return null; }
        try { return new JSONArray(model.bookmarkJson); }
        catch (Exception invalid) { message("Bookmarks could not be loaded", invalid.getMessage()); return null; }
    }
    private void bookmarks() {
        JSONArray entries = readBookmarks(); if (entries == null) return; List<String> labels = new ArrayList<>();
        labels.add("＋ Bookmark active folder"); labels.add("− Remove a bookmark");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i); labels.add(entry == null ? "Unreadable bookmark" : entry.optString("name", "Folder"));
        }
        new AlertDialog.Builder(this).setTitle("Bookmarks").setItems(labels.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) addBookmark();
            else if (which == 1) removeBookmark(entries);
            else try { navigate(FileLocation.fromToken(entries.getJSONObject(which - 2).getString("location"))); }
            catch (Exception invalid) { message("Cannot open bookmark", invalid.getMessage()); }
        }).setNegativeButton("Close", null).show();
    }
    private void addBookmark() {
        FileLocation location = activeDirectory(); EditText name = input("Bookmark name"); name.setText(display(location));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Bookmark folder").setView(padded(name))
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim(); if (label.isEmpty()) { name.setError("Enter a name"); return; }
            try {
                JSONArray entries = readBookmarks(); if (entries == null) return;
                JSONObject bookmark = new JSONObject(); bookmark.put("name", label); bookmark.put("location", location.token()); entries.put(bookmark);
                model.saveBookmarks(entries.toString()); dialog.dismiss();
            } catch (Exception failure) { message("Bookmark failed", failure.getMessage()); }
        })); dialog.show();
    }
    private void removeBookmark(JSONArray entries) {
        String[] labels = new String[entries.length()];
        for (int i = 0; i < labels.length; i++) labels[i] = entries.optJSONObject(i) == null ? "Unreadable bookmark" : entries.optJSONObject(i).optString("name");
        if (labels.length == 0) { tip("No saved bookmarks"); return; }
        new AlertDialog.Builder(this).setTitle("Remove bookmark").setItems(labels, (dialog, which) -> {
            JSONArray remaining = new JSONArray(); for (int i = 0; i < entries.length(); i++) if (i != which) remaining.put(entries.opt(i));
            model.saveBookmarks(remaining.toString());
        }).setNegativeButton("Cancel", null).show();
    }
    void bookmarkSaved(String error) { if (error == null) tip("Bookmarks saved"); else message("Bookmarks could not be saved", error); }

    @Override public void jobsChanged() {
        FileJobs.Snapshot state = jobs.snapshot();
        jobStrip.setVisibility(state.running ? View.VISIBLE : View.GONE);
        jobStatus.setText(state.title + "\n" + (state.cancelled ? "Cancelling…" : state.source != null ? "Waiting for conflict choice" : state.progress));
        if (state.running && state.source != null && (conflictId != state.id || !state.source.location.uri.equals(conflictUri))) {
            if (conflictDialog != null) conflictDialog.dismiss();
            showConflict(state);
        } else if (state.source == null && conflictDialog != null) {
            conflictDialog.dismiss(); conflictDialog = null; conflictId = -1; conflictUri = null;
        }
        if (!state.running && (state.result != null || state.error != null)) {
            jobs.acknowledge(state.id);
            if (state.error != null) { refreshPanels(); message(state.title + " failed", state.error); return; }
            if (state.work instanceof RenamePreview) { showRenamePreview((RenamePreview) state.work); return; }
            if (state.work instanceof SearchWork) {
                SearchWork search = (SearchWork) state.work; PaneState pane = model.panes[search.side];
                if (pane.directory.equals(search.root) && pane.revision == search.revision) {
                    List<FileEntry> found = new ArrayList<>();
                    for (FileSearch.Hit hit : search.found.hits) found.add(hit.entry);
                    pane.accept(found); pane.searchLabel = search.query; pane.selected.clear(); pane.filter = "";
                    pane.hidden = search.hidden; pane.scrollPosition = 0; pane.scrollOffset = 0; renderPanels();
                    message("Search results", state.result);
                } else message("Search finished", state.result + "\nResults were not installed because the column changed. Run the search again in the intended folder.");
                return;
            }
            if (state.work instanceof PropertiesWork) {
                PropertiesWork properties = (PropertiesWork) state.work;
                new AlertDialog.Builder(this).setTitle("Properties").setView(scrollText(state.result))
                    .setNegativeButton("Close", null).setPositiveButton("Permissions…", (d, w) -> permissions(properties.entries)).show();
                return;
            }
            refreshPanels();
            if (state.result != null && !state.result.isEmpty()) message(state.title, state.result);
        }
    }
    private void showConflict(FileJobs.Snapshot state) {
        conflictId = state.id; conflictUri = state.source.location.uri;
        CheckBox all = check("Apply this choice to all remaining conflicts");
        LinearLayout form = column(); form.addView(label(state.source.name + "\nDestination already exists:\n" + display(state.target.location)
            + "\nSource: " + bytes(state.source.size) + "\nExisting: " + bytes(state.target.size)
            + "\nReplace merges directories; incompatible types require Keep both.", 14, text)); form.addView(all);
        String[] choices = {"Replace", "Keep both", "Skip", "Cancel task"};
        FileOperations.Conflict[] values = {FileOperations.Conflict.REPLACE, FileOperations.Conflict.KEEP_BOTH, FileOperations.Conflict.SKIP, FileOperations.Conflict.CANCEL};
        for (int i = 0; i < choices.length; i++) {
            final FileOperations.Conflict choice = values[i];
            form.addView(button(choices[i], choices[i], v -> {
                jobs.answer(state.id, choice, all.isChecked());
                if (conflictDialog != null) conflictDialog.dismiss();
                conflictDialog = null; conflictId = -1; conflictUri = null;
            }));
        }
        conflictDialog = new AlertDialog.Builder(this).setTitle("File conflict").setView(padded(form)).create();
        conflictDialog.setOnCancelListener(dialog -> jobs.answer(state.id, FileOperations.Conflict.CANCEL, false));
        conflictDialog.show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        shift = event.isShiftPressed();
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        boolean typing = getCurrentFocus() instanceof EditText;
        int key = event.getKeyCode();
        if (event.isAltPressed()) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { history(model.active, true); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { history(model.active, false); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP) { model.up(model.active); return true; }
        }
        if (event.isCtrlPressed()) {
            if (key == KeyEvent.KEYCODE_L) { pathDialog(model.active); return true; }
            if (key == KeyEvent.KEYCODE_F) {
                if (compactChrome) filterDialog(model.active); else views[model.active].filter.requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_A && !typing) { model.panes[model.active].selectAll(false); renderPanels(); return true; }
        }
        switch (key) {
            case KeyEvent.KEYCODE_F2: renameDialog(); return true;
            case KeyEvent.KEYCODE_F5: transfer(false); return true;
            case KeyEvent.KEYCODE_F6: transfer(true); return true;
            case KeyEvent.KEYCODE_F7: create(true); return true;
            case KeyEvent.KEYCODE_F8: deleteSelection(); return true;
        }
        if (typing) return super.dispatchKeyEvent(event);
        switch (key) {
            case KeyEvent.KEYCODE_TAB: activate(1 - model.active); views[model.active].list.requestFocus(); return true;
            case KeyEvent.KEYCODE_FORWARD_DEL: deleteSelection(); return true;
            case KeyEvent.KEYCODE_ESCAPE: model.panes[model.active].selected.clear(); renderPanels(); return true;
            case KeyEvent.KEYCODE_SPACE:
                int position = views[model.active].list.getSelectedItemPosition();
                if (position >= 0 && position < views[model.active].adapter.entries.size()) {
                    model.panes[model.active].select(views[model.active].adapter.entries.get(position), event.isShiftPressed()); renderPanels(); return true;
                }
                break;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        PaneState pane = model.panes[model.active];
        if (!pane.selected.isEmpty()) { pane.selected.clear(); renderPanels(); }
        else if (pane.searchLabel != null) model.load(model.active);
        else if (pane.history(true)) model.load(model.active);
        else if (jobs.snapshot().running) confirm("Keep task running?", "The foreground notification keeps progress and cancellation available.", "Leave Files", this::finish);
        else super.onBackPressed();
    }

    private void item(PopupMenu menu, String title, Runnable action) { menu.getMenu().add(title).setOnMenuItemClickListener(item -> { action.run(); return true; }); }
    private void confirm(String title, String body, String action, Runnable next) {
        new AlertDialog.Builder(this).setTitle(title).setView(scrollText(body)).setNegativeButton("Cancel", null)
            .setPositiveButton(action, (dialog, which) -> next.run()).show();
    }
    private void message(String title, String body) { new AlertDialog.Builder(this).setTitle(title).setView(scrollText(body == null ? "No further details" : body)).setPositiveButton("Close", null).show(); }
    private View scrollText(String body) { TextView value = label(body, 14, text); value.setTextIsSelectable(true); ScrollView scroll = new ScrollView(this); scroll.addView(padded(value)); return scroll; }
    private View padded(View child) { LinearLayout wrapper = column(); wrapper.setPadding(dp(18), dp(8), dp(18), dp(8)); wrapper.addView(child); return wrapper; }
    private void tip(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private CheckBox check(String value) { CheckBox check = new CheckBox(this); check.setText(value); check.setTextColor(text); return check; }
    private EditText input(String hint) { EditText input = new EditText(this); input.setHint(hint); input.setTextColor(text); input.setHintTextColor(muted); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); return input; }
    private TextView label(String value, int size, int color) { TextView label = new TextView(this); label.setText(value); label.setTextSize(size); label.setTextColor(color); label.setGravity(Gravity.CENTER_VERTICAL); return label; }
    private Button button(String value, String description, View.OnClickListener action) {
        Button button = new Button(this); button.setText(value); button.setTextSize(12); button.setTextColor(accent);
        button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0); button.setContentDescription(description); button.setOnClickListener(action);
        return button;
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private void addEqual(LinearLayout row, View view) { row.addView(view, new LinearLayout.LayoutParams(0, dp(44), 1)); }
    private HorizontalScrollView horizontal(View child) { HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); scroll.addView(child); return scroll; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String display(FileLocation location) { return location.isLocal() ? location.file().getAbsolutePath() : location.uri; }
    private static FileLocation localDirectory(File directory) {
        // File.toURI() stats the path to decide on a trailing slash. These are known folder requests;
        // construct the URI without touching storage, and let the asynchronous listing validate it.
        String path = directory.getAbsolutePath();
        try { return new FileLocation(new URI("file", null, path.endsWith("/") ? path : path + "/", null).toString()); }
        catch (URISyntaxException invalid) { throw new IllegalArgumentException("Invalid folder path", invalid); }
    }
    private static String bytes(long size) {
        if (size < 0) return "Size unknown";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", size / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GiB", size / (1024.0 * 1024 * 1024));
    }
    private static String join(List<String> values) { StringBuilder text = new StringBuilder(); for (String value : values) text.append(value).append('\n'); return text.toString(); }
    private static String names(List<FileEntry> entries) { StringBuilder value = new StringBuilder(); for (int i = 0; i < entries.size() && i < 30; i++) value.append(entries.get(i).name).append('\n'); if (entries.size() > 30) value.append("… and ").append(entries.size() - 30).append(" more\n"); return value.toString(); }
}
