package com.termux.app;

import java.io.InputStream;
import java.nio.ByteBuffer;

/** Reads the native library's mapped archive without copying it onto the Java heap. */
final class BootstrapArchiveInputStream extends InputStream {
    private final ByteBuffer buffer;

    BootstrapArchiveInputStream(ByteBuffer buffer) {
        this.buffer = buffer.asReadOnlyBuffer();
    }

    @Override public int read() {
        return buffer.hasRemaining() ? buffer.get() & 0xff : -1;
    }

    @Override public int read(byte[] destination, int offset, int length) {
        if (offset < 0 || length < 0 || length > destination.length - offset)
            throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        if (!buffer.hasRemaining()) return -1;
        int count = Math.min(length, buffer.remaining());
        buffer.get(destination, offset, count);
        return count;
    }

    @Override public int available() {
        return buffer.remaining();
    }
}
