package com.termux.app.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.termux.app.files.fs.FileLocation;
import com.termux.app.files.ui.FileManagerActivity;

import java.util.ArrayList;

/** Retains completed tool requests while the workspace rotates or is in the background. */
public final class FileToolState extends Fragment {
    private static final String TAG = "file-tool-state";
    private static final String NAVIGATE = "com.veyterm.files.NAVIGATE";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Intent> pending = new ArrayList<>();
    private boolean restored;

    public FileToolState() { setRetainInstance(true); }
    public static FileToolState of(FileManagerActivity activity) {
        FileToolState state = (FileToolState) activity.getFragmentManager().findFragmentByTag(TAG);
        if (state == null) {
            state = new FileToolState();
            activity.getFragmentManager().beginTransaction().add(state, TAG).commit();
            activity.getFragmentManager().executePendingTransactions();
        }
        return state;
    }
    public synchronized void restore(Bundle state) {
        if (restored) return; restored = true;
        if (state != null) {
            ArrayList<Intent> saved = state.getParcelableArrayList("fm_tool_requests");
            if (saved != null) pending.addAll(saved);
        }
    }
    public synchronized void save(Bundle state) { state.putParcelableArrayList("fm_tool_requests", new ArrayList<>(pending)); }
    public void launch(Intent intent, int request) { intent.putExtra("fm_request", request); enqueue(intent); }
    public void navigate(FileLocation location) { enqueue(new Intent(NAVIGATE).putExtra("fm_location", location.token())); }
    private void enqueue(Intent intent) { synchronized (this) { pending.add(intent); } main.post(this::drain); }
    @Override public void onResume() { super.onResume(); drain(); }
    private void drain() {
        Activity current = getActivity();
        if (!(current instanceof FileManagerActivity) || !isResumed() || current.isFinishing() || current.isDestroyed()) return;
        FileManagerActivity activity = (FileManagerActivity) current;
        while (true) {
            Intent next;
            synchronized (this) { if (pending.isEmpty()) return; next = pending.remove(0); }
            try {
                if (NAVIGATE.equals(next.getAction())) activity.navigate(FileLocation.fromToken(next.getStringExtra("fm_location")));
                else { activity.startActivityForResult(next, next.getIntExtra("fm_request", 0)); return; }
            } catch (RuntimeException failure) {
                new AlertDialog.Builder(activity).setTitle("Cannot open tool").setMessage(failure.getMessage()).setPositiveButton("Close", null).show();
            }
        }
    }
}
