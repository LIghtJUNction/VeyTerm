package com.termux.app.files.fs;

import com.termux.app.files.remote.ConnectionStore;
import com.termux.app.files.remote.RemoteFiles;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteLocationIdentityTest {
    @Test public void savedRecordNamesAndDifferentRootsPreserveServerHierarchy() {
        RemoteFiles.Connection broad = connection("first", "/", "fixture-user");
        RemoteFiles.Connection narrow = connection("renamed-record", "/projects/current", "fixture-user");
        assertTrue(ConnectionStore.contains(broad, "/projects/current", narrow, "/"));
        assertTrue(ConnectionStore.contains(narrow, "/", broad, "/projects/current"));
        assertTrue(ConnectionStore.contains(broad, "/projects", narrow, "/nested"));
        assertFalse(ConnectionStore.contains(narrow, "/", broad, "/projects/other"));
        assertFalse(ConnectionStore.contains(broad, "/", connection("different-account", "/", "other-user"), "/"));
    }
    private static RemoteFiles.Connection connection(String id, String root, String user) {
        return new RemoteFiles.Connection(id, id, RemoteFiles.Protocol.FTP, "fixture.invalid", 21,
            root, user, "fixture-password", "", "", 8000, true);
    }
}
