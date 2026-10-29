package com.termux.app.files.decompile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** UTF-8 byte pages: offsets are explicit, and no character is split or discarded. */
public final class SourcePages {
    public static final int PAGE_BYTES = 32 * 1024;

    public static final class Page {
        public final long start, end, total;
        public final String text;
        Page(long start, long end, long total, String text) {
            this.start = start; this.end = end; this.total = total; this.text = text;
        }
    }

    public static Page read(File file, long start) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long total = in.length();
            if (start < 0 || start > total) throw new IOException("Source page offset outside file");
            in.seek(start);
            if (start < total && continuation(in.read())) throw new IOException("Source page starts inside UTF-8 character");
            in.seek(start);
            byte[] bytes = new byte[(int) Math.min(PAGE_BYTES, total - start)];
            in.readFully(bytes);
            int count = bytes.length;
            if (start + count < total) {
                int next = in.read();
                if (continuation(next)) {
                    do { count--; } while (count > 0 && continuation(bytes[count] & 255));
                }
            }
            return new Page(start, start + count, total, new String(bytes, 0, count, StandardCharsets.UTF_8));
        }
    }

    private static boolean continuation(int value) { return value >= 0 && (value & 0xc0) == 0x80; }
    private SourcePages() {}
}
