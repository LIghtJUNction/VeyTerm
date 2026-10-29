package com.termux.app.files.privileged;

import android.os.Bundle;
import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;
import java.io.FileNotFoundException;
import java.io.IOException;

final class PrivilegedWire {
    static final int VERSION = 1;
    static final int PAGE_SIZE = 64;
    private PrivilegedWire() { }

    static Bundle arguments(String path) {
        Bundle args = new Bundle();
        args.putString("path", path);
        return args;
    }

    static Bundle entry(FileEntry entry) {
        Bundle data = new Bundle();
        data.putString("name", entry.name);
        data.putBoolean("directory", entry.directory);
        data.putBoolean("link", entry.symbolicLink);
        data.putLong("size", entry.size);
        data.putLong("modified", entry.modified);
        return data;
    }

    static FileEntry entry(FileLocation location, Bundle data) throws IOException {
        if (data == null || !data.containsKey("name")) throw new IOException("Missing privileged file metadata");
        return new FileEntry(location, data.getString("name"), data.getBoolean("directory"),
            data.getBoolean("link"), data.getLong("size"), data.getLong("modified"));
    }

    static Bundle failure(Exception error) {
        Bundle data = new Bundle();
        String message = error.getMessage();
        if (message == null) message = error.getClass().getSimpleName();
        data.putString("error", message.length() > 8192 ? message.substring(0, 8192) : message);
        data.putBoolean("missing", error instanceof FileNotFoundException);
        return data;
    }

    static Bundle checked(Bundle data) throws IOException {
        if (data == null) throw new IOException("Privileged service returned no response");
        if (data.containsKey("error")) {
            if (data.getBoolean("missing")) throw new FileNotFoundException(data.getString("error"));
            throw new IOException(data.getString("error"));
        }
        return data;
    }
}
