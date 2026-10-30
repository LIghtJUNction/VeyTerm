package com.termux.app.files.fs;

import android.content.Context;

import com.termux.app.files.archive.ArchiveTools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Virtual archive directories use the same transfer engine as local, SAF and network files. */
public final class ArchiveFileAccess implements FileAccess {
    private final FileAccess fs;
    private final FileStaging staging;
    private final File cache;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, char[]> passwords = new LinkedHashMap<>();
    public ArchiveFileAccess(Context context, FileAccess fs) {
        this.fs = fs; staging = new FileStaging(context, fs); cache = new File(context.getCacheDir(), "archive-members");
    }
    public static FileLocation root(FileLocation archive) {
        return new FileLocation("archive:" + encode(archive.token()) + "!/");
    }
    public static FileLocation container(FileLocation location) throws IOException {
        int delimiter = location.uri.indexOf("!/");
        if (!location.uri.startsWith("archive:") || delimiter < 0) throw new IOException("Invalid archive location");
        return FileLocation.fromToken(decode(location.uri.substring(8, delimiter)));
    }
    private String member(FileLocation location) throws IOException {
        int delimiter = location.uri.indexOf("!/");
        if (delimiter < 0) throw new IOException("Invalid archive location");
        return decode(location.uri.substring(delimiter + 2));
    }
    private FileLocation at(FileLocation location, String member) throws IOException {
        return new FileLocation(root(container(location)).uri + encode(member));
    }
    public synchronized void password(FileLocation archive, char[] password) {
        String key = archive.token();
        char[] previous = passwords.remove(key);
        if (previous != null) Arrays.fill(previous, '\0');
        if (password != null && password.length > 0) passwords.put(key, password.clone());
        Session session = sessions.get(key);
        if (session != null) session.index = null;
    }
    private synchronized Session session(FileLocation location) throws IOException {
        FileLocation archive = container(location);
        String key = archive.token();
        Session session = sessions.get(key);
        if (session == null) {
            if (sessions.size() >= 32) throw new IOException("Too many open archives; close the file workspace before opening another");
            FileStaging.Stage stage = staging.openArchive(archive, FileOperations.NONE);
            session = new Session(archive, stage); sessions.put(key, session);
        }
        return session;
    }
    private final class Session {
        final FileLocation origin;
        final FileStaging.Stage stage;
        final Map<String, File> pending = new LinkedHashMap<>();
        Map<String, ArchiveTools.Entry> index;
        long length, modified;
        Session(FileLocation origin, FileStaging.Stage stage) { this.origin = origin; this.stage = stage; }
        ArchiveTools.Options options() {
            ArchiveTools.Options options = new ArchiveTools.Options();
            synchronized (ArchiveFileAccess.this) {
                char[] password = passwords.get(origin.token());
                options.password = password == null ? null : password.clone();
            }
            return options;
        }
        List<ArchiveTools.Entry> entries() throws IOException {
            if (index == null || length != stage.file.length() || modified != stage.file.lastModified()) {
                index = new LinkedHashMap<>();
                ArchiveTools.Options options = options();
                try {
                    for (ArchiveTools.Entry entry : ArchiveTools.list(stage.file, options, new ArchiveTools.Job())) index.put(strip(entry.name), entry);
                } finally { wipe(options); }
                length = stage.file.length(); modified = stage.file.lastModified();
            }
            return new ArrayList<>(index.values());
        }
        void changed() throws IOException {
            index = null;
            staging.commit(stage, FileOperations.NONE);
        }
        void writable() throws IOException {
            if (!ArchiveTools.detect(stage.file).writable) throw new IOException("This archive format is read-only");
        }
        void add(File file, String name) throws IOException {
            writable(); ArchiveTools.Options options = options();
            try { ArchiveTools.addReplace(stage.file, Collections.singletonList(new ArchiveTools.Source(file, name)), options, new ArchiveTools.Job()); changed(); }
            finally { wipe(options); }
        }
        void preserveParents(String name) throws IOException {
            entries();
            List<ArchiveTools.Source> parents = new ArrayList<>();
            int slash = name.lastIndexOf('/');
            File empty = null;
            while (slash > 0) {
                String parent = name.substring(0, slash);
                if (!index.containsKey(parent)) {
                    if (empty == null) empty = scratch(true);
                    parents.add(new ArchiveTools.Source(empty, parent + "/"));
                }
                slash = parent.lastIndexOf('/'); name = parent;
            }
            if (!parents.isEmpty()) {
                ArchiveTools.Options options = options();
                try { ArchiveTools.addReplace(stage.file, parents, options, new ArchiveTools.Job()); index = null; }
                finally { wipe(options); if (empty != null && !empty.delete()) empty.deleteOnExit(); }
            }
        }
    }
    @Override public FileEntry stat(FileLocation location) throws IOException {
        Session session = session(location);
        synchronized (session) {
            String name = member(location);
            if (name.isEmpty()) return new FileEntry(location, session.stage.file.getName(), true, false, 0, session.stage.file.lastModified());
            File pending = session.pending.get(name);
            if (pending != null) return new FileEntry(location, leaf(name), false, false, pending.length(), pending.lastModified());
            for (ArchiveTools.Entry entry : session.entries()) {
                if (strip(entry.name).equals(name)) return new FileEntry(location, leaf(name), entry.directory, false, entry.size, entry.modified);
                if (entry.name.startsWith(name + "/")) return new FileEntry(location, leaf(name), true, false, 0, entry.modified);
            }
            throw new FileNotFoundException("Archive entry no longer exists: " + name);
        }
    }
    @Override public List<FileEntry> list(FileLocation directory) throws IOException {
        Session session = session(directory);
        synchronized (session) {
            String path = member(directory), prefix = path.isEmpty() ? "" : path + "/";
            Map<String, FileEntry> children = new LinkedHashMap<>();
            for (ArchiveTools.Entry entry : session.entries()) {
                String name = strip(entry.name);
                if (!name.startsWith(prefix) || name.length() <= prefix.length()) continue;
                String rest = name.substring(prefix.length()); int slash = rest.indexOf('/');
                String child = slash < 0 ? rest : rest.substring(0, slash);
                boolean folder = slash >= 0 || entry.directory;
                FileLocation location = at(directory, prefix + child);
                FileEntry known = children.get(child);
                if (known == null || folder) children.put(child, new FileEntry(location, child, folder, false,
                    folder ? 0 : entry.size, entry.modified));
            }
            return new ArrayList<>(children.values());
        }
    }
    @Override public FileLocation parent(FileLocation location) throws IOException {
        String member = member(location);
        if (member.isEmpty()) return null;
        int slash = member.lastIndexOf('/'); return at(location, slash < 0 ? "" : member.substring(0, slash));
    }
    @Override public FileLocation child(FileLocation directory, String name) throws IOException {
        FileLocation.validateName(name); String member = member(directory);
        return at(directory, member.isEmpty() ? name : member + "/" + name);
    }
    @Override public InputStream read(FileLocation location) throws IOException {
        Session session = session(location);
        synchronized (session) {
            String name = member(location);
            File pending = session.pending.get(name);
            if (pending != null) return new FileInputStream(pending);
            if (stat(location).directory) throw new IOException("Choose a file inside the archive");
            File directory = scratch(true); ArchiveTools.Options options = session.options();
            try {
                ArchiveTools.extract(session.stage.file, directory, Collections.singleton(name), options, new ArchiveTools.Job());
                File file = new File(directory, name);
                return new FilterInputStream(new FileInputStream(file)) {
                    @Override public void close() throws IOException { try { super.close(); } finally { deletePrivate(directory); } }
                };
            } catch (IOException e) { try { deletePrivate(directory); } catch (IOException cleanup) { e.addSuppressed(cleanup); } throw e; }
            finally { wipe(options); }
        }
    }
    @Override public OutputStream write(FileLocation location, boolean replace) throws IOException {
        Session session = session(location);
        synchronized (session) {
            session.writable();
            String name = member(location); if (name.isEmpty()) throw new IOException("Cannot replace the archive root");
            File pending = session.pending.get(name);
            if (pending != null) return new FileOutputStream(pending);
            if (!replace && exists(location)) throw new IOException("Archive entry already exists");
            File output = scratch(false);
            return new FilterOutputStream(new FileOutputStream(output)) {
                boolean closed;
                @Override public void write(byte[] data, int offset, int length) throws IOException { out.write(data, offset, length); }
                @Override public void close() throws IOException {
                    if (closed) return; closed = true;
                    try { super.close(); synchronized (session) { session.add(output, name); } }
                    finally { if (output.exists() && !output.delete()) output.deleteOnExit(); }
                }
            };
        }
    }
    @Override public FileLocation createTemporary(FileLocation directory) throws IOException {
        Session session = session(directory);
        synchronized (session) {
            session.writable();
            FileLocation location = child(directory, ".veyterm-copy-" + UUID.randomUUID());
            session.pending.put(member(location), scratch(false)); return location;
        }
    }
    @Override public FileLocation createFile(FileLocation directory, String name) throws IOException {
        FileLocation target = child(directory, name);
        try (OutputStream ignored = write(target, false)) { return target; }
    }
    @Override public FileLocation createDirectory(FileLocation directory, String name) throws IOException {
        FileLocation target = child(directory, name); mkdir(target); return target;
    }
    @Override public void mkdir(FileLocation location) throws IOException {
        if (exists(location)) throw new IOException("Archive entry already exists");
        Session session = session(location);
        synchronized (session) {
            File empty = scratch(true);
            try { session.add(empty, member(location) + "/"); }
            finally { if (!empty.delete()) empty.deleteOnExit(); }
        }
    }
    @Override public FileLocation rename(FileLocation location, String name) throws IOException {
        FileLocation parent = parent(location);
        if (parent == null) throw new IOException("The archive root cannot be renamed");
        FileLocation target = child(parent, name);
        Session session = session(location);
        synchronized (session) {
            if (exists(target)) throw new IOException("Archive entry already exists: " + name);
            String original = member(location); File pending = session.pending.get(original);
            if (pending != null) {
                session.add(pending, member(target)); session.pending.remove(original);
                if (!pending.delete()) pending.deleteOnExit(); return target;
            }
            session.writable(); ArchiveTools.Options options = session.options();
            try { session.preserveParents(original); ArchiveTools.rename(session.stage.file, original, member(target), options, new ArchiveTools.Job()); session.changed(); }
            finally { wipe(options); }
            return target;
        }
    }
    @Override public void delete(FileLocation location) throws IOException {
        Session session = session(location);
        synchronized (session) {
            String name = member(location);
            if (name.isEmpty()) throw new IOException("The archive root cannot be deleted");
            File pending = session.pending.remove(name);
            if (pending != null) { if (!pending.delete()) throw new IOException("Could not remove staged archive data"); return; }
            session.writable(); ArchiveTools.Options options = session.options();
            try { session.preserveParents(name); ArchiveTools.delete(session.stage.file, Collections.singleton(name), options, new ArchiveTools.Job()); session.changed(); }
            finally { wipe(options); }
        }
    }
    @Override public boolean exists(FileLocation location) throws IOException {
        try { stat(location); return true; } catch (FileNotFoundException e) { return false; }
    }
    @Override public boolean contains(FileLocation directory, FileLocation location) throws IOException {
        FileLocation first = container(directory), second = container(location);
        if (!fs.contains(first, second) || !fs.contains(second, first)) return false;
        String base = member(directory), path = member(location);
        return base.isEmpty() || path.equals(base) || path.startsWith(base + "/");
    }
    private File scratch(boolean directory) throws IOException {
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot create archive workspace");
        File file = new File(cache, UUID.randomUUID().toString());
        if (directory ? !file.mkdir() : !file.createNewFile()) throw new IOException("Cannot create archive workspace item");
        return file;
    }
    private static void deletePrivate(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot inspect archive workspace");
            for (File child : children) deletePrivate(child);
        }
        if (!file.delete()) throw new IOException("Cannot clean archive workspace: " + file);
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("!", "%21"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private static String decode(String value) throws IOException {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid archive path", e); }
    }
    private static String leaf(String value) { return value.substring(value.lastIndexOf('/') + 1); }
    private static String strip(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private static void wipe(ArchiveTools.Options options) { if (options.password != null) Arrays.fill(options.password, '\0'); }
}
