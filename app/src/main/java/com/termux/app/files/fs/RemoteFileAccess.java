package com.termux.app.files.fs;

import android.content.Context;

import com.termux.app.files.remote.ConnectionStore;
import com.termux.app.files.remote.RemoteFiles;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public final class RemoteFileAccess implements FileAccess {
    private final Context context;
    public RemoteFileAccess(Context context) { this.context = context.getApplicationContext(); }
    public static FileLocation location(String connection, String path) {
        if (connection == null || !connection.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid saved connection");
        try { return new FileLocation(new URI("remote", connection, path, null).toASCIIString()); }
        catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid remote path", e); }
    }
    private RemoteFiles connect(FileLocation location) throws IOException {
        String id = URI.create(location.uri).getAuthority();
        return RemoteFiles.connect(new ConnectionStore(context).load(id), new RemoteFiles.Cancellation());
    }
    private String path(FileLocation location) { return URI.create(location.uri).getPath(); }
    private FileLocation at(FileLocation base, String path) { return location(URI.create(base.uri).getAuthority(), path); }
    private FileEntry entry(FileLocation base, RemoteFiles.Entry e) {
        return new FileEntry(at(base, e.path), e.name, e.directory, e.symbolicLink, e.size, e.modified);
    }
    @Override public FileEntry stat(FileLocation location) throws IOException {
        try (RemoteFiles remote = connect(location)) { return entry(location, remote.stat(path(location))); }
    }
    @Override public List<FileEntry> list(FileLocation directory) throws IOException {
        try (RemoteFiles remote = connect(directory)) {
            List<FileEntry> result = new ArrayList<>();
            for (RemoteFiles.Entry entry : remote.list(path(directory))) result.add(entry(directory, entry));
            return result;
        }
    }
    @Override public FileLocation parent(FileLocation location) {
        return "/".equals(path(location)) ? null : at(location, RemoteFiles.parent(path(location)));
    }
    @Override public FileLocation child(FileLocation directory, String name) {
        FileLocation.validateName(name);
        return at(directory, RemoteFiles.child(path(directory), name));
    }
    @Override public InputStream read(FileLocation location) throws IOException {
        RemoteFiles remote = connect(location);
        try {
            return new FilterInputStream(remote.read(path(location))) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { remote.close(); }
                }
            };
        } catch (IOException e) { remote.close(); throw e; }
    }
    @Override public OutputStream write(FileLocation location, boolean replace) throws IOException {
        RemoteFiles remote = connect(location);
        try {
            return new FilterOutputStream(remote.write(path(location), replace)) {
                @Override public void write(byte[] data, int off, int length) throws IOException { out.write(data, off, length); }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { remote.close(); }
                }
            };
        } catch (IOException e) { remote.close(); throw e; }
    }
    @Override public void mkdir(FileLocation location) throws IOException {
        try (RemoteFiles remote = connect(location)) { remote.mkdir(path(location)); }
    }
    @Override public FileLocation rename(FileLocation location, String name) throws IOException {
        FileLocation parent = parent(location);
        if (parent == null) throw new IOException("The remote root cannot be renamed");
        FileLocation target = child(parent, name);
        try (RemoteFiles remote = connect(location)) { remote.rename(path(location), path(target)); }
        return target;
    }
    @Override public void delete(FileLocation location) throws IOException {
        if (parent(location) == null) throw new IOException("The remote root cannot be deleted");
        try (RemoteFiles remote = connect(location)) { remote.delete(path(location)); }
    }
    @Override public boolean exists(FileLocation location) throws IOException {
        FileLocation parent = parent(location);
        if (parent == null) { stat(location); return true; }
        for (FileEntry entry : list(parent)) if (entry.location.equals(location)) return true;
        return false;
    }
    @Override public String permissions(FileLocation location) throws IOException {
        try (RemoteFiles remote = connect(location)) { return remote.permissions(path(location)); }
    }
    @Override public void chmod(FileLocation location, int mode) throws IOException {
        try (RemoteFiles remote = connect(location)) { remote.chmod(path(location), mode); }
    }
    @Override public String readLink(FileLocation location) throws IOException {
        try (RemoteFiles remote = connect(location)) { return remote.readLink(path(location)); }
    }
    @Override public void symlink(String target, FileLocation location) throws IOException {
        try (RemoteFiles remote = connect(location)) { remote.symlink(target, path(location)); }
    }
    @Override public void setModified(FileLocation location, long time) throws IOException {
        try (RemoteFiles remote = connect(location)) { remote.setModified(path(location), time); }
    }
    @Override public boolean contains(FileLocation directory, FileLocation location) throws IOException {
        URI base = URI.create(directory.uri), other = URI.create(location.uri);
        if (!java.util.Objects.equals(base.getAuthority(), other.getAuthority())) {
            ConnectionStore store = new ConnectionStore(context);
            return ConnectionStore.contains(store.load(base.getAuthority()), base.getPath(), store.load(other.getAuthority()), other.getPath());
        }
        String root = base.getPath(), path = other.getPath();
        return path.equals(root) || path.startsWith(root.equals("/") ? root : root + "/");
    }
}
