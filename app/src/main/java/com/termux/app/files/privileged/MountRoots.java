package com.termux.app.files.privileged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;

/** Actual mount roots supplement Android's known emulated-storage root aliases. */
final class MountRoots {
    private MountRoots() { }

    static Set<String> read(Reader mountinfo) throws IOException {
        BufferedReader reader = new BufferedReader(mountinfo);
        Set<String> roots = new HashSet<>();
        String line;
        int count = 0;
        while ((line = reader.readLine()) != null) {
            if (++count > 16384 || line.length() > 65536) throw new IOException("Mount table exceeds safe parsing limits");
            String[] fields = line.split(" ", 7);
            if (fields.length < 7 || !fields[6].contains(" - ") && !fields[6].startsWith("- "))
                throw new IOException("Cannot verify filesystem mount roots");
            roots.add(PrivilegedPaths.validate(unescape(fields[4])));
        }
        if (roots.isEmpty()) throw new IOException("Filesystem mount roots unavailable");
        return roots;
    }

    private static String unescape(String text) throws IOException {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') { decoded.append(c); continue; }
            if (i + 3 >= text.length()) throw new IOException("Invalid mount path escape");
            String octal = text.substring(i + 1, i + 4);
            switch (octal) {
                case "040": decoded.append(' '); break;
                case "011": decoded.append('\t'); break;
                case "012": decoded.append('\n'); break;
                case "134": decoded.append('\\'); break;
                default: throw new IOException("Invalid mount path escape");
            }
            i += 3;
        }
        return decoded.toString();
    }
}
