package com.termux.app.files.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import static com.termux.app.files.archive.ArchiveTools.*;
import static org.junit.Assert.*;

public class RarArchiveTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final Options options = new Options();
    private interface IO { void run() throws Exception; }
    private IOException fails(IO work) throws Exception {
        try { work.run(); fail("Expected IOException"); }
        catch (IOException e) { return e; }
        throw new AssertionError();
    }
    private File fixture(String name) {
        return new File(System.getProperty("veyterm.rar.fixtures"), "test_read_format_" + name + ".rar");
    }
    private File save(byte[] bytes) throws IOException {
        File f = tmp.newFile(); Files.write(f.toPath(), bytes); return f;
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String digest(File f) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[32768]; int n;
            while ((n = in.read(b)) >= 0) sha.update(b, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : sha.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static void polynomial(File f, int magic, int size) throws IOException {
        byte[] data = Files.readAllBytes(f.toPath()); assertEquals(size, data.length);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int k = 1; b.hasRemaining(); k++) assertEquals(Math.max(0, k*k - 3*k + 1 + magic), b.getInt());
    }
    private void noStaging(File root) throws IOException {
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".vey-")));
        }
    }
    @Test public void rar5StoredAndCompressedHaveKnownContents() throws Exception {
        for (String suffix : Arrays.asList("stored", "compressed")) {
            File f = fixture("rar5_" + suffix); String original = digest(f);
            assertEquals(Format.RAR, detect(f)); List<Entry> entries = list(f, options, null);
            assertEquals(1, entries.size()); Entry entry = entries.get(0);
            assertFalse(entry.directory); assertFalse(entry.encrypted); assertTrue(entry.modified > 0);
            test(f, options, null); File dest = tmp.newFolder(); extract(f, dest, null, options, null);
            if (suffix.equals("stored")) {
                assertEquals("helloworld.txt", entry.name);
                assertArrayEquals(bytes("hello libarchive test suite!\n"), Files.readAllBytes(new File(dest, entry.name).toPath()));
            } else {
                assertEquals("test.bin", entry.name); polynomial(new File(dest, entry.name), 0, 1200);
            }
            assertEquals(original, digest(f)); noStaging(dest);
        }
    }
    @Test public void rar5MultipleSolidFilesAndSelectedLastFile() throws Exception {
        for (String suffix : Arrays.asList("multiple_files", "multiple_files_solid", "solid")) {
            File f = fixture("rar5_" + suffix); test(f, options, null);
            File dest = tmp.newFolder(); extract(f, dest, null, options, null);
            int count = suffix.equals("solid") ? 6 : 4;
            if (suffix.equals("solid")) polynomial(new File(dest, "test.bin"), 0, 1200);
            for (int i = 1; i <= count; i++) polynomial(new File(dest, "test" + i + ".bin"), i, 4096);
            dest = tmp.newFolder();
            extract(f, dest, Collections.singleton("test" + count + ".bin"), options, null);
            assertEquals(1, Objects.requireNonNull(dest.list()).length);
            polynomial(new File(dest, "test" + count + ".bin"), count, 4096);
        }
    }
    @Test public void rar4CompressedCorpusFirstMembersRetainExactPayload() throws Exception {
        // Upstream archives also contain a symlink. Keep their first complete
        // compressed member, verbatim, plus a real ENDARC from the same archive.
        for (String suffix : Arrays.asList("compress_normal", "compress_best")) {
            byte[] all = Files.readAllBytes(fixture("rar_" + suffix).toPath());
            ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            int start = 7 + (b.getShort(12) & 65535);
            int end = start + (b.getShort(start + 5) & 65535) + b.getInt(start + 7);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(all, 0, end); out.write(all, all.length - 7, 7);
            File f = save(out.toByteArray()); String original = digest(f);
            test(f, options, null); File dest = tmp.newFolder(); extract(f, dest, null, options, null);
            assertEquals(20111, new File(dest, "LibarchiveAddingTest.html").length());
            assertEquals("ee16390e87152d7dec632ae2b37566da6135966e46b5166e6f40a1c827fa61ad",
                digest(new File(dest, "LibarchiveAddingTest.html")));
            assertEquals(original, digest(f));
            File unsafe = new File(tmp.newFolder(), "not-created");
            fails(() -> extract(fixture("rar_" + suffix), unsafe, Collections.singleton("LibarchiveAddingTest.html"), options, null));
            assertFalse(unsafe.exists());
        }
    }
    @Test public void rar4LowDistanceResetCorpusMatchesEveryByte() throws Exception {
        File f = fixture("rar3_lowdist_reset"); test(f, options, null);
        File dest = tmp.newFolder(); extract(f, dest, null, options, null);
        byte[] data = Files.readAllBytes(new File(dest, "lowdist-reset.bin").toPath());
        assertEquals(64, data.length);
        for (int i = 0; i < 40; i++) assertEquals(i, data[i]);
        assertArrayEquals(new byte[]{2,3,4,5,6,7,13,14,15,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54}, Arrays.copyOfRange(data,40,64));
    }
    @Test public void rar4LargeFileStreamsWithBoundedProgressAndNativeMemory() throws Exception {
        File f = fixture("rar_multi_lzss_blocks"); File dest = tmp.newFolder();
        long[] last = {0}; int[] calls = {0};
        extract(f, dest, null, options, new Job((phase, name, done, total) -> {
            if (phase.equals("extract")) { assertTrue(done >= last[0]); assertTrue(done - last[0] <= 32768); last[0] = done; calls[0]++; }
        }));
        assertEquals(20131111, last[0]); assertTrue(calls[0] > 600);
        assertEquals(20131111, new File(dest, "multi_lzss_blocks_test.txt").length());
        try (RandomAccessFile data = new RandomAccessFile(new File(dest, "multi_lzss_blocks_test.txt"), "r")) {
            byte[] tail = bytes("-bottom: 0in\"><BR>\n</P>\n</BODY>\n</HTML>");
            data.seek(data.length() - tail.length); byte[] actual = new byte[tail.length]; data.readFully(actual); assertArrayEquals(tail, actual);
        }
        try (NativeRar reader = new NativeRar(f, 8192, () -> {})) {
            assertNotNull(reader.next()); reader.copy(new OutputStream() { public void write(int b) {} public void write(byte[] b,int o,int n) {} });
            assertTrue(reader.peakBytes() <= 8192 * 1024L); assertTrue(reader.peakBytes() > 1024 * 1024L);
        }
    }
    @Test public void nativeMemoryLimitFailsCleanlyAndReadersRemainIndependent() throws Exception {
        Options small = new Options(); small.maxMemoryKiB = 1024;
        for (String name : Arrays.asList("rar_multi_lzss_blocks", "rar5_multiple_files_solid")) {
            IOException error = fails(() -> test(fixture(name), small, null));
            assertTrue(error.toString(), error.getMessage().contains("memory limit"));
            test(fixture(name), options, null);
        }
        // Tiny hostile compressed header declaring a 64 MiB dictionary. Both RAR5
        // windows are charged before malloc, before inspecting its dummy payload.
        File oversized = save(rar5("oversized", new byte[]{1}, 0100644, 0, null, false, 9));
        try (NativeRar r = new NativeRar(oversized, 1024, () -> {})) {
            IOException error = fails(() -> { r.next(); r.copy(new ByteArrayOutputStream()); });
            assertTrue(error.getMessage(), error.getMessage().contains("memory limit"));
            assertTrue(r.peakBytes() <= 1024 * 1024L);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> {
                try { test(fixture("rar5_compressed"), options, null); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }));
            for (Future<?> result : results) result.get();
        } finally { pool.shutdownNow(); }
    }
    @Test public void cancellationDuringExtractionPreservesExistingTargetAndOriginal() throws Exception {
        File f = fixture("rar_multi_lzss_blocks"); String original = digest(f); File dest = tmp.newFolder();
        File target = new File(dest, "multi_lzss_blocks_test.txt"); Files.write(target.toPath(), bytes("original target"));
        options.replaceExisting = true;
        Job[] job = new Job[1]; job[0] = new Job((phase, name, done, total) -> { if (phase.equals("extract") && done >= 32768) job[0].cancel(); });
        assertTrue(fails(() -> extract(f, dest, null, options, job[0])) instanceof InterruptedIOException);
        assertArrayEquals(bytes("original target"), Files.readAllBytes(target.toPath()));
        assertEquals(original, digest(f)); noStaging(dest);
    }
    @Test public void cancellationInsideNativeInputKeepsTheJavaException() throws Exception {
        int[] checks = {0};
        IOException error = fails(() -> {
            try (NativeRar r = new NativeRar(fixture("rar_multi_lzss_blocks"), 65536, () -> {
                if (++checks[0] == 5) throw new InterruptedIOException("cancel in input callback");
            })) {
                r.next(); r.copy(new ByteArrayOutputStream());
            }
        });
        assertTrue(error.toString(), error instanceof InterruptedIOException);
    }
    @Test public void payloadHeaderCorruptionAndTruncationNeverOverwriteTargets() throws Exception {
        byte[] original = rar5("safe", bytes("uncompressed payload"), 0100644, 0, null);
        for (int position : new int[]{original.length - 10, 12}) {
            byte[] bad = original.clone(); bad[position] ^= 1;
            File f = save(bad); fails(() -> test(f, options, null));
            File dest = tmp.newFolder(); File target = new File(dest, "safe"); Files.write(target.toPath(), bytes("keep"));
            options.replaceExisting = true; fails(() -> extract(f, dest, null, options, null));
            assertArrayEquals(bytes("keep"), Files.readAllBytes(target.toPath())); noStaging(dest);
        }
        File truncated = save(Arrays.copyOf(original, original.length - 15)); fails(() -> test(truncated, options, null));
        for (String bad : Arrays.asList("rar5_bad_tables", "rar5_truncated_huff")) fails(() -> test(fixture(bad), options, null));
    }
    @Test public void encryptedRar4And5FailExplicitlyEvenWithPassword() throws Exception {
        options.password = "12345678".toCharArray();
        for (String name : Arrays.asList("rar4_encrypted", "rar4_encrypted_filenames", "rar4_solid_encrypted",
            "rar5_encrypted", "rar5_encrypted_filenames", "rar5_solid_encrypted")) {
            IOException e = fails(() -> test(fixture(name), options, null));
            assertTrue(e.toString(), e.getMessage().toLowerCase(Locale.ROOT).contains("encrypt"));
        }
        // Data-encrypted entries with clear headers can still expose their flag.
        assertTrue(list(fixture("rar5_encrypted"), options, null).stream().anyMatch(e -> e.encrypted));
    }
    @Test public void multivolumesAndAllRar5RedirectionsAreRejectedBeforeDestinationCreation() throws Exception {
        for (String name : Arrays.asList("rar_multivolume.part0001", "rar5_multiarchive.part01", "rar5_symlink", "rar5_redir_varint_2byte", "rar5_unicode")) {
            File dest = new File(tmp.newFolder(), "not-created"); fails(() -> extract(fixture(name), dest, Collections.emptySet(), options, null));
            assertFalse(dest.exists());
        }
        for (int kind = 1; kind <= 6; kind++) {
            ByteArrayOutputStream redir = new ByteArrayOutputStream(); vint(redir, 5); vint(redir, kind); vint(redir,0); vint(redir,6); redir.write(bytes("target"));
            ByteArrayOutputStream extra = new ByteArrayOutputStream(); vint(extra,redir.size()); extra.write(redir.toByteArray());
            File f = save(rar5("link", new byte[0], 0100644, 0, extra.toByteArray()));
            fails(() -> list(f, options, null));
        }
    }
    @Test public void hostileNamesSpecialModesAndUnselectedMembersAreRejected() throws Exception {
        for (String name : Arrays.asList("../escape", "/absolute", "a\\b", "C:drive", "a//b", "./dot", "bad\0tail")) {
            File f = save(rar5(name, bytes("x"), 0100644, 0, null)); File dest = new File(tmp.newFolder(), "not-created");
            fails(() -> extract(f, dest, Collections.emptySet(), options, null)); assertFalse(dest.exists());
        }
        for (int mode : new int[]{0120777, 0010644, 0020644, 0060644}) {
            File f = save(rar5("special", bytes("x"), mode, 0, null)); fails(() -> list(f, options, null));
        }
    }
    @Test public void rar5Utf8NamesAreNotModifiedUtf8AndBlake2IsChecked() throws Exception {
        File f = save(rar5("目录/👋🌎.txt", bytes("unicode content"), 0100644, 0, null));
        assertEquals("目录/👋🌎.txt", list(f, options, null).get(0).name);
        File dest = tmp.newFolder(); extract(f, dest, null, options, null);
        assertArrayEquals(bytes("unicode content"), Files.readAllBytes(new File(dest,"目录/👋🌎.txt").toPath()));
        test(fixture("rar5_blake2"), options, null);
        byte[] bad = Files.readAllBytes(fixture("rar5_blake2").toPath()); bad[bad.length - 12] ^= 1;
        File broken = save(bad); fails(() -> test(broken, options, null));
    }
    @Test public void rarLimitsReadOnlyOperationsAndDirectoryMetadataKeepOriginal() throws Exception {
        File f = fixture("rar5_multiple_files_solid"); String original = digest(f);
        options.maxEntries = 1; fails(() -> list(f, options, null)); options.maxEntries = 100;
        options.maxEntryBytes = 100; fails(() -> test(f, options, null)); options.maxEntryBytes = 10000;
        options.maxTotalBytes = 5000; fails(() -> test(f, options, null)); options.maxTotalBytes = 100000;
        fails(() -> addReplace(f, Collections.emptyList(), options, null));
        fails(() -> delete(f, Collections.singleton("test1.bin"), options, null));
        fails(() -> rename(f,"test1.bin","new",options,null));
        assertEquals(original, digest(f));
        File directory = save(rar5("empty", new byte[0], 0040755, 0, null));
        assertTrue(list(directory, options, null).get(0).directory);
        File dest = tmp.newFolder(); extract(directory,dest,null,options,null); assertTrue(new File(dest,"empty").isDirectory());
    }
    @Test public void rar4UnsafeNamesDuplicatesAndCompressedCrcAreRejected() throws Exception {
        for (String name : Arrays.asList("../escape", "/abs", "a\\b", "bad\0tail")) {
            File f = save(ArchiveToolsTest.rar4(name, "payload"));
            fails(() -> list(f, options, null));
        }
        byte[] first = ArchiveToolsTest.rar4("duplicate", "one");
        byte[] second = ArchiveToolsTest.rar4("duplicate", "two");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(first, 0, first.length - 7); data.write(second, 20, second.length - 20);
        File duplicate = save(data.toByteArray()); fails(() -> list(duplicate, options, null));
        for (String suffix : Arrays.asList("compress_normal", "compress_best")) {
            byte[] all = Files.readAllBytes(fixture("rar_" + suffix).toPath());
            ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            int start = 7 + (b.getShort(12) & 65535), size = b.getShort(start + 5) & 65535;
            // Alter expected payload CRC, then fix header CRC: the codec must
            // reject the payload, rather than merely noticing a damaged header.
            all[start + 16] ^= 1;
            CRC32 crc = new CRC32(); crc.update(all, start + 2, size - 2);
            b.putShort(start, (short)crc.getValue());
            File f = save(all); fails(() -> test(f, options, null));
        }
    }
    @Test public void rar5PresentZeroCrcAndSplitFlagsAreNotIgnored() throws Exception {
        File zeroCrc = save(rar5("zero", bytes("wrong CRC"), 0100644, 0, null, true));
        fails(() -> test(zeroCrc, options, null));
        File volume = save(rar5("volume", bytes("x"), 0100644, 1, null));
        fails(() -> list(volume, options, null));
    }
    @Test public void truncatedRarHeadersCannotLookLikeSuccessfulEmptyArchives() throws Exception {
        byte[] rar = rar5("file", bytes("payload"), 0100644, 0, null);
        for (int n = 8; n < rar.length; n++) {
            File f = save(Arrays.copyOf(rar, n)); fails(() -> test(f, options, null));
        }
        byte[] old = ArchiveToolsTest.rar4("file", "payload");
        for (int n : new int[]{8, 19, 24, 30, old.length - 8, old.length - 1}) {
            File f = save(Arrays.copyOf(old, n)); fails(() -> test(f, options, null));
        }
        byte[] solid = old.clone();
        ByteBuffer solidHeader = ByteBuffer.wrap(solid).order(ByteOrder.LITTLE_ENDIAN);
        solidHeader.putShort(23, (short)0x8010);
        int solidSize = solidHeader.getShort(25) & 65535;
        CRC32 solidCrc = new CRC32(); solidCrc.update(solid, 22, solidSize - 2);
        solidHeader.putShort(20, (short)solidCrc.getValue());
        File unsupportedSolid = save(solid);
        assertTrue(fails(() -> test(unsupportedSolid, options, null)).getMessage().contains("solid"));
        // RAR4 explicitly permits a completely omitted ENDARC record.
        test(save(Arrays.copyOf(old, old.length - 7)), options, null);
        byte[] encodedNul = ArchiveToolsTest.rar4("legacy\0\0\0\0", "payload");
        ByteBuffer b = ByteBuffer.wrap(encodedNul).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(23, (short)0x8200); int size = b.getShort(25) & 65535;
        CRC32 crc = new CRC32(); crc.update(encodedNul,22,size-2); b.putShort(20,(short)crc.getValue());
        File nul = save(encodedNul); fails(() -> list(nul, options, null));
    }
    // Genuine RAR5 STORE records, with CRC32-protected headers and payload.
    private static void vint(OutputStream out, long n) throws IOException {
        do { int v = (int)(n & 127); n >>>= 7; out.write(v | (n == 0 ? 0 : 128)); } while(n != 0);
    }
    private static void u32(OutputStream out, long n) throws IOException {
        for (int i=0; i<4; i++) { out.write((int)n & 255); n >>>=8; }
    }
    private static void block(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream record = new ByteArrayOutputStream(); vint(record, body.length); record.write(body);
        CRC32 crc = new CRC32(); crc.update(record.toByteArray()); u32(out,crc.getValue()); out.write(record.toByteArray());
    }
    private static byte[] rar5(String name, byte[] data, int mode, int mainFlags, byte[] extra) throws IOException {
        return rar5(name, data, mode, mainFlags, extra, false);
    }
    private static byte[] rar5(String name, byte[] data, int mode, int mainFlags, byte[] extra, boolean zeroCrc) throws IOException {
        return rar5(name, data, mode, mainFlags, extra, zeroCrc, 0);
    }
    private static byte[] rar5(String name, byte[] data, int mode, int mainFlags, byte[] extra, boolean zeroCrc, int dictionaryExponent) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(new byte[]{'R','a','r','!',26,7,1,0});
        ByteArrayOutputStream main = new ByteArrayOutputStream(); vint(main,1); vint(main,0); vint(main,mainFlags); block(out,main.toByteArray());
        ByteArrayOutputStream file = new ByteArrayOutputStream(); vint(file,2); vint(file,extra == null ? 2 : 3);
        if (extra != null) vint(file,extra.length);
        vint(file,data.length); vint(file, (mode & 0170000) == 0040000 ? 1 : 4);
        vint(file,data.length); vint(file,mode);
        if ((mode & 0170000) != 0040000) { CRC32 crc = new CRC32(); crc.update(data); u32(file,zeroCrc ? 0 : crc.getValue()); }
        vint(file,((long)dictionaryExponent << 10) | (dictionaryExponent == 0 ? 0 : 3 << 7)); vint(file,1); byte[] n = bytes(name); vint(file,n.length); file.write(n);
        if (extra != null) file.write(extra);
        block(out,file.toByteArray()); out.write(data); block(out,new byte[]{5,0,0}); return out.toByteArray();
    }
}
