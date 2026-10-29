package com.termux.app.files.privileged;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/** URI encoding is confined to the client boundary. Binder paths are literal filenames. */
final class PrivilegedPaths {
    private PrivilegedPaths() { }

    static String path(String scheme, String location) throws IOException {
        try {
            URI uri = new URI(location);
            if (!scheme.equals(uri.getScheme()) || uri.isOpaque() || uri.getRawAuthority() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IOException("Expected " + scheme + ":/absolute/path");
            return validate(uri.getPath());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Invalid privileged file location", e);
        }
    }

    static String validate(String path) throws IOException {
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0
            || path.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IOException("Use an absolute path of at most 4096 UTF-8 bytes without NUL");
        for (String part : path.split("/"))
            if (part.equals(".") || part.equals(".."))
                throw new IOException("Dot path components must be resolved explicitly");
        // Preserve symlinks: never canonicalize a path used to access a file.
        String value = path.replaceAll("/+", "/");
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    static String uri(String scheme, String path) throws IOException {
        if (!scheme.equals("root") && !scheme.equals("shizuku")) throw new IOException("Unknown privileged scheme");
        try { return new URI(scheme, null, validate(path), null).toASCIIString(); }
        catch (URISyntaxException e) { throw new IOException("Invalid privileged path", e); }
    }

    static void name(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
            || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("Use a single nonempty filename without / or NUL");
    }

    static String child(String directory, String name) throws IOException {
        name(name);
        return validate((directory.equals("/") ? "/" : directory + "/") + name);
    }

    static String parent(String path) throws IOException {
        path = validate(path);
        if (path.equals("/")) return null;
        int slash = path.lastIndexOf('/');
        return slash == 0 ? "/" : path.substring(0, slash);
    }

    static boolean protectedRoot(String path) throws IOException {
        path = validate(path);
        return path.equals("/") || path.equals("/sdcard") || path.equals("/mnt/sdcard")
            || path.equals("/storage") || path.equals("/storage/self")
            || path.equals("/storage/self/primary") || path.equals("/storage/emulated")
            || path.matches("/storage/emulated/[0-9]+") || path.matches("/storage/[^/]+")
            || path.equals("/mnt") || path.equals("/mnt/media_rw")
            || path.matches("/mnt/media_rw/[^/]+")
            || path.equals("/mnt/runtime") || path.matches("/mnt/runtime/[^/]+(?:/[^/]+){0,2}")
            || path.matches("/mnt/(?:user|installer|androidwritable|pass_through)/[0-9]+(?:/[^/]+(?:/[0-9]+)?)?")
            || path.equals("/data/media") || path.matches("/data/media/[0-9]+");
    }

    static void requireDeletable(String path) throws IOException {
        if (protectedRoot(path)) throw new IOException("A filesystem or storage root cannot be deleted or renamed");
    }

    static void requireDeletableResolved(String path, boolean symbolicLink) throws IOException {
        deletionPath(path, symbolicLink);
    }

    static String deletionPath(String path, boolean symbolicLink) throws IOException {
        requireDeletable(path);
        File file = new File(path);
        // A link itself is removable, but never walk into its target for deletion.
        String resolved = symbolicLink
            ? new File(file.getParentFile().getCanonicalFile(), file.getName()).getPath()
            : file.getCanonicalPath();
        requireDeletable(resolved);
        return resolved;
    }
}
