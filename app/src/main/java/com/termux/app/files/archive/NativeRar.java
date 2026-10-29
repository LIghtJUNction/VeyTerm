package com.termux.app.files.archive;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Private, synchronous streaming bridge. A reader is owned by one operation. */
final class NativeRar implements Closeable {
    interface Check { void run() throws IOException; }
    private static final Throwable LOAD_ERROR;
    static {
        Throwable error = null;
        try { System.loadLibrary("veyterm_archive"); }
        catch (LinkageError | SecurityException e) { error = e; }
        LOAD_ERROR = error;
    }
    private long handle;
    private final Check checker;
    static final class Member {
        final String name;
        final long size, modified;
        final boolean directory, link, encrypted, special;
        // Called by JNI. Decode strictly: never replace malformed names silently.
        Member(byte[] name, long size, long modified, int flags) throws IOException {
            try {
                this.name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();
            } catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8 RAR member name", e); }
            this.size = size; this.modified = modified;
            directory = (flags & 1) != 0; link = (flags & 2) != 0;
            encrypted = (flags & 4) != 0; special = (flags & 8) != 0;
        }
    }
    NativeRar(File file, int memoryKiB, Check checker) throws IOException {
        this.checker = checker;
        check();
        if (LOAD_ERROR != null) throw new IOException("RAR reader unavailable: package libveyterm_archive.so for this ABI", LOAD_ERROR);
        handle = open(file.getAbsolutePath().getBytes(StandardCharsets.UTF_8), memoryKiB * 1024L);
    }
    // Also called from each native input callback during header/solid parsing.
    private void check() throws IOException { checker.run(); }
    Member next() throws IOException { check(); return next(handle); }
    void copy(OutputStream out) throws IOException {
        byte[] block = new byte[32768]; int n;
        while (true) {
            check(); n = read(handle, block);
            if (n < 0) return;
            out.write(block, 0, n);
        }
    }
    long peakBytes() { return peak(handle); }
    @Override public void close() throws IOException {
        if (handle != 0) { long h = handle; handle = 0; release(h); }
    }
    private native long open(byte[] path, long memoryBytes) throws IOException;
    private native Member next(long handle) throws IOException;
    private native int read(long handle, byte[] block) throws IOException;
    private native void release(long handle) throws IOException;
    private native long peak(long handle);
}
