package com.termux.app.files.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RoutingFileAccess implements FileAccess {
    private final Map<String, FileAccess> backends = new HashMap<>();
    public RoutingFileAccess register(String scheme, FileAccess backend) { backends.put(scheme, backend); return this; }
    public FileAccess backend(String scheme) { return backends.get(scheme); }
    private FileAccess at(FileLocation location) throws IOException {
        FileAccess backend = backends.get(URI.create(location.uri).getScheme());
        if (backend == null) throw new IOException("Storage connection is not available; reconnect it from Locations");
        return backend;
    }
    @Override public FileEntry stat(FileLocation location) throws IOException { return at(location).stat(location); }
    @Override public List<FileEntry> list(FileLocation directory) throws IOException { return at(directory).list(directory); }
    @Override public FileLocation parent(FileLocation location) throws IOException { return at(location).parent(location); }
    @Override public FileLocation child(FileLocation directory, String name) throws IOException { return at(directory).child(directory, name); }
    @Override public InputStream read(FileLocation location) throws IOException { return at(location).read(location); }
    @Override public OutputStream write(FileLocation location, boolean replace) throws IOException { return at(location).write(location, replace); }
    @Override public void mkdir(FileLocation location) throws IOException { at(location).mkdir(location); }
    @Override public FileLocation rename(FileLocation location, String name) throws IOException { return at(location).rename(location, name); }
    @Override public void delete(FileLocation location) throws IOException { at(location).delete(location); }
    @Override public void checkDelete(FileLocation location) throws IOException { at(location).checkDelete(location); }
    @Override public boolean exists(FileLocation location) throws IOException { return at(location).exists(location); }
    @Override public FileLocation createFile(FileLocation directory, String name) throws IOException { return at(directory).createFile(directory, name); }
    @Override public FileLocation createDirectory(FileLocation directory, String name) throws IOException { return at(directory).createDirectory(directory, name); }
    @Override public FileLocation createTemporary(FileLocation directory) throws IOException { return at(directory).createTemporary(directory); }
    @Override public boolean contains(FileLocation directory, FileLocation location) throws IOException {
        return at(directory) == at(location) && at(directory).contains(directory, location);
    }
    @Override public String readLink(FileLocation location) throws IOException { return at(location).readLink(location); }
    @Override public void symlink(String target, FileLocation location) throws IOException { at(location).symlink(target, location); }
    @Override public void setModified(FileLocation location, long time) throws IOException { at(location).setModified(location, time); }
    @Override public String permissions(FileLocation location) throws IOException { return at(location).permissions(location); }
    @Override public void chmod(FileLocation location, int mode) throws IOException { at(location).chmod(location, mode); }
}
