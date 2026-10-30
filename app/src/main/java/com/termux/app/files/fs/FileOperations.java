package com.termux.app.files.fs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

public final class FileOperations {
    public enum Conflict { REPLACE, KEEP_BOTH, SKIP, CANCEL }
    public interface ConflictResolver { Conflict resolve(FileEntry source, FileEntry target) throws IOException; }
    public interface Monitor {
        void checkCancelled() throws InterruptedIOException;
        void progress(String name, long bytes, long files);
    }
    public static final Monitor NONE = new Monitor() {
        @Override public void checkCancelled() { }
        @Override public void progress(String name, long bytes, long files) { }
    };
    public static final class Result {
        public long bytes;
        public long files;
        public int skipped;
        public final List<FileLocation> outputs = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }
    private final FileAccess fs;
    public FileOperations(FileAccess fs) { this.fs = fs; }

    public Result transfer(List<FileLocation> sources, FileLocation destination, boolean move,
                           ConflictResolver conflicts, Monitor monitor) throws IOException {
        if (!fs.stat(destination).directory || fs.stat(destination).symbolicLink)
            throw new IOException("Choose a real destination directory");
        for (FileLocation source : sources) {
            FileEntry entry = fs.stat(source);
            if (move) fs.checkDelete(source);
            if (entry.directory && !entry.symbolicLink && fs.contains(source, destination))
                throw new IOException("A directory cannot be copied into itself or one of its descendants");
        }
        Result result = new Result();
        for (FileLocation source : sources) {
            monitor.checkCancelled();
            FileEntry entry = fs.stat(source);
            List<FileEntry> copied = new ArrayList<>();
            boolean complete = copy(entry, destination, conflicts, monitor, result, copied, 0);
            if (move && complete) removeCopied(copied, monitor);
        }
        return result;
    }

    private boolean copy(FileEntry entry, FileLocation directory, ConflictResolver conflicts,
                         Monitor monitor, Result result, List<FileEntry> copied, int depth) throws IOException {
        if (depth > 512) throw new IOException("Directory nesting exceeds the supported depth");
        monitor.checkCancelled();
        String targetName = entry.name;
        FileLocation target = fs.child(directory, targetName);
        boolean targetExists = fs.exists(target);
        if (targetExists) {
            FileLocation sourceParent = fs.parent(entry.location);
            boolean sameDirectory = sourceParent != null && fs.contains(sourceParent, directory)
                && fs.contains(directory, sourceParent);
            boolean sameTarget = target.equals(entry.location) || sameDirectory;
            FileEntry existing = fs.stat(target);
            Conflict choice = conflicts.resolve(entry, existing);
            if (choice == Conflict.CANCEL) throw new InterruptedIOException("Operation cancelled");
            if (choice == Conflict.SKIP) { result.skipped++; return false; }
            if (choice == Conflict.KEEP_BOTH) {
                targetName = unusedLeafName(directory, entry.name);
                target = fs.child(directory, targetName);
                targetExists = false;
            } else if (sameTarget) {
                throw new IOException("Source and destination are the same file");
            } else if (entry.directory != existing.directory || existing.symbolicLink) {
                throw new IOException("Replacing a directory, or replacing a symbolic link, requires choosing a new name");
            }
        }
        if (entry.symbolicLink) {
            if (targetExists) throw new IOException("Choose Keep both when copying a symbolic link over an existing file");
            fs.symlink(fs.readLink(entry.location), target);
            result.files++;
            result.outputs.add(target);
            copied.add(entry);
            monitor.progress(entry.name, result.bytes, result.files);
            return true;
        }
        if (entry.directory) {
            if (!targetExists) target = fs.createDirectory(directory, targetName);
            boolean complete = true;
            for (FileEntry child : fs.list(entry.location))
                complete &= copy(child, target, conflicts, monitor, result, copied, depth + 1);
            result.outputs.add(target);
            result.files++;
            copied.add(entry);
            monitor.progress(entry.name, result.bytes, result.files);
            return complete;
        }
        FileLocation temporary = fs.createTemporary(directory);
        boolean committed = false;
        Throwable primaryFailure = null;
        try {
            try (InputStream in = new BufferedInputStream(fs.read(entry.location));
                 OutputStream out = new BufferedOutputStream(fs.write(temporary, true))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    monitor.checkCancelled();
                    out.write(buffer, 0, count);
                    result.bytes += count;
                    monitor.progress(entry.name, result.bytes, result.files);
                }
            }
            monitor.checkCancelled();
            verifyUnchanged(entry);
            String name = targetExists ? fs.stat(target).name : targetName;
            FileLocation output = publish(temporary, directory, name, targetExists, result.warnings, entry);
            committed = true;
            try { fs.setModified(output, entry.modified); }
            catch (IOException e) { result.warnings.add(e.getMessage()); }
            result.files++;
            result.outputs.add(output);
            copied.add(entry);
            monitor.progress(entry.name, result.bytes, result.files);
            return true;
        } catch (IOException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!committed) {
                try { if (fs.exists(temporary)) fs.delete(temporary); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (primaryFailure == null) throw cleanup;
                    primaryFailure.addSuppressed(cleanup);
                }
            }
        }
    }

    private void verifyUnchanged(FileEntry before) throws IOException {
        FileEntry now = fs.stat(before.location);
        if (now.directory != before.directory || now.symbolicLink != before.symbolicLink ||
            (!before.directory && before.size >= 0 && now.size != before.size) ||
            (!before.directory && before.modified > 0 && now.modified != before.modified))
            throw new IOException("Source changed during the operation; source was preserved: " + before.name);
    }

    private void removeCopied(List<FileEntry> copied, Monitor monitor) throws IOException {
        for (FileEntry entry : copied) { monitor.checkCancelled(); fs.checkDelete(entry.location); verifyUnchanged(entry); }
        for (FileEntry entry : copied) {
            monitor.checkCancelled();
            verifyUnchanged(entry);
            if (entry.directory && !entry.symbolicLink && !fs.list(entry.location).isEmpty())
                throw new IOException("New files appeared in the source directory; they were preserved: " + entry.name);
            fs.delete(entry.location);
        }
    }

    public FileLocation unusedName(FileLocation directory, String name) throws IOException {
        return fs.child(directory, unusedLeafName(directory, name));
    }

    private String unusedLeafName(FileLocation directory, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 100000; i++) {
            String candidateName = stem + " (" + i + ")" + suffix;
            FileLocation candidate = fs.child(directory, candidateName);
            if (!fs.exists(candidate)) return candidateName;
        }
        throw new IOException("Too many files with the same base name");
    }

    private FileLocation publish(FileLocation temporary, FileLocation directory, String name,
                                boolean replace, List<String> warnings, FileEntry source) throws IOException {
        FileLocation target = fs.child(directory, name);
        FileLocation previous = null;
        if (fs.exists(target)) {
            if (!replace) throw new IOException("The target appeared while copying; original was preserved: " + name);
            if (fs.stat(target).directory || fs.stat(target).symbolicLink) throw new IOException("The target changed type while copying");
            previous = fs.rename(target, ".veyterm-replaced-" + UUID.randomUUID());
        }
        FileLocation published;
        try {
            // A second view of the same server file may have a different URI. Moving its
            // destination aside must not remove or change our source before publication.
            if (previous != null) verifyUnchanged(source);
            published = fs.rename(temporary, name);
        }
        catch (IOException | RuntimeException | Error failure) {
            if (previous != null) {
                try { fs.rename(previous, name); }
                catch (IOException | RuntimeException | Error rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
        if (previous != null) {
            try { fs.delete(previous); }
            catch (IOException e) { warnings.add("Copied successfully; previous contents retained at " + previous.uri + ": " + e.getMessage()); }
        }
        return published;
    }

    public Result remove(List<FileLocation> locations, Monitor monitor) throws IOException {
        for (FileLocation location : locations) fs.checkDelete(location);
        Result result = new Result();
        for (FileLocation location : locations) remove(location, monitor, result, 0);
        return result;
    }
    private void remove(FileLocation location, Monitor monitor, Result result, int depth) throws IOException {
        fs.checkDelete(location);
        if (depth > 512) throw new IOException("Directory nesting exceeds the supported depth");
        monitor.checkCancelled();
        if (fs.parent(location) == null) throw new IOException("A storage root cannot be deleted");
        FileEntry entry = fs.stat(location);
        if (entry.directory && !entry.symbolicLink)
            for (FileEntry child : fs.list(location)) remove(child.location, monitor, result, depth + 1);
        monitor.checkCancelled();
        fs.delete(location);
        result.files++;
        monitor.progress(entry.name, result.bytes, result.files);
    }

    public Map<FileLocation, String> renamePlan(List<FileLocation> locations, String regex,
                                               String replacement, int firstNumber) throws IOException {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        Map<FileLocation, String> plan = new LinkedHashMap<>();
        Set<String> targets = new HashSet<>();
        Set<FileLocation> originals = new HashSet<>(locations);
        int number = firstNumber;
        for (FileLocation location : locations) {
            String name = pattern.matcher(fs.stat(location).name).replaceAll(replacement.replace("{n}", Integer.toString(number++)));
            FileLocation.validateName(name);
            FileLocation parent = fs.parent(location);
            if (parent == null) throw new IOException("A storage root cannot be renamed");
            FileLocation target = fs.child(parent, name);
            if (!targets.add(parent.uri + "\0" + name)) throw new IOException("Two files would have the same name: " + name);
            if (fs.exists(target) && !originals.contains(target)) throw new IOException("An unselected file already uses this name: " + name);
            plan.put(location, name);
        }
        return plan;
    }

    public void batchRename(Map<FileLocation, String> plan, Monitor monitor) throws IOException {
        Map<FileLocation, String> originalNames = new LinkedHashMap<>();
        for (Map.Entry<FileLocation, String> item : plan.entrySet()) {
            fs.checkDelete(item.getKey()); FileLocation.validateName(item.getValue());
            originalNames.put(item.getKey(), fs.stat(item.getKey()).name);
        }
        Map<FileLocation, FileLocation> current = new LinkedHashMap<>();
        try {
            for (FileLocation original : plan.keySet()) {
                monitor.checkCancelled();
                current.put(original, fs.rename(original, ".veyterm-rename-" + UUID.randomUUID()));
            }
            for (Map.Entry<FileLocation, String> item : plan.entrySet()) {
                monitor.checkCancelled();
                FileLocation renamed = fs.rename(current.get(item.getKey()), item.getValue());
                current.put(item.getKey(), renamed);
                monitor.progress(item.getValue(), 0, 0);
            }
        } catch (IOException | RuntimeException failure) {
            Map<FileLocation, FileLocation> rollback = new LinkedHashMap<>();
            for (Map.Entry<FileLocation, FileLocation> item : current.entrySet()) {
                try { rollback.put(item.getKey(), fs.rename(item.getValue(), ".veyterm-rollback-" + UUID.randomUUID())); }
                catch (IOException e) { failure.addSuppressed(e); }
            }
            for (Map.Entry<FileLocation, FileLocation> item : rollback.entrySet()) {
                try { fs.rename(item.getValue(), originalNames.get(item.getKey())); }
                catch (IOException e) { failure.addSuppressed(e); }
            }
            throw failure;
        }
    }

    public String checksum(FileLocation file, String algorithm, Monitor monitor) throws IOException {
        try {
            MessageDigest digest = algorithm.equals("CRC32") ? null : MessageDigest.getInstance(algorithm);
            CRC32 crc = digest == null ? new CRC32() : null;
            String name = fs.stat(file).name;
            long total = 0;
            try (InputStream in = fs.read(file)) {
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = in.read(buffer)) != -1) {
                    monitor.checkCancelled();
                    if (digest != null) digest.update(buffer, 0, length); else crc.update(buffer, 0, length);
                    total += length;
                    monitor.progress(name, total, 0);
                }
            }
            if (crc != null) return String.format(Locale.ROOT, "%08x", crc.getValue());
            StringBuilder value = new StringBuilder();
            for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException("Checksum algorithm is unavailable", e); }
    }
}
