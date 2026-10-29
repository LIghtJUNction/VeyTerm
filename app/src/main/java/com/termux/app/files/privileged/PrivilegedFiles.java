package com.termux.app.files.privileged;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import com.termux.app.files.fs.FileAccess;
import com.termux.app.files.fs.FileLocation;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;

import java.io.IOException;
import java.lang.ref.WeakReference;

import rikka.shizuku.Shizuku;

/** Entry point. Permission/binding is performed exclusively by the explicit connect methods. */
public final class PrivilegedFiles {
    public interface Callback {
        void connected(int uid);
        void failed(String message);
    }

    private static PrivilegedFiles instance;
    private final Application application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PrivilegedFileAccess rootAccess = new PrivilegedFileAccess("root");
    private final PrivilegedFileAccess shizukuAccess = new PrivilegedFileAccess("shizuku");
    private Connection root;
    private Connection shizuku;
    private int requestSequence;
    private boolean lifecycleRegistered;

    public static synchronized PrivilegedFiles get(Context context) {
        if (instance == null) instance = new PrivilegedFiles((Application) context.getApplicationContext());
        return instance;
    }

    private PrivilegedFiles(Application application) { this.application = application; }
    public FileAccess rootAccess() { return rootAccess; }
    public FileAccess shizukuAccess() { return shizukuAccess; }

    public static FileLocation location(String scheme, String absolutePath) throws IOException {
        return new FileLocation(PrivilegedPaths.uri(scheme, absolutePath));
    }

    /** Call on the I/O executor before the parent's recursive delete/rename/move begins. */
    public void requireDeletable(FileLocation location) throws IOException {
        String scheme = java.net.URI.create(location.uri).getScheme();
        if ("root".equals(scheme)) rootAccess.requireDeletable(location);
        else if ("shizuku".equals(scheme)) shizukuAccess.requireDeletable(location);
        else throw new IOException("Expected a ROOT or Shizuku location");
    }

    public void connectRoot(Activity activity, Callback callback) {
        requireMain();
        if (!validOwner(activity, callback)) return;
        if (reuse(root, callback)) return;
        Connection connection = new Connection(true, activity, callback);
        root = connection;
        registerLifecycle();
        main.postDelayed(connection.timeout, 60000);
        // Obtain permission before registering a pending libsu bind. A denial must not
        // leave a queued bind that could unexpectedly start on a later root attempt.
        new Thread(() -> {
            try (Shell shell = Shell.Builder.create().setTimeout(30).build()) {
                if (connection.cancelled) return;
                if (!shell.isRoot()) throw new IOException("Real ROOT permission was denied or su is unavailable");
                java.util.concurrent.FutureTask<Shell.Task> prepare = new java.util.concurrent.FutureTask<>(() -> {
                    if (!current(connection)) return null;
                    connection.binding = true;
                    return RootService.bindOrTask(new Intent(application, RootFilesService.class),
                        command -> main.post(command), connection);
                });
                main.post(prepare);
                Shell.Task launch = prepare.get();
                // Once queued, complete the launch even after cancellation; a late Binder
                // is immediately unbound. Never strand libsu's pending launch state.
                if (launch != null) shell.execTask(launch);
            } catch (Exception e) { main.post(() -> fail(connection, "ROOT launch failed: " + message(e))); }
        }, "priv_files_root_launch").start();
    }

    public void connectShizuku(Activity activity, Callback callback) {
        requireMain();
        if (!validOwner(activity, callback)) return;
        if (reuse(shizuku, callback)) return;
        if (Build.VERSION.SDK_INT < 24) { callback.failed("Shizuku API 13.1.5 requires Android 7 or later"); return; }
        Connection connection = new Connection(false, activity, callback);
        shizuku = connection;
        registerLifecycle();
        main.postDelayed(connection.timeout, 60000);
        try {
            if (!Shizuku.pingBinder()) { fail(connection, "Start Shizuku, then press Connect again"); return; }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                fail(connection, "Update Shizuku to server version 13 or later"); return;
            }
            Shizuku.addBinderDeadListener(connection.shizukuDied);
            connection.deathListenerRegistered = true;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindShizuku(connection);
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                fail(connection, "Shizuku permission was denied; allow VeyTerm in Shizuku before reconnecting");
            } else {
                connection.requestCode = 0x56540000 | (++requestSequence & 0xffff);
                Shizuku.addRequestPermissionResultListener(connection.permission);
                connection.permissionRegistered = true;
                Shizuku.requestPermission(connection.requestCode);
            }
        } catch (RuntimeException e) { fail(connection, "Shizuku connection failed: " + message(e)); }
    }

    private void bindShizuku(Connection connection) {
        if (!current(connection)) return;
        removePermissionListener(connection);
        try {
            connection.userServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(application, ShizukuFilesService.class))
                .tag("veyterm_files_" + Process.myUid() + "_" + java.util.UUID.randomUUID())
                .processNameSuffix("priv_files")
                .version(PrivilegedWire.VERSION).daemon(false).debuggable(false);
            connection.binding = true;
            Shizuku.bindUserService(connection.userServiceArgs, connection);
        } catch (RuntimeException e) { fail(connection, "Cannot start Shizuku file service: " + message(e)); }
    }

    private boolean validOwner(Activity activity, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("A connection callback is required");
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            callback.failed("The Files Activity is no longer open"); return false;
        }
        return true;
    }

    private boolean reuse(Connection connection, Callback callback) {
        if (connection == null) return false;
        if (connection.uid >= 0 && connection.binder != null && connection.binder.isBinderAlive()) callback.connected(connection.uid);
        else callback.failed("A connection is already pending; disconnect or wait for its result");
        return true;
    }

    public void disconnectRoot() { requireMain(); release(root, "ROOT connection closed"); }
    public void disconnectShizuku() { requireMain(); release(shizuku, "Shizuku connection closed"); }
    public void disconnect() { requireMain(); disconnectRoot(); disconnectShizuku(); }

    private boolean current(Connection connection) { return connection != null && (connection.root ? root == connection : shizuku == connection); }
    private void fail(Connection connection, String reason) { if (current(connection)) release(connection, reason); }

    private void release(Connection connection, String reason) {
        if (!current(connection)) return;
        Callback pending = connection.callback;
        connection.callback = null;
        connection.cancelled = true;
        if (connection.root) { root = null; rootAccess.disconnect(); }
        else { shizuku = null; shizukuAccess.disconnect(); }
        main.removeCallbacks(connection.timeout);
        removePermissionListener(connection);
        if (connection.deathListenerRegistered) {
            Shizuku.removeBinderDeadListener(connection.shizukuDied);
            connection.deathListenerRegistered = false;
        }
        if (connection.binder != null) {
            try { connection.binder.unlinkToDeath(connection.died, 0); }
            catch (java.util.NoSuchElementException ignored) { }
        }
        cleanupBinding(connection);
        if (root == null && shizuku == null && lifecycleRegistered) {
            application.unregisterActivityLifecycleCallbacks(lifecycle);
            lifecycleRegistered = false;
        }
        if (pending != null) pending.failed(reason);
    }

    private void cleanupBinding(Connection connection) {
        if (!connection.binding) return;
        connection.binding = false;
        try {
            if (connection.root) RootService.unbind(connection);
            else if (connection.userServiceArgs != null) {
                // remove=true invokes the reserved destroy method; false removes local listeners.
                try { Shizuku.unbindUserService(connection.userServiceArgs, connection, true); }
                finally { Shizuku.unbindUserService(connection.userServiceArgs, connection, false); }
            }
        } catch (RuntimeException ignored) { /* A dead server already lost the service. */ }
    }

    private void removePermissionListener(Connection connection) {
        if (connection.permissionRegistered) {
            Shizuku.removeRequestPermissionResultListener(connection.permission);
            connection.permissionRegistered = false;
        }
    }

    private void registerLifecycle() {
        if (!lifecycleRegistered) {
            application.registerActivityLifecycleCallbacks(lifecycle);
            lifecycleRegistered = true;
        }
    }

    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityDestroyed(Activity activity) {
            if (root != null && root.owner.get() == activity) disconnectRoot();
            if (shizuku != null && shizuku.owner.get() == activity) disconnectShizuku();
        }
        @Override public void onActivityCreated(Activity a, Bundle b) { }
        @Override public void onActivityStarted(Activity a) { }
        @Override public void onActivityResumed(Activity a) { }
        @Override public void onActivityPaused(Activity a) { }
        @Override public void onActivityStopped(Activity a) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    };

    private final class Connection implements ServiceConnection {
        final boolean root;
        final WeakReference<Activity> owner;
        Callback callback;
        IBinder binder;
        int uid = -1;
        int requestCode;
        boolean binding;
        boolean permissionRegistered;
        boolean deathListenerRegistered;
        volatile boolean cancelled;
        Shizuku.UserServiceArgs userServiceArgs;
        final Runnable timeout = () -> fail(this, "Connection timed out; check permission and reconnect explicitly");
        final IBinder.DeathRecipient died = () -> main.post(() -> fail(this, "Privileged file service stopped; reconnect explicitly"));
        final Shizuku.OnBinderDeadListener shizukuDied = () -> main.post(() -> fail(this, "Shizuku stopped; start it and reconnect explicitly"));
        final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> main.post(() -> {
            if (!current(this) || code != requestCode) return;
            if (result == PackageManager.PERMISSION_GRANTED) bindShizuku(this);
            else fail(this, "Shizuku permission denied");
        });

        Connection(boolean root, Activity activity, Callback callback) {
            this.root = root; owner = new WeakReference<>(activity); this.callback = callback;
        }

        @Override public void onServiceConnected(ComponentName name, IBinder connected) {
            main.post(() -> accept(connected));
        }

        private void accept(IBinder connected) {
            if (!current(this)) {
                // A permission dialog can finish after the user has left Files.
                binding = true; cleanupBinding(this); return;
            }
            if (binder != null) return;
            binder = connected;
            try {
                if (connected == null) throw new IOException("Service returned no Binder");
                connected.linkToDeath(died, 0);
                IPrivilegedFiles remote = IPrivilegedFiles.Stub.asInterface(connected);
                new Thread(() -> {
                    try {
                        Bundle identity = PrivilegedWire.checked(remote.execute("identity", new Bundle()));
                        int actualUid = identity.getInt("uid", -1);
                        KernelIdentity.requirePrivilege(actualUid, root);
                        if (identity.getInt("version", -1) != PrivilegedWire.VERSION)
                            throw new IOException("Privileged protocol mismatch; reinstall the app");
                        main.post(() -> finish(remote, actualUid));
                    } catch (Exception e) { main.post(() -> fail(this, "Privilege verification failed: " + message(e))); }
                }, "priv_files_identity").start();
            } catch (Exception e) { fail(this, "Cannot attach privileged file service: " + message(e)); }
        }

        private void finish(IPrivilegedFiles remote, int actualUid) {
            if (!current(this)) return;
            Activity activity = owner.get();
            if (activity == null || activity.isDestroyed() || activity.isFinishing() || !binder.isBinderAlive()) {
                fail(this, "Files closed or privileged service stopped during connection"); return;
            }
            uid = actualUid;
            (root ? rootAccess : shizukuAccess).attach(remote);
            main.removeCallbacks(timeout);
            Callback ready = callback; callback = null;
            if (ready != null) ready.connected(actualUid);
        }

        @Override public void onServiceDisconnected(ComponentName name) { main.post(() -> fail(this, "Privileged file service disconnected")); }
        @Override public void onBindingDied(ComponentName name) { main.post(() -> fail(this, "Privileged file service binding died")); }
        @Override public void onNullBinding(ComponentName name) { main.post(() -> fail(this, "Privileged file service refused the connection")); }
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Connect and disconnect on the main thread");
    }
    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
