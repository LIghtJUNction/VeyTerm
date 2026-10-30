package com.termux.app.files.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public interface FileAccess {
    FileEntry stat(FileLocation location) throws IOException;
    List<FileEntry> list(FileLocation directory) throws IOException;
    FileLocation parent(FileLocation location) throws IOException;
    FileLocation child(FileLocation directory, String name) throws IOException;
    InputStream read(FileLocation location) throws IOException;
    OutputStream write(FileLocation location, boolean replace) throws IOException;
    void mkdir(FileLocation location) throws IOException;
    FileLocation rename(FileLocation location, String name) throws IOException;
    void delete(FileLocation location) throws IOException;
    default void checkDelete(FileLocation location) throws IOException { }
    boolean exists(FileLocation location) throws IOException;
    default FileLocation createFile(FileLocation directory, String name) throws IOException {
        FileLocation location = child(directory, name);
        try (OutputStream ignored = write(location, false)) { return location; }
    }
    default FileLocation createDirectory(FileLocation directory, String name) throws IOException {
        FileLocation location = child(directory, name);
        mkdir(location);
        return location;
    }
    default FileLocation createTemporary(FileLocation directory) throws IOException {
        return createFile(directory, ".veyterm-copy-" + java.util.UUID.randomUUID());
    }
    default boolean contains(FileLocation directory, FileLocation location) throws IOException {
        return directory.equals(location) || location.ancestors.contains(directory.uri);
    }
    default String readLink(FileLocation location) throws IOException { throw new IOException("Symbolic links are not supported by this storage"); }
    default void symlink(String target, FileLocation location) throws IOException { throw new IOException("Symbolic links are not supported by this storage"); }
    default void setModified(FileLocation location, long time) throws IOException { throw new IOException("This storage cannot preserve modification times"); }
    default String permissions(FileLocation location) throws IOException { return "This storage does not expose Unix permissions"; }
    default void chmod(FileLocation location, int mode) throws IOException { throw new IOException("This storage does not support Unix permissions"); }
}
