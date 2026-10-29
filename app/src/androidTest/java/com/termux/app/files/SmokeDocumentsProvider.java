package com.termux.app.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

/** Real Binder/PFD provider. No host-side Android stubs and no target-private paths. */
public final class SmokeDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.veyterm.fileworkspacesmoke.test.documents.20261030";
    public static final Uri CONTROL = Uri.parse("content://" + AUTHORITY + ".control");
    private static final String TARGET = "com.veyterm";
    // Deliberately stable provider metadata: edit conflicts must compare bytes, not just size/time.
    private static final long FIXTURE_MODIFIED = 1700000000000L;
    private final Map<String, String> versions = new HashMap<>();
    private static final int GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION
        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS
    };

    @Override public boolean onCreate() { return true; }

    private static File fixtures(Context context) { return new File(context.getCacheDir(), "file-workspace-smoke-provider"); }

    private static String session(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("A canonical smoke session UUID is required");
        return value;
    }

    private synchronized File file(String id) throws FileNotFoundException {
        try {
            String[] parts = id.split("/", -1);
            session(parts[0]);
            for (String part : parts) name(part);
            File base = fixtures(getContext()).getCanonicalFile();
            if (parts.length != 1 && parts.length < 3) throw new IOException("Invalid path ID");
            String relative = parts[0];
            for (int i = 2; i < parts.length; i++) relative += "/" + parts[i];
            File candidate = new File(base, relative).getCanonicalFile();
            if (!candidate.getPath().startsWith(base.getPath() + File.separator) || !candidate.exists())
                throw new IOException("Missing document");
            if (parts.length > 1 && !parts[1].equals(versions.get(candidate.getPath())))
                throw new IOException("Document ID was replaced or renamed");
            return candidate;
        } catch (IOException | IllegalArgumentException e) { throw missing(e); }
    }

    private synchronized String id(File file) throws FileNotFoundException {
        try {
            String base = fixtures(getContext()).getCanonicalPath() + File.separator;
            String path = file.getCanonicalPath();
            if (!path.startsWith(base)) throw new IOException("Outside fixture");
            String relative = path.substring(base.length());
            int slash = relative.indexOf('/');
            if (slash < 0) return relative;
            String version = versions.get(path);
            if (version == null) { version = UUID.randomUUID().toString(); versions.put(path, version); }
            return relative.substring(0, slash) + "/" + version + relative.substring(slash);
        } catch (IOException e) { throw missing(e); }
    }

    private static void name(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("One safe document name is required");
    }

    private static FileNotFoundException missing(Exception e) {
        FileNotFoundException failure = new FileNotFoundException(e.getMessage());
        failure.initCause(e); return failure;
    }

    @Override public Cursor queryRoots(String[] projection) {
        // No persistent picker root: only the explicitly granted per-run tree exists.
        return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID} : projection);
    }

    private void row(MatrixCursor cursor, File file) throws FileNotFoundException {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) value = id(file);
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) value = file.getName();
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column))
                value = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
            else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) value = file.isDirectory() ? 0L : file.length();
            else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) value = FIXTURE_MODIFIED;
            else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) value =
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                    | (file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    : DocumentsContract.Document.FLAG_SUPPORTS_WRITE);
            row.add(column, value);
        }
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        row(cursor, file(documentId)); return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
        throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        File[] children = file(parentDocumentId).listFiles();
        if (children == null) throw new FileNotFoundException("Not a fixture directory");
        for (File child : children) row(cursor, child);
        return cursor;
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        try { return file(documentId).getPath().startsWith(file(parentDocumentId).getPath() + File.separator); }
        catch (FileNotFoundException e) { return false; }
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName)
        throws FileNotFoundException {
        name(displayName);
        File parent = file(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Not a directory");
        File child = new File(parent, displayName);
        try {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType) ? !child.mkdir() : !child.createNewFile())
                throw new IOException("Document already exists or could not be created");
            return id(child);
        } catch (IOException e) { throw missing(e); }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
        throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        File target = file(documentId);
        if (!target.isFile()) throw new FileNotFoundException("Not a fixture file");
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public synchronized String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        name(displayName);
        if (documentId.indexOf('/') < 0) throw new FileNotFoundException("Cannot rename a session root");
        File original = file(documentId);
        File renamed = new File(original.getParentFile(), displayName);
        if (renamed.exists() || !original.renameTo(renamed)) throw new FileNotFoundException("Rename refused");
        // Invalidate the old path and any directory descendants. New occupants get fresh IDs.
        versions.keySet().removeIf(path -> path.equals(original.getPath()) || path.startsWith(original.getPath() + "/"));
        versions.remove(renamed.getPath());
        return id(renamed); // Path IDs deliberately change, matching renameDocument's new-ID contract.
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        if (documentId.indexOf('/') < 0) throw new FileNotFoundException("Cannot delete a session root");
        File target = file(documentId);
        try { remove(target); } catch (IOException e) { throw missing(e); }
        synchronized (this) {
            versions.keySet().removeIf(path -> path.equals(target.getPath()) || path.startsWith(target.getPath() + "/"));
        }
    }

    private static void remove(File file) throws IOException {
        File[] children = file.listFiles();
        if (file.isDirectory() && children == null) throw new IOException("Cannot list fixture");
        if (children != null) for (File child : children) remove(child);
        if (file.exists() && !file.delete()) throw new IOException("Cannot delete fixture " + file.getName());
    }

    /** Bootstrap grants originate in the provider UID, never from a spoofed test Context. */
    public static final class Control extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            try {
                int caller = Binder.getCallingUid();
                int target = getContext().getPackageManager().getApplicationInfo(TARGET, 0).uid;
                if (caller != target) throw new SecurityException("Only the smoke target UID may control fixtures");
                String token = session(arg);
                long identity = Binder.clearCallingIdentity();
                try {
                    File base = fixtures(getContext());
                    File directory = new File(base, token);
                    Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, token);
                    Bundle result = new Bundle();
                    if ("begin".equals(method)) {
                        if ((!base.isDirectory() && !base.mkdirs()) || !directory.mkdir())
                            throw new IOException("Cannot create a fresh smoke fixture");
                        try { getContext().grantUriPermission(TARGET, tree, GRANTS); }
                        catch (RuntimeException e) { remove(directory); throw e; }
                        result.putString("tree", tree.toString());
                        result.putInt("provider_uid", android.os.Process.myUid());
                        result.putInt("provider_pid", android.os.Process.myPid());
                    } else if ("end".equals(method)) {
                        try {
                            getContext().revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } finally {
                            remove(directory);
                            File[] remaining = base.listFiles();
                            if (remaining != null && remaining.length == 0 && !base.delete())
                                throw new IOException("Cannot remove empty fixture base");
                        }
                        result.putBoolean("removed", !directory.exists());
                    } else throw new IllegalArgumentException("Unknown smoke control method");
                    return result;
                } finally { Binder.restoreCallingIdentity(identity); }
            } catch (IOException | android.content.pm.PackageManager.NameNotFoundException e) {
                throw new IllegalStateException("Smoke fixture control failed", e);
            }
        }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri u) { throw new UnsupportedOperationException(); }
        @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    }
}
