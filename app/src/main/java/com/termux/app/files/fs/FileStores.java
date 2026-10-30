package com.termux.app.files.fs;

import android.content.Context;

import com.termux.app.files.privileged.PrivilegedFiles;

public final class FileStores {
    private FileStores() { }
    public static RoutingFileAccess create(Context context) {
        java.io.File decoder = new java.io.File(context.getApplicationInfo().nativeLibraryDir, "libveyterm_unrar.so");
        try { com.termux.app.files.archive.RarExecutable.setExecutable(decoder.isFile() && decoder.canExecute() ? decoder : null); }
        catch (java.io.IOException error) { throw new IllegalStateException("The archive command is not executable", error); }
        RoutingFileAccess files = new RoutingFileAccess();
        DocumentFileAccess documents = new DocumentFileAccess(context);
        files.register("file", new LocalFileAccess()).register("content", documents).register("pending-document", documents);
        files.register("remote", new RemoteFileAccess(context));
        files.register("archive", new ArchiveFileAccess(context, files));
        PrivilegedFiles privileged = PrivilegedFiles.get(context);
        files.register("root", privileged.rootAccess()).register("shizuku", privileged.shizukuAccess());
        return files;
    }
}
