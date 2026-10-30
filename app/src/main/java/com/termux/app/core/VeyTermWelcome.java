package com.termux.app.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Replace the bundled welcome template while retaining user-written messages and links. */
public final class VeyTermWelcome {
    private VeyTermWelcome() { }
    public static final String TEXT = "Welcome to VeyTerm!\n\n"
        + "Open the folder button for the dual-pane file workspace.\n"
        + "Help:   https://github.com/LIghtJUNction/VeyTerm#readme\n"
        + "Issues: https://github.com/LIghtJUNction/VeyTerm/issues\n";
    private static final byte[] STOCK = ("Welcome to Termux!\n\n"
        + "Docs:       https://termux.dev/docs\nDonate:     https://termux.dev/donate\nCommunity:  https://termux.dev/community\n\n"
        + "Working with packages:\n\n - Search:  pkg search <query>\n - Install: pkg install <package>\n - Upgrade: pkg upgrade\n\n"
        + "Report issues at https://termux.dev/issues\n").getBytes(StandardCharsets.UTF_8);

    public static boolean refresh(File prefix, boolean freshInstall) throws IOException {
        File parent = new File(prefix, "etc");
        if (!parent.isDirectory()) { if (!freshInstall) return false; if (!parent.mkdirs()) throw new IOException("Cannot create welcome-message directory"); }
        String root = prefix.getCanonicalPath();
        if (!parent.getCanonicalPath().startsWith(root + File.separator)) throw new IOException("Welcome-message directory leaves the application prefix");
        File target = new File(parent, "motd");
        File expected = new File(parent.getCanonicalFile(), target.getName());
        if (!target.getCanonicalFile().equals(expected)) return false;
        byte[] before = target.isFile() ? readSmall(target) : null;
        if (!freshInstall && !Arrays.equals(before, STOCK)) return false;
        File temporary = File.createTempFile(".veyterm-welcome-", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(TEXT.getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
            if (!temporary.setReadable(true, false)) throw new IOException("Cannot set welcome-message permissions");
            if (before != null && !Arrays.equals(before, readSmall(target))) throw new IOException("Welcome message changed during update; original retained");
            if (!temporary.renameTo(target)) throw new IOException("Cannot publish welcome message; original retained");
            return true;
        } finally { if (temporary.exists() && !temporary.delete()) throw new IOException("Welcome-message temporary file retained: " + temporary); }
    }
    private static byte[] readSmall(File file) throws IOException {
        if (file.length() > 8192) return new byte[0];
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] data = new byte[1024]; int length;
            while ((length = input.read(data)) != -1) { if (output.size() + length > 8192) return new byte[0]; output.write(data, 0, length); }
            return output.toByteArray();
        }
    }
}
