package com.termux.app.files.privileged;

import com.termux.app.files.fs.FileLocation;
import org.junit.Test;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Host tests exercise real URI/path/symlink handling, without simulating privileged Android. */
public class PrivilegedCoreTest {
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action) throws Exception {
        try { action.run(); fail("Expected IOException"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void specialFilenamesRoundTripThroughParentLocation() throws Exception {
        for (String scheme : Arrays.asList("root", "shizuku")) {
            for (String name : Arrays.asList("a b", "漢字.txt", "#?%name", "$(id);'\"`whoami`", "line\nfeed\rname", "\\backslash", "😀")) {
                String path = PrivilegedPaths.child("/data/local/tmp", name);
                FileLocation location = new FileLocation(PrivilegedPaths.uri(scheme, path));
                assertEquals(path, PrivilegedPaths.path(scheme, location.uri));
            }
        }
    }

    @Test public void namespacesAndUriFieldsCannotEscapeBackend() throws Exception {
        for (String bad : Arrays.asList("file:/data/x", "shizuku:/data/x", "root://host/data/x",
            "root:/data/x?query", "root:/data/x#fragment", "root:relative", "root:////host/path")) {
            // Four slashes contain no URI authority, but should never produce a //host file path.
            if (bad.equals("root:////host/path")) assertEquals("/host/path", PrivilegedPaths.path("root", bad));
            else rejected(() -> PrivilegedPaths.path("root", bad));
        }
    }

    @Test public void encodedNulAndDotTraversalAreRejected() throws Exception {
        for (String bad : Arrays.asList("root:/a/%00b", "root:/a/%2e%2e/b", "root:/a/%2E/b"))
            rejected(() -> PrivilegedPaths.path("root", bad));
        rejected(() -> PrivilegedPaths.validate("relative"));
        rejected(() -> PrivilegedPaths.validate("/a/../b"));
        rejected(() -> PrivilegedPaths.validate(null));
    }

    @Test public void childCannotInjectAnAbsoluteOrMultipleComponentPath() throws Exception {
        for (String bad : Arrays.asList("", ".", "..", "/escape", "a/b", "a\0b"))
            rejected(() -> PrivilegedPaths.child("/data", bad));
    }

    @Test public void filenameContainingPercentSlashStaysLiteral() throws Exception {
        String path = PrivilegedPaths.child("/data", "%2Fetc%2Fpasswd");
        assertEquals("/data/%2Fetc%2Fpasswd", PrivilegedPaths.path("root", PrivilegedPaths.uri("root", path)));
    }

    @Test public void utf8LimitIsBytesRatherThanCharacters() throws Exception {
        char[] chars = new char[1500]; Arrays.fill(chars, '界');
        rejected(() -> PrivilegedPaths.validate("/" + new String(chars)));
        assertEquals("/a/b", PrivilegedPaths.validate("//a///b/"));
    }

    @Test public void parentTraversalStopsAtRoot() throws Exception {
        assertNull(PrivilegedPaths.parent("/"));
        assertEquals("/", PrivilegedPaths.parent("/data"));
        assertEquals("/data", PrivilegedPaths.parent("/data/x"));
        assertEquals("/name", PrivilegedPaths.child("/", "name"));
    }

    @Test public void deletionAndRenameProtectStorageRoots() throws Exception {
        for (String root : Arrays.asList("/", "/sdcard", "/mnt/sdcard", "/storage", "/storage/emulated",
            "/storage/emulated/0", "/storage/emulated/10", "/storage/self/primary", "/storage/ABCD-1234",
            "/mnt", "/mnt/media_rw/ABCD-1234", "/mnt/runtime/write/emulated/0", "/data/media/0"))
            rejected(() -> PrivilegedPaths.requireDeletable(root));
        PrivilegedPaths.requireDeletable("/storage/emulated/0/Documents");
        PrivilegedPaths.requireDeletable("/storage/ABCD-1234/file");
        PrivilegedPaths.requireDeletable("/data/local/tmp/file");
    }

    @Test public void canonicalRootAliasIsProtectedButLinkItselfCanBeUnlinked() throws Exception {
        Path directory = Files.createTempDirectory("priv-links-");
        Path link = directory.resolve("root-alias");
        try {
            Files.createSymbolicLink(link, directory.getRoot());
            rejected(() -> PrivilegedPaths.requireDeletableResolved(link.toString(), false));
            PrivilegedPaths.requireDeletableResolved(link.toString(), true);
            assertTrue(Files.isSymbolicLink(link));
            assertEquals(directory.getRoot(), Files.readSymbolicLink(link));
        } finally { Files.deleteIfExists(link); Files.delete(directory); }
    }

    @Test public void rootIdentityMustComeFromConsistentKernelCredentials() throws Exception {
        int uid = KernelIdentity.effectiveUid(new StringReader("Name:\tapp_process\nUid:\t0\t0\t0\t0\nGid:\t0\n"));
        assertEquals(0, uid);
        KernelIdentity.requirePrivilege(uid, true);
    }

    @Test public void shellIdentityNeverCountsAsRoot() throws Exception {
        int uid = KernelIdentity.effectiveUid(new StringReader("Uid:\t2000\t2000\t2000\t2000\n"));
        assertEquals(2000, uid);
        KernelIdentity.requirePrivilege(uid, false);
        rejected(() -> KernelIdentity.requirePrivilege(uid, true));
    }

    @Test public void applicationIdentityIsRejectedForBothBackends() throws Exception {
        rejected(() -> KernelIdentity.requirePrivilege(10123, true));
        rejected(() -> KernelIdentity.requirePrivilege(10123, false));
        rejected(() -> KernelIdentity.requirePrivilege(-1, false));
    }

    @Test public void inconsistentOrMalformedKernelRecordsFailClosed() throws Exception {
        for (String record : Arrays.asList("", "Name: su\n", "Uid: 0 2000 0 0\n", "Uid: 0 0 0 2000\n",
            "Uid: 0 0 2000 0\n", "Uid: 0\n", "Uid: -1 -1 -1 -1\n", "Uid: root root root root\n",
            "Uid: 4294967295 4294967295 4294967295 4294967295\n"))
            rejected(() -> KernelIdentity.effectiveUid(new StringReader(record)));
    }

    @Test public void procStatusScanHasABound() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int n = 0; n < 513; n++) text.append("Name: filler\n");
        text.append("Uid: 0 0 0 0\n");
        rejected(() -> KernelIdentity.effectiveUid(new StringReader(text.toString())));
    }

    @Test public void mountRootsIncludeBindMountsAndEscapedNames() throws Exception {
        String table = "1 0 0:1 / / rw - rootfs rootfs rw\n"
            + "2 1 8:0 /subtree /vendor\\040disk rw shared:4 - ext4 /dev/disk rw\n"
            + "3 1 0:2 / /tab\\011line\\012slash\\134name rw - tmpfs tmpfs rw\n";
        java.util.Set<String> roots = MountRoots.read(new StringReader(table));
        assertEquals(3, roots.size());
        assertTrue(roots.contains("/"));
        assertTrue(roots.contains("/vendor disk"));
        assertTrue(roots.contains("/tab\tline\nslash\\name"));
    }

    @Test public void brokenMountTableCannotDisableRootProtection() throws Exception {
        for (String broken : Arrays.asList("", "1 2\n", "1 0 0:1 / /x rw not-a-separator\n",
            "1 0 0:1 / /bad\\777path rw - tmpfs tmpfs rw\n"))
            rejected(() -> MountRoots.read(new StringReader(broken)));
    }

    @Test public void additionalAndroidStorageMountAliasesAreProtected() throws Exception {
        for (String path : Arrays.asList("/mnt/user/0/emulated/0", "/mnt/pass_through/0/emulated/0",
            "/mnt/installer/0/ABCD-1234", "/mnt/androidwritable/10/emulated/10"))
            rejected(() -> PrivilegedPaths.requireDeletable(path));
        PrivilegedPaths.requireDeletable("/mnt/user/0/emulated/0/Documents");
    }
}
