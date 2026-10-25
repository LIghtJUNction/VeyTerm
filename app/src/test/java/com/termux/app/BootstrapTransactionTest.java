package com.termux.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class BootstrapTransactionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void successfulSwitchRetainsExistingPackages() throws Exception {
        File prefix = temporary.newFolder("usr");
        File stage = temporary.newFolder("usr-staging");
        File previous = new File(temporary.getRoot(), "usr.previous");
        Files.write(new File(prefix, "user-package").toPath(), new byte[]{1});
        Files.write(new File(stage, "new-package").toPath(), new byte[]{2});
        BootstrapTransaction.commit(stage, prefix, previous);
        assertTrue(new File(prefix, "new-package").isFile());
        assertTrue(new File(previous, "user-package").isFile());
        assertFalse(stage.exists());
    }

    @Test public void failedSwitchRestoresExistingPackages() throws Exception {
        File prefix = temporary.newFolder("usr");
        Files.write(new File(prefix, "user-package").toPath(), new byte[]{1});
        File stage = new File(temporary.newFolder("usr-staging").getPath()) {
            @Override public boolean renameTo(File destination) { return false; }
        };
        File previous = new File(temporary.getRoot(), "usr.previous");
        try {
            BootstrapTransaction.commit(stage, prefix, previous);
            fail("A failed switch must not report success");
        } catch (IOException expected) {
            assertTrue(new File(prefix, "user-package").isFile());
            assertTrue(stage.isDirectory());
            assertFalse(previous.exists());
        }
    }

    @Test public void interruptedSwitchRecoversPreviousPrefix() throws Exception {
        File previous = temporary.newFolder("usr.previous");
        Files.write(new File(previous, "user-package").toPath(), new byte[]{1});
        File prefix = new File(temporary.getRoot(), "usr");
        BootstrapTransaction.recover(prefix, previous);
        assertTrue(new File(prefix, "user-package").isFile());
    }

    @Test public void rejectsArchiveTraversal() throws Exception {
        File stage = temporary.newFolder("usr-staging");
        for (String name : new String[]{"../home/.zshrc", "/absolute"}) {
            try {
                BootstrapTransaction.archivePath(stage, name);
                fail("Accepted archive path: " + name);
            } catch (IOException expected) {
                assertFalse(new File(temporary.getRoot(), "home/.zshrc").exists());
            }
        }
    }

    @Test public void rejectsOldPrefixAcrossReadBufferBoundary() throws Exception {
        File prefix = temporary.newFolder("usr");
        File bin = new File(prefix, "bin");
        assertTrue(bin.mkdir());
        byte[] data = new byte[8200];
        Files.write(new File(bin, "apt").toPath(), new byte[]{1});
        byte[] oldPath = "/data/data/com.termux/files/usr/lib".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[data.length + oldPath.length];
        System.arraycopy(oldPath, 0, payload, 8190, oldPath.length);
        Files.write(new File(bin, "bash").toPath(), payload);
        assertFalse(BootstrapTransaction.isHealthy(prefix, false));
    }
}
