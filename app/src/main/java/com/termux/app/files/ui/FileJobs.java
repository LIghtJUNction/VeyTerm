package com.termux.app.files.ui;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileOperations;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One process-owned task, with no strong Activity reference. Never replays mutations after process death. */
final class FileJobs implements FileOperations.Monitor, FileOperations.ConflictResolver {
    interface Listener { void jobsChanged(); }
    static final class Snapshot {
        final String title, progress, result, error;
        final boolean running, cancelled;
        final FileEntry source, target;
        final long id;
        final FileManagerActivity.FileWork work;
        Snapshot(FileJobs jobs) {
            title = jobs.title; progress = jobs.progress; result = jobs.result; error = jobs.error;
            running = jobs.running; cancelled = jobs.cancelled; source = jobs.source; target = jobs.target;
            id = jobs.id; work = jobs.work;
        }
    }
    private static FileJobs instance;
    static synchronized FileJobs get(Context context) {
        if (instance == null) instance = new FileJobs(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final SharedPreferences journal;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WeakReference<Listener> listener = new WeakReference<>(null);
    private String title = "", progress = "", result, error;
    private boolean running, cancelled, dispatched;
    private boolean journalReady;
    private long id, lastProgress;
    private FileManagerActivity.FileWork work;
    private FileEntry source, target;
    private FileOperations.Conflict answer, applyAll;

    private FileJobs(Context context) {
        this.context = context;
        journal = context.getSharedPreferences("fm_task_journal", Context.MODE_PRIVATE);
        // Read on the worker, so even a cold SharedPreferences load cannot block the UI.
        executor.execute(() -> {
            try {
                boolean interrupted = journal.getBoolean("running", false);
                synchronized (FileJobs.this) {
                    if (interrupted) {
                        title = journal.getString("title", "Previous file task");
                        error = "Android ended the previous process. The task was not replayed. Review both columns before retrying; completed or temporary files may remain.";
                        journal.edit().putBoolean("running", false).apply();
                    }
                }
            } catch (RuntimeException failure) {
                synchronized (FileJobs.this) { error = "Cannot read the task recovery state: " + failure; }
            } finally {
                synchronized (FileJobs.this) { journalReady = true; }
                changed();
            }
        });
    }
    synchronized Snapshot snapshot() { return new Snapshot(this); }
    synchronized void attach(Listener next) { listener = new WeakReference<>(next); }
    synchronized void detach(Listener previous) { if (listener.get() == previous) listener.clear(); }

    void start(String title, FileManagerActivity.FileWork next) {
        synchronized (this) {
            if (!journalReady) throw new IllegalStateException("Task recovery state is still loading; try again shortly");
            if (running) throw new IllegalStateException("A task is already running. Cancel it or wait for completion.");
            this.title = title; progress = "Starting…"; result = null; error = null;
            cancelled = false; dispatched = false; running = true; source = null; target = null;
            answer = null; applyAll = null; work = next; id++;
        }
        try {
            Intent service = new Intent(context, FileTaskService.class);
            ComponentName started = Build.VERSION.SDK_INT >= 26 ? context.startForegroundService(service) : context.startService(service);
            if (started == null) throw new IllegalStateException("FileTaskService is not registered in the application manifest");
        } catch (RuntimeException failure) {
            serviceFailed(failure);
            return;
        }
        changed();
    }

    void serviceFailed(RuntimeException failure) {
        synchronized (this) {
            if (dispatched) { cancelled = true; notifyAll(); }
            else { running = false; error = "Cannot start the background task: " + failure.getMessage(); }
        }
        changed();
    }

    void serviceReady() {
        final FileManagerActivity.FileWork next;
        final String taskTitle;
        synchronized (this) {
            if (!running || dispatched) return;
            dispatched = true;
            next = work;
            taskTitle = title;
        }
        executor.execute(() -> {
            try {
                if (!journal.edit().putString("title", taskTitle).putBoolean("running", true).commit())
                    throw new IOException("Could not save the task recovery marker; no file operation was started");
                checkCancelled();
                String message = next.run(this);
                synchronized (this) { result = message == null ? "Completed" : message; }
            } catch (Exception failure) {
                StringBuilder detail = new StringBuilder(failure instanceof InterruptedIOException && cancelled
                    ? "Cancelled. Completed changes remain; review both columns."
                    : failure.getClass().getSimpleName() + ": " + failure.getMessage()
                        + "\nSome changes may have completed before the error. Review both columns before retrying.");
                for (Throwable suppressed : failure.getSuppressed())
                    detail.append("\nRecovery warning: ").append(suppressed.getMessage());
                synchronized (this) { error = detail.toString(); }
            } finally {
                boolean journalCleared;
                try { journalCleared = journal.edit().putBoolean("running", false).commit(); }
                catch (RuntimeException failure) { journalCleared = false; }
                if (!journalCleared) {
                    synchronized (this) {
                        String warning = "Could not clear the recovery marker; the next launch may report an interrupted task.";
                        if (error == null) result = (result == null ? "" : result + "\n") + warning;
                        else error += "\n" + warning;
                    }
                }
                synchronized (this) { running = false; source = null; target = null; notifyAll(); }
                main.post(() -> context.stopService(new Intent(context, FileTaskService.class)));
                changed();
            }
        });
    }

    synchronized void acknowledge(long completedId) {
        if (!running && completedId == id) { result = null; error = null; work = null; }
    }

    synchronized void cancel() { cancelled = true; notifyAll(); changed(); }

    @Override public synchronized void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Task cancelled");
    }

    @Override public void progress(String name, long bytes, long files) {
        synchronized (this) {
            progress = name + " · " + bytes + " bytes · " + files + " items";
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastProgress < 200) return;
            lastProgress = now;
        }
        changed();
    }

    @Override public synchronized FileOperations.Conflict resolve(FileEntry from, FileEntry to) throws IOException {
        checkCancelled();
        if (applyAll != null) return applyAll;
        source = from; target = to; answer = null;
        changed();
        try {
            while (answer == null) {
                checkCancelled();
                try { wait(500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); checkCancelled(); }
            }
            return answer;
        } finally { source = null; target = null; changed(); }
    }

    synchronized void answer(long expectedId, FileOperations.Conflict choice, boolean all) {
        if (id != expectedId || !running || source == null) return;
        answer = choice;
        if (choice == FileOperations.Conflict.CANCEL) cancelled = true;
        source = null;
        target = null;
        if (all && choice != FileOperations.Conflict.CANCEL) applyAll = choice;
        notifyAll();
    }

    private void changed() {
        main.post(() -> {
            Listener current;
            synchronized (FileJobs.this) { current = listener.get(); }
            if (current != null) current.jobsChanged();
            FileTaskService.updateIfRunning();
        });
    }
}
