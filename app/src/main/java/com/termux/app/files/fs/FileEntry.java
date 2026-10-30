package com.termux.app.files.fs;

public final class FileEntry {
    public final FileLocation location;
    public final String name;
    public final boolean directory;
    public final boolean symbolicLink;
    public final long size;
    public final long modified;

    public FileEntry(FileLocation location, String name, boolean directory, boolean symbolicLink, long size, long modified) {
        this.location = location;
        this.name = name;
        this.directory = directory;
        this.symbolicLink = symbolicLink;
        this.size = size;
        this.modified = modified;
    }
    public String extension() {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
