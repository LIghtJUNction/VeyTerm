package com.termux.app.files.privileged;

import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.termux.app.files.fs.FileAccess;
import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** No method here obtains permission, starts a process, or reconnects a dead service. */
final class PrivilegedFileAccess implements FileAccess {
    @Override public void checkDelete(FileLocation location) throws IOException { requireDeletable(location); }
    private final String scheme;
    private IPrivilegedFiles service;
    private final Set<Closeable> streams = new HashSet<>();

    PrivilegedFileAccess(String scheme) { this.scheme = scheme; }

    synchronized void attach(IPrivilegedFiles connected) { disconnect(); service = connected; }

    synchronized void disconnect() {
        service = null;
        for (Closeable stream : new ArrayList<>(streams)) {
            try { stream.close(); } catch (IOException ignored) { }
        }
        streams.clear();
    }

    private synchronized IPrivilegedFiles connected() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Run privileged file I/O on a worker thread");
        if (service == null || !service.asBinder().isBinderAlive())
            throw new IOException("Connect " + scheme + " explicitly before accessing these files");
        return service;
    }

    private Bundle call(String operation, Bundle args) throws IOException {
        return call(connected(), operation, args);
    }

    private static Bundle call(IPrivilegedFiles remote, String operation, Bundle args) throws IOException {
        try { return PrivilegedWire.checked(remote.execute(operation, args)); }
        catch (RemoteException | RuntimeException e) { throw new IOException("Privileged service unavailable; reconnect explicitly", e); }
    }

    private String path(FileLocation location) throws IOException { return PrivilegedPaths.path(scheme, location.uri); }
    private Bundle args(FileLocation location) throws IOException { return PrivilegedWire.arguments(path(location)); }
    private FileLocation location(String path) throws IOException { return new FileLocation(PrivilegedPaths.uri(scheme, path)); }

    @Override public FileEntry stat(FileLocation location) throws IOException {
        return PrivilegedWire.entry(location, call("stat", args(location)));
    }

    @SuppressWarnings("deprecation") // API-21-compatible Bundle getter; metadata is checked below.
    @Override public List<FileEntry> list(FileLocation directory) throws IOException {
        IPrivilegedFiles remote = connected();
        Bundle page = call(remote, "list", args(directory));
        String cursor = page.getString("cursor");
        try {
            List<FileEntry> result = new ArrayList<>();
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Directory listing cancelled");
                ArrayList<Bundle> entries = page.getParcelableArrayList("entries");
                if (entries == null) throw new IOException("Privileged service returned no directory entries");
                for (Bundle entry : entries) {
                    FileLocation child = child(directory, entry.getString("name"));
                    result.add(PrivilegedWire.entry(child, entry));
                }
                if (cursor == null) return result;
                Bundle args = new Bundle();
                args.putString("cursor", cursor);
                page = call(remote, "list.next", args);
                cursor = page.getString("cursor");
            }
        } finally {
            if (cursor != null) {
                Bundle args = new Bundle(); args.putString("cursor", cursor);
                try { call(remote, "list.close", args); } catch (IOException ignored) { }
            }
        }
    }

    @Override public FileLocation parent(FileLocation location) throws IOException {
        String parent = PrivilegedPaths.parent(path(location));
        return parent == null ? null : location(parent);
    }

    @Override public FileLocation child(FileLocation directory, String name) throws IOException {
        return directory.descendant(PrivilegedPaths.uri(scheme, PrivilegedPaths.child(path(directory), name)));
    }

    private ParcelFileDescriptor open(IPrivilegedFiles remote, FileLocation location, boolean write, boolean replace) throws IOException {
        try {
            Bundle result = new Bundle();
            ParcelFileDescriptor fd = remote.openFile(path(location), write, replace, result);
            try { PrivilegedWire.checked(result); }
            catch (IOException e) { if (fd != null) fd.close(); throw e; }
            if (fd == null) throw new IOException("Privileged service returned no file descriptor");
            return fd;
        } catch (RemoteException | RuntimeException e) { throw new IOException("Privileged file stream could not be opened", e); }
    }

    private synchronized <T extends Closeable> T track(T stream, IPrivilegedFiles remote) throws IOException {
        if (service != remote) {
            stream.close();
            throw new IOException("Connection closed while opening the file");
        }
        streams.add(stream);
        return stream;
    }

    @Override public InputStream read(FileLocation location) throws IOException {
        IPrivilegedFiles remote = connected();
        InputStream stream = new FilterInputStream(new ParcelFileDescriptor.AutoCloseInputStream(open(remote, location, false, false))) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { synchronized (PrivilegedFileAccess.this) { streams.remove(this); } }
            }
        };
        return track(stream, remote);
    }

    @Override public OutputStream write(FileLocation location, boolean replace) throws IOException {
        IPrivilegedFiles remote = connected();
        OutputStream stream = new FilterOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(open(remote, location, true, replace))) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
            @Override public void close() throws IOException {
                try { super.close(); } finally { synchronized (PrivilegedFileAccess.this) { streams.remove(this); } }
            }
        };
        return track(stream, remote);
    }

    @Override public void mkdir(FileLocation location) throws IOException { call("mkdir", args(location)); }

    @Override public FileLocation rename(FileLocation location, String name) throws IOException {
        PrivilegedPaths.name(name);
        PrivilegedPaths.requireDeletable(path(location));
        Bundle args = args(location); args.putString("name", name);
        return location(call("rename", args).getString("path"));
    }

    void requireDeletable(FileLocation location) throws IOException {
        PrivilegedPaths.requireDeletable(path(location));
        call("deletable", args(location));
    }

    @Override public void delete(FileLocation location) throws IOException {
        PrivilegedPaths.requireDeletable(path(location));
        call("delete", args(location));
    }
    @Override public boolean exists(FileLocation location) throws IOException { return call("exists", args(location)).getBoolean("value"); }
    @Override public boolean contains(FileLocation directory, FileLocation location) throws IOException {
        if (!scheme.equals(java.net.URI.create(location.uri).getScheme())) return false;
        Bundle args = args(directory); args.putString("other", path(location));
        return call("contains", args).getBoolean("value");
    }
    @Override public String readLink(FileLocation location) throws IOException { return call("readlink", args(location)).getString("value"); }
    @Override public void symlink(String target, FileLocation location) throws IOException {
        Bundle args = args(location); args.putString("target", target); call("symlink", args);
    }
    @Override public void setModified(FileLocation location, long time) throws IOException {
        Bundle args = args(location); args.putLong("time", time); call("modified", args);
    }
    @Override public String permissions(FileLocation location) throws IOException { return call("permissions", args(location)).getString("value"); }
    @Override public void chmod(FileLocation location, int mode) throws IOException {
        Bundle args = args(location); args.putInt("mode", mode); call("chmod", args);
    }
}
