package com.termux.app.files.fs;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LocalFileAccess implements FileAccess {
    @Override public FileEntry stat(FileLocation location) throws IOException {
        File file = location.file();
        try {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            return new FileEntry(location, file.getName().isEmpty() ? "/" : file.getName(),
                file.isDirectory(), OsConstants.S_ISLNK(stat.st_mode), stat.st_size,
                OsConstants.S_ISLNK(stat.st_mode) ? stat.st_mtime * 1000L : file.lastModified());
        } catch (ErrnoException e) { throw error(file, e); }
    }
    @Override public List<FileEntry> list(FileLocation location) throws IOException {
        File directory = location.file();
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot list " + directory + ": check storage permission or directory access");
        List<FileEntry> entries = new ArrayList<>(children.length);
        for (File child : children) {
            try { entries.add(stat(FileLocation.local(child))); }
            catch (FileNotFoundException ignored) { /* A concurrent deletion is not an unreadable directory. */ }
        }
        return entries;
    }
    @Override public FileLocation parent(FileLocation location) {
        File parent = location.file().getParentFile();
        return parent == null ? null : FileLocation.local(parent);
    }
    @Override public FileLocation child(FileLocation directory, String name) {
        FileLocation.validateName(name);
        return FileLocation.local(new File(directory.file(), name));
    }
    @Override public InputStream read(FileLocation location) throws IOException {
        return new ParcelFileDescriptor.AutoCloseInputStream(regularDescriptor(location, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, false));
    }
    @Override public OutputStream write(FileLocation location, boolean replace) throws IOException {
        int flags = OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_NONBLOCK | OsConstants.O_NOFOLLOW | (replace ? 0 : OsConstants.O_EXCL);
        return new ParcelFileDescriptor.AutoCloseOutputStream(regularDescriptor(location, flags, replace));
    }
    private ParcelFileDescriptor regularDescriptor(FileLocation location, int flags, boolean truncate) throws IOException {
        java.io.FileDescriptor descriptor = null;
        ParcelFileDescriptor owned = null;
        try {
            descriptor = Os.open(location.file().getAbsolutePath(), flags, 0600);
            if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) throw new IOException("Choose a regular file; devices, sockets and named pipes cannot be edited or copied");
            if (truncate) Os.ftruncate(descriptor, 0);
            owned = ParcelFileDescriptor.dup(descriptor);
            Os.close(descriptor); descriptor = null;
            return owned;
        } catch (ErrnoException | IOException failure) {
            IOException problem = failure instanceof ErrnoException ? error(location.file(), (ErrnoException) failure) : (IOException) failure;
            if (owned != null) try { owned.close(); } catch (IOException cleanup) { problem.addSuppressed(cleanup); }
            if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException cleanup) { problem.addSuppressed(cleanup); }
            throw problem;
        }
    }
    @Override public void mkdir(FileLocation location) throws IOException {
        if (!location.file().mkdir()) throw new IOException("Cannot create directory: " + location.file());
    }
    @Override public FileLocation rename(FileLocation location, String name) throws IOException {
        FileLocation parent = parent(location);
        if (parent == null) throw new IOException("The filesystem root cannot be renamed");
        FileLocation target = child(parent, name);
        if (exists(target)) throw new IOException("Target already exists: " + name);
        try { Os.rename(location.file().getAbsolutePath(), target.file().getAbsolutePath()); }
        catch (ErrnoException e) { throw error(location.file(), e); }
        return FileLocation.local(target.file());
    }
    @Override public void delete(FileLocation location) throws IOException {
        if (parent(location) == null) throw new IOException("The filesystem root cannot be deleted");
        if (!location.file().delete()) throw new IOException("Cannot delete: " + location.file());
    }
    @Override public boolean exists(FileLocation location) throws IOException {
        try { Os.lstat(location.file().getAbsolutePath()); return true; }
        catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) return false;
            throw error(location.file(), e);
        }
    }
    @Override public boolean contains(FileLocation directory, FileLocation location) throws IOException {
        if (!directory.isLocal() || !location.isLocal()) return false;
        String base = directory.file().getCanonicalPath();
        String path = location.file().getCanonicalPath();
        return path.equals(base) || path.startsWith(base.equals("/") ? "/" : base + "/");
    }
    @Override public String readLink(FileLocation location) throws IOException {
        try { return Os.readlink(location.file().getAbsolutePath()); }
        catch (ErrnoException e) { throw error(location.file(), e); }
    }
    @Override public void symlink(String target, FileLocation location) throws IOException {
        try { Os.symlink(target, location.file().getAbsolutePath()); }
        catch (ErrnoException e) { throw error(location.file(), e); }
    }
    @Override public void setModified(FileLocation location, long time) throws IOException {
        if (time > 0 && !location.file().setLastModified(time)) throw new IOException("Could not preserve modification time: " + location.file());
    }
    @Override public String permissions(FileLocation location) throws IOException {
        try {
            StructStat s = Os.lstat(location.file().getAbsolutePath());
            return String.format(Locale.ROOT, "%04o · uid %d · gid %d", s.st_mode & 07777, s.st_uid, s.st_gid);
        } catch (ErrnoException e) { throw error(location.file(), e); }
    }
    @Override public void chmod(FileLocation location, int mode) throws IOException {
        if (stat(location).symbolicLink) throw new IOException("Permissions of a symbolic-link target must be changed explicitly at its target");
        if (mode < 0 || mode > 07777) throw new IOException("Invalid Unix permission mode");
        try { Os.chmod(location.file().getAbsolutePath(), mode); }
        catch (ErrnoException e) { throw error(location.file(), e); }
    }
    private static IOException error(File file, ErrnoException e) {
        return e.errno == OsConstants.ENOENT ? new FileNotFoundException(file.toString()) : new IOException(file + ": " + e.getMessage(), e);
    }
}
