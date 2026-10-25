package com.termux.app;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class BootstrapArchiveInputStreamTest {
    @Test public void extractsZipContentAndChecksItsCrc() throws Exception {
        byte[] expected = "native bootstrap payload".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("bin/fixture"));
            zip.write(expected);
            zip.closeEntry();
        }
        ByteBuffer mapped = ByteBuffer.allocateDirect(bytes.size());
        mapped.put(bytes.toByteArray()).flip();
        try (ZipInputStream zip = new ZipInputStream(new BootstrapArchiveInputStream(mapped))) {
            assertEquals("bin/fixture", zip.getNextEntry().getName());
            ByteArrayOutputStream extracted = new ByteArrayOutputStream();
            byte[] buffer = new byte[3];
            int count;
            while ((count = zip.read(buffer)) != -1) extracted.write(buffer, 0, count);
            assertArrayEquals(expected, extracted.toByteArray());
            assertNull(zip.getNextEntry());
        }
        assertEquals("Reading must not change the native buffer's position", 0, mapped.position());
    }

    @Test public void supportsUnsignedBytesAndZeroLengthReadAtEnd() {
        BootstrapArchiveInputStream input = new BootstrapArchiveInputStream(ByteBuffer.wrap(new byte[]{(byte) 255}));
        assertEquals(255, input.read());
        assertEquals(0, input.available());
        assertEquals(0, input.read(new byte[1], 0, 0));
        assertEquals(-1, input.read());
        assertEquals(-1, input.read(new byte[1], 0, 1));
    }

    @Test public void respectsDestinationOffsetAndChecksBounds() {
        BootstrapArchiveInputStream input = new BootstrapArchiveInputStream(ByteBuffer.wrap(new byte[]{1, 2}));
        byte[] destination = new byte[4];
        assertEquals(2, input.read(destination, 1, 3));
        assertArrayEquals(new byte[]{0, 1, 2, 0}, destination);
        try {
            input.read(destination, 3, 2);
            fail("Out-of-bounds reads must fail even at EOF");
        } catch (IndexOutOfBoundsException expected) {
            assertEquals(0, input.available());
        }
    }
}
