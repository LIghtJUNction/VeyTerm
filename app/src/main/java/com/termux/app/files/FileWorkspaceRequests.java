package com.termux.app.files;

import android.os.SystemClock;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One-use in-process requests keep the exported terminal entry from becoming a command runner. */
public final class FileWorkspaceRequests {
    public static final String EXTRA = "com.veyterm.files.REQUEST";
    public static final class Request {
        public final File directory, script;
        private final long created = SystemClock.elapsedRealtime();
        private Request(File directory, File script) { this.directory = directory; this.script = script; }
    }
    private static final Map<String, Request> requests = new ConcurrentHashMap<>();
    private FileWorkspaceRequests() { }
    public static String create(File directory, File script) {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, Request> entry : requests.entrySet()) if (now - entry.getValue().created > 60_000) requests.remove(entry.getKey());
        if (requests.size() >= 32) throw new IllegalStateException("Too many pending terminal requests");
        String token = UUID.randomUUID().toString(); requests.put(token, new Request(directory, script)); return token;
    }
    public static Request take(String token) {
        if (token == null) return null;
        Request request = requests.remove(token);
        return request != null && SystemClock.elapsedRealtime() - request.created <= 60_000 ? request : null;
    }
}
