package com.termux.app.files.ui;

import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.termux.app.files.fs.FileAccess;
import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Retained across rotation; directory I/O never runs on the main thread. */
public final class WorkspaceModel extends Fragment {
    final PaneState[] panes = new PaneState[2];
    int active;
    FileAccess access;
    FileManagerActivity.RenamePreview renamePreview;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService readers = Executors.newFixedThreadPool(2);
    private final Future<?>[] pending = new Future<?>[2];
    String bookmarkJson;
    String bookmarkError;
    boolean savingBookmarks;
    private SharedPreferences bookmarkStore;

    void loadBookmarks(Context context) {
        readers.submit(() -> {
            try {
                SharedPreferences store = context.getSharedPreferences("fm_bookmarks", Context.MODE_PRIVATE);
                String json = store.getString("entries", "[]");
                main.post(() -> { bookmarkStore = store; bookmarkJson = json; });
            } catch (RuntimeException error) { main.post(() -> bookmarkError = error.toString()); }
        });
    }

    void saveBookmarks(String json) {
        savingBookmarks = true;
        readers.submit(() -> {
            String failure = null;
            try {
                if (!bookmarkStore.edit().putString("entries", json).commit()) failure = "Storage refused to save bookmarks";
            } catch (RuntimeException error) { failure = error.toString(); }
            String error = failure;
            main.post(() -> {
                savingBookmarks = false;
                if (error == null) bookmarkJson = json;
                bookmarkError = error;
                if (getActivity() instanceof FileManagerActivity)
                    ((FileManagerActivity) getActivity()).bookmarkSaved(error);
            });
        });
    }

    void invalidate(int side) {
        panes[side].revision++;
        panes[side].loading = false;
        if (pending[side] != null) pending[side].cancel(true);
    }

    @Override public void onCreate(Bundle savedInstanceState) { super.onCreate(savedInstanceState); setRetainInstance(true); }

    void load(int side) {
        PaneState pane = panes[side];
        FileLocation directory = pane.directory;
        long revision = ++pane.revision;
        pane.loading = true;
        pane.error = null;
        pane.searchLabel = null;
        if (pending[side] != null) pending[side].cancel(true);
        render();
        pending[side] = readers.submit(() -> {
            try {
                List<FileEntry> entries = access.list(directory);
                main.post(() -> {
                    if (panes[side] != pane || pane.revision != revision) return;
                    pane.accept(entries);
                    render();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (panes[side] != pane || pane.revision != revision) return;
                    pane.loading = false;
                    pane.error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    render();
                });
            }
        });
    }

    void up(int side) {
        PaneState pane = panes[side];
        FileLocation directory = pane.directory;
        long revision = ++pane.revision;
        pane.loading = true;
        render();
        if (pending[side] != null) pending[side].cancel(true);
        pending[side] = readers.submit(() -> {
            try {
                FileLocation parent = access.parent(directory);
                main.post(() -> {
                    if (panes[side] != pane || pane.revision != revision) return;
                    pane.loading = false;
                    if (parent != null) { pane.navigate(parent); load(side); }
                    else render();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (panes[side] != pane || pane.revision != revision) return;
                    pane.loading = false;
                    pane.error = failure.getMessage();
                    render();
                });
            }
        });
    }

    void render() {
        if (getActivity() instanceof FileManagerActivity) ((FileManagerActivity) getActivity()).renderPanels();
    }

    @Override public void onDestroy() { readers.shutdownNow(); super.onDestroy(); }
}
