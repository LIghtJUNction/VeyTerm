package com.termux.app.files.privileged;

import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;
import com.termux.app.files.fs.LocalFileAccess;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** Same implementation runs inside libsu's root process and Shizuku's UserService. */
class PrivilegedFileService extends IPrivilegedFiles.Stub {
    private final int clientUid;
    private final boolean root;
    private final LocalFileAccess local = new LocalFileAccess();
    private final Map<String, Listing> listings = new HashMap<>();
    private boolean closed;
    private Set<String> mountRoots;
    private long mountsChecked;

    PrivilegedFileService(int clientUid, boolean root) {
        this.clientUid = clientUid;
        this.root = root;
    }

    private int authorize() throws IOException {
        if (Binder.getCallingUid() != clientUid) throw new SecurityException("This Binder belongs to another application UID");
        if (closed) throw new IOException("Privileged file service is closed");
        int uid;
        try (FileReader status = new FileReader("/proc/self/status")) { uid = KernelIdentity.effectiveUid(status); }
        if (uid != Os.getuid() || uid != Os.geteuid()) throw new IOException("Kernel and process identities disagree");
        KernelIdentity.requirePrivilege(uid, root);
        return uid;
    }

    @Override public synchronized Bundle execute(String operation, Bundle args) {
        try {
            int uid = authorize();
            if (args == null) throw new IOException("Missing file arguments");
            expireListings();
            Bundle result = new Bundle();
            if ("identity".equals(operation)) {
                result.putInt("uid", uid);
                result.putInt("version", PrivilegedWire.VERSION);
                return result;
            }
            if ("list.next".equals(operation)) return page(args.getString("cursor"));
            if ("list.close".equals(operation)) { listings.remove(args.getString("cursor")); return result; }
            FileLocation location = FileLocation.local(new File(PrivilegedPaths.validate(args.getString("path"))));
            switch (operation) {
                case "stat": return PrivilegedWire.entry(local.stat(location));
                case "exists": result.putBoolean("value", local.exists(location)); break;
                case "list": return startListing(location);
                case "mkdir": local.mkdir(location); break;
                case "rename": {
                    PrivilegedPaths.name(args.getString("name"));
                    requireDeletable(location);
                    String parent = PrivilegedPaths.parent(location.file().getPath());
                    PrivilegedPaths.requireDeletable(PrivilegedPaths.child(parent, args.getString("name")));
                    result.putString("path", local.rename(location, args.getString("name")).file().getPath());
                    break;
                }
                case "delete": requireDeletable(location); local.delete(location); break;
                case "readlink": result.putString("value", local.readLink(location)); break;
                case "symlink": {
                    String target = args.getString("target");
                    if (target == null || target.isEmpty() || target.indexOf('\0') >= 0 || target.length() > 4096)
                        throw new IOException("Invalid symbolic-link target");
                    local.symlink(target, location); break;
                }
                case "permissions": result.putString("value", local.permissions(location)); break;
                case "chmod": chmod(location, args.getInt("mode", -1)); break;
                case "modified": {
                    // Do not silently change a symlink's target timestamps.
                    if (local.stat(location).symbolicLink)
                        throw new IOException("Changing symbolic-link timestamps is not supported");
                    long time = args.getLong("time", -1);
                    if (time < 0 || !location.file().setLastModified(time))
                        throw new IOException("Could not preserve modification time");
                    break;
                }
                case "contains": {
                    FileLocation other = FileLocation.local(new File(PrivilegedPaths.validate(args.getString("other"))));
                    result.putBoolean("value", local.contains(location, other)); break;
                }
                case "deletable": requireDeletable(location); break;
                default: throw new IOException("Unknown privileged file operation");
            }
            return result;
        } catch (IOException | RuntimeException e) { return PrivilegedWire.failure(e); }
    }

    private void requireDeletable(FileLocation location) throws IOException {
        String path = PrivilegedPaths.deletionPath(location.file().getPath(), local.stat(location).symbolicLink);
        long now = SystemClock.elapsedRealtime();
        if (mountRoots == null || now - mountsChecked > 1000) {
            try (FileReader mounts = new FileReader("/proc/self/mountinfo")) { mountRoots = MountRoots.read(mounts); }
            mountsChecked = now;
        }
        if (mountRoots.contains(path)) throw new IOException("A filesystem mount root cannot be deleted or renamed");
    }

    private Bundle startListing(FileLocation location) throws IOException {
        if (listings.size() >= 8) throw new IOException("Too many open directory listings");
        File[] children = location.file().listFiles();
        if (children == null) throw new IOException("Cannot list directory; check access and SELinux policy");
        if (children.length > 100000) throw new IOException("Directory exceeds 100000 entries; narrow the location");
        String cursor = UUID.randomUUID().toString();
        listings.put(cursor, new Listing(children));
        try { return page(cursor); }
        catch (IOException | RuntimeException e) { listings.remove(cursor); throw e; }
    }

    private Bundle page(String cursor) throws IOException {
        Listing listing = listings.get(cursor);
        if (listing == null) throw new IOException("Directory listing expired; refresh the directory");
        ArrayList<Bundle> entries = new ArrayList<>();
        // Bound scanned entries too, even if files disappear while listing.
        int end = Math.min(listing.offset + PrivilegedWire.PAGE_SIZE, listing.children.length);
        try {
            while (listing.offset < end) {
                File file = listing.children[listing.offset++];
                try { entries.add(PrivilegedWire.entry(local.stat(FileLocation.local(file)))); }
                catch (FileNotFoundException ignored) { /* Concurrent deletion is not a denied directory. */ }
            }
        } catch (IOException | RuntimeException e) { listings.remove(cursor); throw e; }
        Bundle result = new Bundle();
        result.putParcelableArrayList("entries", entries);
        if (listing.offset < listing.children.length) {
            listing.touched = SystemClock.elapsedRealtime();
            result.putString("cursor", cursor);
        } else listings.remove(cursor);
        return result;
    }

    private void expireListings() {
        long now = SystemClock.elapsedRealtime();
        Iterator<Listing> entries = listings.values().iterator();
        while (entries.hasNext()) if (now - entries.next().touched > 120000) entries.remove();
    }

    @Override public synchronized ParcelFileDescriptor openFile(String path, boolean write, boolean replace, Bundle result) {
        FileDescriptor fd = null;
        try {
            authorize();
            path = PrivilegedPaths.validate(path);
            int flags = OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK;
            // No truncation until fstat verifies the actual opened inode is a regular file.
            flags |= write ? OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_NOFOLLOW
                | (replace ? 0 : OsConstants.O_EXCL) : OsConstants.O_RDONLY;
            fd = Os.open(path, flags, 0600);
            StructStat stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode)) throw new IOException("Only regular files can be streamed");
            if (write && replace) Os.ftruncate(fd, 0);
            return ParcelFileDescriptor.dup(fd);
        } catch (ErrnoException e) {
            result.putAll(PrivilegedWire.failure(e.errno == OsConstants.ENOENT
                ? new FileNotFoundException(e.getMessage()) : new IOException(e.getMessage(), e)));
            return null;
        } catch (IOException | RuntimeException e) {
            result.putAll(PrivilegedWire.failure(e));
            return null;
        } finally {
            if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { }
        }
    }

    private void chmod(FileLocation location, int mode) throws IOException {
        if (mode < 0 || mode > 07777) throw new IOException("Invalid Unix permission mode");
        FileDescriptor fd = null;
        try {
            fd = Os.open(location.file().getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW
                | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            int type = Os.fstat(fd).st_mode;
            if (!OsConstants.S_ISREG(type) && !OsConstants.S_ISDIR(type))
                throw new IOException("Change permissions explicitly on a regular file or directory");
            Os.fchmod(fd, mode);
        } catch (ErrnoException e) { throw new IOException("Cannot change permissions: " + e.getMessage(), e); }
        finally { if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { } }
    }

    synchronized void closeService() { closed = true; listings.clear(); mountRoots = null; }

    @Override public void destroy() {
        // RootService lifecycle owns process shutdown. Shizuku overrides this hook.
        if (Binder.getCallingUid() != clientUid) throw new SecurityException("Only the client may close this service");
        closeService();
    }

    private static final class Listing {
        final File[] children;
        int offset;
        long touched = SystemClock.elapsedRealtime();
        Listing(File[] children) { this.children = children; }
    }
}
