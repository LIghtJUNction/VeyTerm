package com.termux.app.files.fs;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class FileLocation {
    public final String uri;
    public final String treeUri;
    public final List<String> ancestors;

    public FileLocation(String uri) { this(uri, "", Collections.emptyList()); }

    public FileLocation(String uri, String treeUri, List<String> ancestors) {
        URI parsed = URI.create(uri);
        if (parsed.getScheme() == null || uri.contains("\n") || uri.contains("\r"))
            throw new IllegalArgumentException("A file location must be an absolute URI");
        this.uri = parsed.normalize().toString();
        this.treeUri = treeUri == null ? "" : treeUri;
        this.ancestors = Collections.unmodifiableList(new ArrayList<>(ancestors));
    }

    public static FileLocation local(File file) { return new FileLocation(file.getAbsoluteFile().toURI().toString()); }
    public boolean isLocal() { return "file".equals(URI.create(uri).getScheme()); }
    public File file() {
        if (!isLocal()) throw new IllegalStateException("This location is not a local file");
        return new File(URI.create(uri));
    }
    public FileLocation descendant(String childUri) {
        List<String> parents = new ArrayList<>(ancestors);
        parents.add(uri);
        return new FileLocation(childUri, treeUri, parents);
    }
    public FileLocation recordedParent() {
        if (ancestors.isEmpty()) return null;
        return new FileLocation(ancestors.get(ancestors.size() - 1), treeUri,
            ancestors.subList(0, ancestors.size() - 1));
    }
    public String token() {
        StringBuilder value = new StringBuilder(uri).append('\n').append(treeUri);
        for (String parent : ancestors) value.append('\n').append(parent);
        return value.toString();
    }
    public static FileLocation fromToken(String token) {
        String[] values = token.split("\n", -1);
        List<String> parents = new ArrayList<>();
        for (int i = 2; i < values.length; i++) parents.add(values[i]);
        return new FileLocation(values[0], values.length > 1 ? values[1] : "", parents);
    }
    public static void validateName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
            name.indexOf('/') >= 0 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Use a single, nonempty file name without / or NUL");
    }
    @Override public boolean equals(Object other) {
        return other instanceof FileLocation && uri.equals(((FileLocation) other).uri);
    }
    @Override public int hashCode() { return Objects.hash(uri); }
    @Override public String toString() { return uri; }
}
