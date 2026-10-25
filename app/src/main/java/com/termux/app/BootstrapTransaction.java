package com.termux.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Filesystem boundary for validating and switching a staged native prefix. */
final class BootstrapTransaction {
    static final String MARKER = ".veyterm-native-bootstrap-v2";
    private static final byte[] UPSTREAM_PREFIX = "/data/data/com.termux".getBytes(StandardCharsets.UTF_8);

    static File archivePath(File staging, String name) throws IOException {
        File target = new File(staging, name);
        if (name.startsWith("/") || !target.getCanonicalPath().startsWith(staging.getCanonicalPath() + "/")) {
            throw new IOException("Bootstrap entry escapes staging: " + name);
        }
        return target;
    }

    static boolean isHealthy(File prefix, boolean pacman) throws IOException {
        String[] required = pacman
            ? new String[]{"bin/bash", "bin/zsh", "bin/pacman", "bin/paru", "bin/proot", "bin/proot-distro", "etc/pacman.conf"}
            : new String[]{"bin/bash", "bin/apt"};
        for (String name : required) {
            File file = new File(prefix, name);
            if (!file.isFile() || file.length() == 0 || containsUpstreamPrefix(file)) return false;
        }
        return true;
    }

    private static boolean containsUpstreamPrefix(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int matched = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == UPSTREAM_PREFIX[matched]) {
                        if (++matched == UPSTREAM_PREFIX.length) return true;
                    } else {
                        matched = buffer[i] == UPSTREAM_PREFIX[0] ? 1 : 0;
                    }
                }
            }
            return false;
        }
    }

    static void recover(File prefix, File previous) throws IOException {
        if (!prefix.exists() && previous.exists() && !previous.renameTo(prefix)) {
            throw new IOException("Could not restore interrupted bootstrap from " + previous);
        }
    }

    static void commit(File staging, File prefix, File previous) throws IOException {
        if (previous.exists()) {
            File retained = new File(previous.getPath() + "-" + System.nanoTime());
            if (!previous.renameTo(retained)) throw new IOException("Could not retain previous bootstrap: " + previous);
        }
        boolean hadPrefix = prefix.exists();
        if (hadPrefix && !prefix.renameTo(previous)) throw new IOException("Could not retain existing prefix: " + prefix);
        if (!staging.renameTo(prefix)) {
            if (hadPrefix && !previous.renameTo(prefix)) {
                throw new IOException("Bootstrap switch failed; restore the retained prefix at " + previous);
            }
            throw new IOException("Bootstrap switch failed; existing prefix restored");
        }
    }

    static void markHealthy(File prefix) throws IOException {
        try (FileOutputStream marker = new FileOutputStream(new File(prefix, MARKER))) {
            marker.write("2\n".getBytes(StandardCharsets.UTF_8));
            marker.getFD().sync();
        }
    }
}
