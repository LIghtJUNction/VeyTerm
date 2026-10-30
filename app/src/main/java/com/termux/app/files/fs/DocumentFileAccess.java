package com.termux.app.files.fs;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class DocumentFileAccess implements FileAccess {
    private static final String[] COLUMNS = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED };
    private final ContentResolver resolver;
    public DocumentFileAccess(Context context) { resolver = context.getApplicationContext().getContentResolver(); }
    public static FileLocation tree(Uri uri) {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
        return new FileLocation(document.toString(), uri.toString(), java.util.Collections.emptyList());
    }
    private FileEntry entry(FileLocation location, Cursor cursor) {
        return new FileEntry(location, cursor.getString(1),
            DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)), false,
            cursor.isNull(3) ? -1 : cursor.getLong(3), cursor.isNull(4) ? 0 : cursor.getLong(4));
    }
    @Override public FileEntry stat(FileLocation location) throws IOException {
        if (location.uri.startsWith("pending-document:")) throw new FileNotFoundException("Document has not been created");
        try (Cursor cursor = resolver.query(Uri.parse(location.uri), COLUMNS, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new FileNotFoundException("Document is no longer available");
            return entry(location, cursor);
        } catch (SecurityException | IllegalArgumentException e) { throw new IOException("Folder access expired; grant the folder again", e); }
    }
    @Override public List<FileEntry> list(FileLocation directory) throws IOException {
        Uri base = Uri.parse(directory.uri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(base, DocumentsContract.getDocumentId(base));
        try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("Storage provider did not return a directory listing");
            List<FileEntry> result = new ArrayList<>();
            while (cursor.moveToNext()) {
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(base, cursor.getString(0));
                result.add(entry(directory.descendant(uri.toString()), cursor));
            }
            return result;
        } catch (SecurityException | IllegalArgumentException e) { throw new IOException("Folder access expired; grant the folder again", e); }
    }
    @Override public FileLocation parent(FileLocation location) {
        return location.uri.startsWith("pending-document:") ? pendingParent(location) : location.recordedParent();
    }
    @Override public boolean contains(FileLocation directory, FileLocation location) {
        if (sameDocument(directory.uri, location.uri)) return true;
        for (String ancestor : location.ancestors) if (sameDocument(directory.uri, ancestor)) return true;
        return false;
    }
    private static boolean sameDocument(String first, String second) {
        if (first.startsWith("pending-document:") || second.startsWith("pending-document:")) return first.equals(second);
        Uri a = Uri.parse(first), b = Uri.parse(second);
        return java.util.Objects.equals(a.getAuthority(), b.getAuthority())
            && DocumentsContract.getDocumentId(a).equals(DocumentsContract.getDocumentId(b));
    }
    @Override public FileLocation child(FileLocation directory, String name) throws IOException {
        FileLocation.validateName(name);
        for (FileEntry entry : list(directory)) if (entry.name.equals(name)) return entry.location;
        return directory.descendant("pending-document:" + Uri.encode(directory.token()) + "/" + Uri.encode(name));
    }
    private FileLocation pendingParent(FileLocation location) {
        int slash = location.uri.lastIndexOf('/');
        return FileLocation.fromToken(Uri.decode(location.uri.substring("pending-document:".length(), slash)));
    }
    private FileLocation materialize(FileLocation location, String mime) throws IOException {
        if (!location.uri.startsWith("pending-document:")) return location;
        int slash = location.uri.lastIndexOf('/');
        FileLocation parent = pendingParent(location);
        String name = Uri.decode(location.uri.substring(slash + 1));
        try {
            Uri created = DocumentsContract.createDocument(resolver, Uri.parse(parent.uri), mime, name);
            if (created == null) throw new IOException("Storage provider could not create " + name);
            return parent.descendant(created.toString());
        } catch (SecurityException e) { throw new IOException("Storage provider refused to create this document", e); }
    }
    @Override public InputStream read(FileLocation location) throws IOException {
        InputStream stream = resolver.openInputStream(Uri.parse(location.uri));
        if (stream == null) throw new IOException("Storage provider did not open the document");
        return stream;
    }
    @Override public OutputStream write(FileLocation location, boolean replace) throws IOException {
        if (location.uri.startsWith("pending-document:")) throw new IOException("Create a document before opening it for writing");
        if (!replace) throw new IOException("Document already exists");
        OutputStream stream = resolver.openOutputStream(Uri.parse(location.uri), "wt");
        if (stream == null) throw new IOException("Storage provider did not open the document for writing");
        return stream;
    }
    @Override public void mkdir(FileLocation location) throws IOException {
        if (!location.uri.startsWith("pending-document:")) throw new IOException("Document already exists");
        materialize(location, DocumentsContract.Document.MIME_TYPE_DIR);
    }
    @Override public FileLocation createFile(FileLocation directory, String name) throws IOException {
        FileLocation target = child(directory, name);
        if (exists(target)) throw new IOException("Document already exists: " + name);
        return materialize(target, "application/octet-stream");
    }
    @Override public FileLocation createDirectory(FileLocation directory, String name) throws IOException {
        FileLocation target = child(directory, name);
        if (exists(target)) throw new IOException("Document already exists: " + name);
        return materialize(target, DocumentsContract.Document.MIME_TYPE_DIR);
    }
    @Override public FileLocation rename(FileLocation location, String name) throws IOException {
        FileLocation.validateName(name);
        try {
            Uri result = DocumentsContract.renameDocument(resolver, Uri.parse(location.uri), name);
            if (result == null) throw new IOException("Storage provider could not rename the document");
            return new FileLocation(result.toString(), location.treeUri, location.ancestors);
        } catch (SecurityException e) { throw new IOException("Storage provider refused the rename", e); }
    }
    @Override public void delete(FileLocation location) throws IOException {
        try {
            if (!DocumentsContract.deleteDocument(resolver, Uri.parse(location.uri))) throw new IOException("Storage provider refused the deletion");
        } catch (SecurityException e) { throw new IOException("Storage provider refused the deletion", e); }
    }
    @Override public boolean exists(FileLocation location) throws IOException {
        if (location.uri.startsWith("pending-document:")) return false;
        try { stat(location); return true; }
        catch (FileNotFoundException e) { return false; }
        catch (IOException failure) {
            // Providers may reject a removed opaque ID as outside its tree. Only a successful
            // parent listing can distinguish that stale child from a revoked folder grant.
            FileLocation parent = parent(location);
            if (!(failure.getCause() instanceof SecurityException) || parent == null) throw failure;
            for (FileEntry child : list(parent)) if (child.location.uri.equals(location.uri)) throw failure;
            return false;
        }
    }
}
