package com.termux.app.files.fs;

import android.content.Context;

import com.termux.app.files.archive.ArchiveTools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A private edit lease records the source identity so return from an editor cannot overwrite a changed document. */
public final class FileStaging {
    private static final int MAX_VOLUMES = 1024, MAX_LISTING = 100000;
    private static final long MAX_VOLUME_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 16L * 1024 * 1024 * 1024;
    public static final class Stage {
        public final File file;
        public FileLocation origin;
        public final boolean owned;
        private String originalDigest;
        private final List<String> volumes = new ArrayList<>();
        private boolean rarReadOnly, ownsPending, fileCreated;
        private String metadata;
        private Stage(File file, FileLocation origin, boolean owned, String digest) {
            this.file = file; this.origin = origin; this.owned = owned; this.originalDigest = digest;
        }
    }
    private final File root;
    private final FileAccess fs;
    public FileStaging(Context context, FileAccess fs) {
        this.root = new File(context.getCacheDir(), "file-workspace"); this.fs = fs;
    }
    public Stage open(FileLocation origin, FileOperations.Monitor monitor) throws IOException {
        if (origin.isLocal()) return new Stage(origin.file(), origin, false, "");
        FileEntry entry = fs.stat(origin);
        if (entry.directory) throw new IOException("Choose a file to edit");
        FileLocation.validateName(entry.name);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create the private editing workspace");
        File directory = new File(root, UUID.randomUUID().toString());
        if (!directory.mkdir()) throw new IOException("Cannot create a private editing lease");
        File data = new File(directory, "data");
        if (!data.mkdir()) { directory.delete(); throw new IOException("Cannot create the private editing data directory"); }
        File file = new File(data, entry.name);
        Stage stage = new Stage(file, origin, true, "");
        try {
            if (!file.createNewFile()) throw new IOException("Editing file already exists");
            stage.fileCreated = true;
            MessageDigest digest = sha256();
            long copied = 0;
            try (InputStream in = fs.read(origin); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024]; int length;
                while ((length = in.read(buffer)) != -1) {
                    monitor.checkCancelled(); out.write(buffer, 0, length); digest.update(buffer, 0, length);
                    copied += length; monitor.progress(entry.name, copied, 0);
                }
                out.getFD().sync();
            }
            FileEntry after = fs.stat(origin);
            if (after.size != entry.size || after.modified != entry.modified)
                throw new IOException("Source changed while opening it; try again");
            stage.originalDigest = hex(digest.digest());
            save(stage);
            return stage;
        } catch (IOException | RuntimeException failure) {
            try { discard(stage); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    /** Stage sibling volumes only for a content-detected RAR. The returned RAR lease is cache-only. */
    public Stage openArchive(FileLocation origin, FileOperations.Monitor monitor) throws IOException {
        // Bound the ordinary open's stream too, including providers with an unknown/false size.
        FileOperations.Monitor bounded = new FileOperations.Monitor() {
            long bytes;
            @Override public void checkCancelled() throws java.io.InterruptedIOException {
                monitor.checkCancelled();
                if (bytes > MAX_VOLUME_BYTES) throw new java.io.InterruptedIOException("Archive volume exceeds 4 GiB");
            }
            @Override public void progress(String name, long count, long files) {
                bytes = count; monitor.progress(name, count, files);
            }
        };
        Stage stage = open(origin, bounded);
        if (!stage.owned) return stage;
        try {
            bounded.checkCancelled();
            if (ArchiveTools.detect(stage.file) != ArchiveTools.Format.RAR) return stage;
            stage.rarReadOnly = true;
            save(stage);
            if (!stage.file.setReadOnly()) throw new IOException("Cannot protect cached RAR archive");
            VolumeNames names = VolumeNames.forFirst(stage.file.getName());
            if (names == null) return stage;
            FileEntry first = fs.stat(origin);
            regular(first);
            if (!first.name.equals(stage.file.getName()) || (first.size >= 0 && first.size != stage.file.length()))
                throw new IOException("Source changed while opening the archive");
            FileLocation parent = fs.parent(origin);
            if (parent == null) return stage; // An individually granted SAF document has no sibling access.
            TreeMap<Integer, FileEntry> selected = new TreeMap<>();
            selected.put(1, first);
            Set<FileLocation> locations = new HashSet<>();
            locations.add(origin);
            int scanned = 0;
            long declared = stage.file.length();
            for (FileEntry entry : fs.list(parent)) {
                monitor.checkCancelled();
                if (++scanned > MAX_LISTING) throw new IOException("Archive sibling listing exceeds 100000 entries");
                int index = names.index(entry.name);
                if (index == 0) continue;
                regular(entry);
                if (!parent.equals(fs.parent(entry.location))) throw new IOException("RAR volume is not a sibling");
                if (index == 1) {
                    if (!entry.location.equals(origin)) throw new IOException("Ambiguous first RAR volume");
                    unchanged(first, entry);
                    continue;
                }
                FileLocation.validateName(entry.name);
                if (index > MAX_VOLUMES || selected.put(index, entry) != null || !locations.add(entry.location))
                    throw new IOException("Too many or ambiguous RAR volumes");
                if (entry.size > MAX_VOLUME_BYTES) throw new IOException("RAR volume exceeds 4 GiB");
                if (entry.size > 0) declared += entry.size;
                if (declared > MAX_ARCHIVE_BYTES) throw new IOException("RAR volumes exceed 16 GiB");
            }
            int expected = 1;
            for (int index : selected.keySet())
                if (index != expected++) throw new IOException("Missing RAR volume before part " + index);
            long total = stage.file.length();
            java.util.Map<FileLocation, String> hashes = new java.util.HashMap<>();
            hashes.put(origin, stage.originalDigest);
            for (FileEntry entry : selected.values()) {
                monitor.checkCancelled();
                unchanged(entry, fs.stat(entry.location));
                if (entry.location.equals(origin)) continue;
                File target = new File(stage.file.getParentFile(), entry.name);
                if (!target.createNewFile()) throw new IOException("RAR volume cache file already exists: " + entry.name);
                // Persist only after exclusive creation, before reading any source bytes. On a crash,
                // even an incomplete copy is owned by this lease. No directory-wide deletion is used.
                stage.volumes.add(entry.name);
                save(stage);
                long count = 0;
                MessageDigest copiedHash = sha256();
                try (InputStream in = fs.read(entry.location); FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024]; int length;
                    while (true) {
                        monitor.checkCancelled();
                        length = in.read(buffer);
                        if (length == -1) break;
                        monitor.checkCancelled();
                        if (length > MAX_VOLUME_BYTES - count || length > MAX_ARCHIVE_BYTES - total)
                            throw new IOException("RAR volume cache size limit exceeded");
                        out.write(buffer, 0, length); copiedHash.update(buffer, 0, length); count += length; total += length;
                        monitor.progress(entry.name, total, stage.volumes.size() + 1);
                    }
                    out.getFD().sync();
                }
                if (entry.size >= 0 && count != entry.size) throw new IOException("RAR volume size changed: " + entry.name);
                hashes.put(entry.location, hex(copiedHash.digest()));
                unchanged(entry, fs.stat(entry.location));
                if (!target.setReadOnly()) throw new IOException("Cannot protect cached RAR volume");
            }
            // Re-read the bounded set: timestamps may be unavailable or have coarse precision.
            for (FileEntry entry : selected.values()) {
                monitor.checkCancelled(); unchanged(entry, fs.stat(entry.location));
                if (!hashes.get(entry.location).equals(digest(fs.read(entry.location), bounded, entry.name)))
                    throw new IOException("RAR volume changed while staging siblings: " + entry.name);
                bounded.checkCancelled();
                unchanged(entry, fs.stat(entry.location));
            }
            return stage;
        } catch (IOException | RuntimeException failure) {
            try { discard(stage); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static void regular(FileEntry entry) throws IOException {
        if (entry.directory || entry.symbolicLink) throw new IOException("RAR volumes must be regular non-symlink files: " + entry.name);
    }
    private static void unchanged(FileEntry before, FileEntry after) throws IOException {
        regular(after);
        if (!before.location.equals(after.location) || !before.name.equals(after.name) ||
            before.size != after.size || before.modified != after.modified)
            throw new IOException("RAR volume changed while staging: " + before.name);
    }
    /** Match the decoder's on-disk naming, without treating arbitrary .rar-looking files as volumes. */
    private static final class VolumeNames {
        final Pattern pattern;
        final String first;
        final boolean traditional;
        final int width;
        VolumeNames(String first, Pattern pattern, boolean traditional, int width) {
            this.first = first; this.pattern = pattern; this.traditional = traditional; this.width = width;
        }
        static VolumeNames forFirst(String name) {
            Matcher part = Pattern.compile("(?i)^(.*\\.part)(0*1)(\\.rar)$").matcher(name);
            if (part.matches()) return new VolumeNames(name,
                Pattern.compile(Pattern.quote(part.group(1)) + "([0-9]+)" + Pattern.quote(part.group(3))), false, part.group(2).length());
            if (name.matches("(?i).*\\.part[0-9]+\\.rar")) return null;
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".rar")) return new VolumeNames(name,
                Pattern.compile(Pattern.quote(name.substring(0, name.length() - 3)) + "([r-zR-Z])([0-9]{2})"), true, 2);
            if (name.endsWith(".001")) return new VolumeNames(name,
                Pattern.compile(Pattern.quote(name.substring(0, name.length() - 3)) + "([0-9]+)"), false, 3);
            return null;
        }
        int index(String name) throws IOException {
            if (first.equals(name)) return 1;
            Matcher match = pattern.matcher(name);
            if (!match.matches()) return 0;
            if (traditional) return (Character.toLowerCase(match.group(1).charAt(0)) - 'r') * 100 + Integer.parseInt(match.group(2)) + 2;
            String digits = match.group(1);
            if (digits.length() < width || (digits.length() > width && digits.charAt(0) == '0')) return 0;
            try { int value = Integer.parseInt(digits); return value > 0 ? value : 0; }
            catch (NumberFormatException e) { throw new IOException("RAR volume number is too large", e); }
        }
    }
    public Stage restore(File file) throws IOException {
        File directory = leaseDirectory(file);
        if (directory == null) return null;
        File metadata = new File(directory, ".origin.json");
        if (!present(metadata)) return null;
        return restore(file, readMetadata(metadata));
    }
    private Stage restore(File file, String text) throws IOException {
        try {
            JSONObject json = new JSONObject(text);
            for (String key : new String[]{"name", "origin", "sha256"})
                if (!(json.get(key) instanceof String)) throw new IOException("Invalid edit metadata field: " + key);
            String name = json.getString("name"); FileLocation.validateName(name);
            String hash = json.getString("sha256");
            if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid edit digest");
            FileLocation origin = FileLocation.fromToken(json.getString("origin"));
            if (origin.isLocal()) throw new IOException("A private lease must have a nonlocal origin");
            Stage stage = new Stage(new File(file.getParentFile(), name), origin, true, hash);
            stage.fileCreated = true;
            if (json.has("rarReadOnly")) {
                if (!(json.get("rarReadOnly") instanceof Boolean)) throw new IOException("Invalid RAR lease flag");
                stage.rarReadOnly = json.getBoolean("rarReadOnly");
            }
            if (json.has("volumes")) {
                JSONArray volumes = json.getJSONArray("volumes");
                if (!stage.rarReadOnly || volumes.length() >= MAX_VOLUMES) throw new IOException("Invalid RAR volume lease");
                VolumeNames names = VolumeNames.forFirst(name);
                Set<Integer> indices = new HashSet<>();
                for (int i = 0; i < volumes.length(); i++) {
                    if (!(volumes.get(i) instanceof String)) throw new IOException("Invalid RAR volume name");
                    String volume = volumes.getString(i); FileLocation.validateName(volume);
                    int index = names == null ? 0 : names.index(volume);
                    if (index < 2 || index > MAX_VOLUMES || !indices.add(index)) throw new IOException("Invalid RAR volume manifest");
                    stage.volumes.add(volume);
                }
            }
            stage.metadata = text;
            // Sibling cache files and unknown extraction outputs are never separate edit leases.
            return file.getName().equals(name) ? stage : null;
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("Edit lease is invalid", e); }
    }
    public boolean commit(Stage stage, FileOperations.Monitor monitor) throws IOException {
        if (!stage.owned) return false;
        if (stage.rarReadOnly) throw new IOException("Cached RAR archives and their volumes are read-only");
        File directory = leaseDirectory(stage.file);
        if (directory == null) throw new IOException("Invalid editing workspace");
        checkMetadata(stage, directory);
        String edited = digest(new FileInputStream(stage.file), monitor, stage.file.getName());
        if (edited.equals(stage.originalDigest)) return false;
        String current = digest(fs.read(stage.origin), monitor, stage.file.getName());
        if (!current.equals(stage.originalDigest))
            throw new IOException("The original changed outside this editor. Your edited copy is preserved at " + stage.file);
        FileLocation parent = fs.parent(stage.origin);
        if (parent == null) throw new IOException("This document does not have a writable parent");
        checkMetadata(stage, directory);
        if (present(new File(directory, ".origin.pending"))) throw new IOException("Unknown pending edit metadata retained");
        FileOperations.Result result = new FileOperations(fs).transfer(Collections.singletonList(FileLocation.local(stage.file)), parent, false,
            (source, target) -> FileOperations.Conflict.REPLACE, monitor);
        if (result.outputs.size() == 1) stage.origin = result.outputs.get(0);
        stage.originalDigest = edited;
        save(stage);
        return true;
    }
    public java.util.List<Stage> recoverable() throws IOException {
        java.util.List<Stage> stages = new java.util.ArrayList<>();
        if (!root.exists()) return stages;
        File[] leases = root.listFiles();
        if (leases == null) throw new IOException("Cannot inspect saved editing workspaces");
        for (File lease : leases) {
            try {
                File data = new File(lease, "data");
                // Inspect only the manifest's primary name, never each volume/output as a lease.
                if (leaseDirectory(new File(data, "placeholder")) == null) continue;
                File metadata = new File(lease, ".origin.json");
                if (!present(metadata)) continue;
                String text = readMetadata(metadata);
                String name = new JSONObject(text).getString("name"); FileLocation.validateName(name);
                Stage stage = restore(new File(data, name), text);
                if (stage != null && present(stage.file)) { regularCacheFile(stage.file); stages.add(stage); }
            } catch (IOException | JSONException | IllegalArgumentException invalidLease) {
                // Isolate unreadable, malformed or unsafe leases without changing any of their files.
                // Direct restore/discard still report the error; other healthy leases remain usable.
            }
        }
        return stages;
    }
    public void discard(Stage stage) throws IOException {
        if (!stage.owned) return;
        if (!stage.fileCreated) throw new IOException("Refusing to remove an unowned editing file");
        File directory = leaseDirectory(stage.file);
        if (directory == null)
            throw new IOException("Refusing to remove an unrelated file");
        checkMetadata(stage, directory);
        Set<String> owned = new HashSet<>(stage.volumes); owned.add(stage.file.getName());
        File[] files = stage.file.getParentFile().listFiles();
        if (files == null) throw new IOException("Cannot inspect editing files");
        for (File file : files) {
            if (!owned.contains(file.getName())) throw new IOException("Additional editing files retained: " + file);
            regularCacheFile(file);
        }
        File[] leaseFiles = directory.listFiles();
        if (leaseFiles == null) throw new IOException("Cannot inspect editing lease");
        for (File file : leaseFiles) {
            String name = file.getName();
            if (name.equals("data") || name.equals(".origin.json")) continue;
            if (name.equals(".origin.pending") && stage.ownsPending) { regularCacheFile(file); continue; }
            throw new IOException("Unknown editing lease file retained: " + file);
        }
        // Preflight above is deliberately before *any* deletion. Never recursively remove outputs.
        for (File file : files) remove(file);
        if (stage.ownsPending) remove(new File(directory, ".origin.pending"));
        remove(new File(directory, ".origin.json"));
        if (!stage.file.getParentFile().delete()) throw new IOException("Additional editing files retained: " + stage.file.getParentFile());
        if (!directory.delete()) throw new IOException("Editing workspace retained: " + directory);
    }
    private File leaseDirectory(File file) throws IOException {
        File data = file.getParentFile();
        File directory = data == null ? null : data.getParentFile();
        if (data == null || !data.getName().equals("data") || directory == null || directory.getParentFile() == null ||
            !directory.getParentFile().getAbsoluteFile().equals(root.getAbsoluteFile()) ||
            !directory.getName().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return null;
        File expected = new File(root.getCanonicalFile(), directory.getName());
        if (!directory.getCanonicalFile().equals(expected) || !data.getCanonicalFile().equals(new File(expected, "data")) ||
            !file.getCanonicalFile().equals(new File(new File(expected, "data"), file.getName())))
            throw new IOException("Refusing a symbolic-link editing workspace");
        if (!directory.isDirectory() || !data.isDirectory()) return null;
        return directory;
    }
    private void save(Stage stage) throws IOException {
        try {
            JSONObject json = new JSONObject().put("name", stage.file.getName()).put("origin", stage.origin.token()).put("sha256", stage.originalDigest);
            if (stage.rarReadOnly) {
                JSONArray volumes = new JSONArray();
                for (String name : stage.volumes) volumes.put(name);
                json.put("rarReadOnly", true).put("volumes", volumes);
            }
            File directory = leaseDirectory(stage.file);
            if (directory == null) throw new IOException("Invalid editing workspace");
            File metadata = new File(directory, ".origin.json");
            File pending = new File(directory, ".origin.pending");
            checkMetadata(stage, directory);
            if (!pending.createNewFile()) throw new IOException("Unknown pending edit metadata retained");
            stage.ownsPending = true;
            String text = json.toString();
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            checkMetadata(stage, directory);
            if (!pending.renameTo(metadata)) throw new IOException("Cannot save the edit lease");
            stage.ownsPending = false;
            stage.metadata = text;
        } catch (JSONException e) { throw new IOException("Cannot save the edit lease", e); }
    }
    private static boolean present(File file) throws IOException {
        String[] names = file.getParentFile().list();
        if (names == null) throw new IOException("Cannot inspect editing workspace");
        for (String name : names) if (name.equals(file.getName())) return true;
        return false; // Unlike File.exists(), this also notices dangling symbolic links.
    }
    private static void regularCacheFile(File file) throws IOException {
        if (!file.isFile() || !file.getCanonicalFile().equals(new File(file.getParentFile().getCanonicalFile(), file.getName())))
            throw new IOException("Refusing a nonregular or symbolic-link editing file: " + file);
    }
    private static String readMetadata(File metadata) throws IOException {
        regularCacheFile(metadata);
        if (metadata.length() > 1024 * 1024) throw new IOException("Invalid edit metadata");
        try (InputStream in = new FileInputStream(metadata)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > 1024 * 1024) throw new IOException("Invalid edit metadata");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static void checkMetadata(Stage stage, File directory) throws IOException {
        File metadata = new File(directory, ".origin.json");
        boolean exists = present(metadata);
        if (stage.metadata == null ? exists : !exists || !stage.metadata.equals(readMetadata(metadata)))
            throw new IOException("Edit metadata changed; workspace retained");
    }
    private static void remove(File file) throws IOException {
        if (present(file) && !file.delete()) throw new IOException("Editing workspace retained: " + file);
    }
    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private static String digest(InputStream input, FileOperations.Monitor monitor, String name) throws IOException {
        MessageDigest digest = sha256(); long count = 0;
        try (InputStream in = input) {
            byte[] buffer = new byte[64 * 1024]; int length;
            while ((length = in.read(buffer)) != -1) {
                monitor.checkCancelled(); digest.update(buffer, 0, length); count += length; monitor.progress(name, count, 0);
            }
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
}
