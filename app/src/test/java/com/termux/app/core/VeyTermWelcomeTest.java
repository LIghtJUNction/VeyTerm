package com.termux.app.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class VeyTermWelcomeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void replacesShippedTemplateOnceAndKeepsCustomText() throws Exception {
        File prefix = temporary.newFolder(), etc = new File(prefix, "etc"); assertTrue(etc.mkdir()); File message = new File(etc, "motd");
        try (java.io.InputStream input = getClass().getResourceAsStream("/branding/stock-motd.txt")) { Files.copy(input, message.toPath()); }
        assertTrue(VeyTermWelcome.refresh(prefix, false));
        assertTrue(new String(Files.readAllBytes(message.toPath()), StandardCharsets.UTF_8).startsWith("Welcome to VeyTerm!"));
        assertFalse(VeyTermWelcome.refresh(prefix, false));
        Files.write(message.toPath(), "My own greeting\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(VeyTermWelcome.refresh(prefix, false));
        assertEquals("My own greeting\n", new String(Files.readAllBytes(message.toPath()), StandardCharsets.UTF_8));
        assertArrayEquals(new String[]{"motd"}, etc.list());
    }
    @Test public void leavesUserSymlinkAndItsTargetUntouched() throws Exception {
        File prefix = temporary.newFolder(), etc = new File(prefix, "etc"); assertTrue(etc.mkdir()); File target = temporary.newFile();
        Files.write(target.toPath(), "Owned by the user".getBytes(StandardCharsets.UTF_8));
        File message = new File(etc, "motd"); Files.createSymbolicLink(message.toPath(), target.toPath());
        assertFalse(VeyTermWelcome.refresh(prefix, true));
        assertTrue(Files.isSymbolicLink(message.toPath()));
        assertEquals("Owned by the user", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void createsFreshWelcomeWithoutPackageManagerMisinformation() throws Exception {
        File prefix = temporary.newFolder(); assertTrue(VeyTermWelcome.refresh(prefix, true));
        String text = new String(Files.readAllBytes(new File(prefix, "etc/motd").toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("VeyTerm")); assertTrue(text.contains("dual-pane")); assertFalse(text.contains("pkg install"));
    }
}
