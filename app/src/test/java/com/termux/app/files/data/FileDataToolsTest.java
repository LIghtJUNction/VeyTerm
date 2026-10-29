package com.termux.app.files.data;

import com.termux.app.files.fs.FileAccess;
import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;
import com.termux.app.files.fs.FileOperations;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class FileDataToolsTest {
    private static final File SCRATCH = new File("build/file-data-tests").getAbsoluteFile();
    private static final String MD5_ABC = "900150983cd24fb0d6963f7d28e17f72";
    private static final String SHA1_ABC = "a9993e364706816aba3e25717850c26c9cd0d89d";
    private static final String SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    @Rule public TemporaryFolder temporary = new TemporaryFolder(SCRATCH);
    private Path root, target;
    private PlainFiles fs;

    @BeforeClass public static void prepareScratch() throws IOException { Files.createDirectories(SCRATCH.toPath()); }
    @Before public void setup() throws IOException {
        root = temporary.newFolder("volume").toPath();
        target = Files.createDirectory(root.resolve("target"));
        fs = new PlainFiles(root, false);
    }
    private Path file(String name, byte[] bytes) throws IOException { return Files.write(root.resolve(name), bytes); }
    private Path text(String name, String text) throws IOException { return file(name, text.getBytes(StandardCharsets.UTF_8)); }
    private FileLocation at(Path path) { return fs.at(path); }
    private List<FileLocation> split(Path source, long bytes) throws IOException {
        return FileDataTools.split(fs, at(source), at(target), "part", bytes, FileOperations.NONE);
    }
    private String verify(Path manifest) throws IOException { return FileDataTools.verifyManifest(fs, at(manifest), FileOperations.NONE); }
    private void targetNames(String... expected) throws IOException {
        List<String> actual = new ArrayList<>();
        try (Stream<Path> paths = Files.list(target)) { paths.forEach(p -> actual.add(p.getFileName().toString())); }
        Collections.sort(actual);
        List<String> wanted = new ArrayList<>(Arrays.asList(expected)); Collections.sort(wanted);
        assertEquals(wanted, actual);
    }
    private static byte[] binary(int size) { byte[] value = new byte[size]; new Random(7391).nextBytes(value); return value; }

    @Test public void binaryRoundtripCoversEmptyExactBlockAndRemainder() throws Exception {
        int[] sizes = {0, 1, 7, 8, 9, 65536, 131072, 200005};
        for (int size : sizes) {
            byte[] original = binary(size);
            Path input = file("input-" + size, original);
            Path output = Files.createDirectory(root.resolve("out-" + size));
            int block = size < 20 ? 8 : 65536;
            List<FileLocation> parts = FileDataTools.split(fs, at(input), at(output), "中文 part", block, null);
            assertEquals(Math.max(1, (size + block - 1) / block), parts.size());
            for (int i = 0; i < parts.size(); i++) {
                assertEquals(String.format(Locale.ROOT, "中文 part.%03d", i + 1), fs.stat(parts.get(i)).name);
                assertTrue(fs.stat(parts.get(i)).size <= block);
            }
            FileLocation joined = FileDataTools.merge(fs, parts, at(output), "joined.bin", null);
            assertArrayEquals(original, Files.readAllBytes(fs.path(joined)));
            assertArrayEquals(original, Files.readAllBytes(input));
        }
    }

    @Test public void singleBytePartsAndLongPartSizePreserveUnsignedBytes() throws Exception {
        Path input = file("binary", new byte[]{0, -1, -128, 13, 10});
        List<FileLocation> parts = split(input, 1);
        assertEquals(5, parts.size());
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(fs.path(
            FileDataTools.merge(fs, parts, at(target), "one", FileOperations.NONE))));
        List<FileLocation> huge = FileDataTools.split(fs, at(input), at(target), "huge", Long.MAX_VALUE, FileOperations.NONE);
        assertEquals(1, huge.size());
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(fs.path(huge.get(0))));
    }

    @Test public void mergeKeepsCallerOrderIncludingRepeatedAndEmptySources() throws Exception {
        Path a = text("a", "alpha"), b = text("b", "beta"), empty = text("empty", "");
        FileLocation joined = FileDataTools.merge(fs, Arrays.asList(at(b), at(empty), at(a), at(b)), at(target), "joined", FileOperations.NONE);
        assertEquals("betaalphabeta", new String(Files.readAllBytes(fs.path(joined)), StandardCharsets.UTF_8));
    }

    @Test public void invalidRequestsCreateNothing() throws Exception {
        Path input = text("input", "abc");
        for (String name : new String[]{"", ".", "..", "../bad", "a/b", "a\\b", "a\nb", "a\0b"}) {
            assertThrows(IOException.class, () -> FileDataTools.split(fs, at(input), at(target), name, 1, FileOperations.NONE));
            assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(input)), at(target), name, FileOperations.NONE));
        }
        assertThrows(IOException.class, () -> split(input, 0));
        assertThrows(IOException.class, () -> split(input, -1));
        assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.emptyList(), at(target), "joined", FileOperations.NONE));
        assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(root)), at(target), "joined", FileOperations.NONE));
        assertThrows(IOException.class, () -> FileDataTools.split(fs, at(input), at(input), "part", 1, FileOperations.NONE));
        targetNames(); assertEquals(0, fs.temporaries);
    }

    @Test public void existingFirstSplitTargetIsNeverOverwritten() throws Exception {
        Path input = text("input", "abcdef"); Files.write(target.resolve("part.001"), binary(11));
        byte[] original = Files.readAllBytes(target.resolve("part.001"));
        IOException error = assertThrows(IOException.class, () -> split(input, 3));
        assertTrue(error.getMessage(), error.getMessage().contains("completed outputs (0): []"));
        assertArrayEquals(original, Files.readAllBytes(target.resolve("part.001")));
        targetNames("part.001"); assertEquals(0, fs.temporaries);
    }

    @Test public void laterConflictPreservesCompletedPartsAndForeignTemporary() throws Exception {
        Path input = text("input", "abcdef");
        Files.write(target.resolve("part.002"), new byte[]{99});
        Files.write(target.resolve(".veyterm-copy-unrelated"), new byte[]{88});
        IOException error = assertThrows(IOException.class, () -> split(input, 3));
        assertTrue(error.getMessage(), error.getMessage().contains(at(target.resolve("part.001")).uri));
        assertArrayEquals(new byte[]{99}, Files.readAllBytes(target.resolve("part.002")));
        assertEquals("abc", new String(Files.readAllBytes(target.resolve("part.001")), StandardCharsets.UTF_8));
        targetNames("part.001", "part.002", ".veyterm-copy-unrelated");
    }

    @Test public void existingMergeTargetIncludingSourceIsPreserved() throws Exception {
        Path input = text("input", "abc"); Files.write(target.resolve("joined"), new byte[]{90});
        assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(input)), at(target), "joined", FileOperations.NONE));
        assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(input)), at(root), "input", FileOperations.NONE));
        assertArrayEquals(new byte[]{90}, Files.readAllBytes(target.resolve("joined")));
        assertEquals("abc", new String(Files.readAllBytes(input), StandardCharsets.UTF_8));
        assertEquals(0, fs.temporaries);
    }

    @Test public void targetAppearingDuringWriteIsPreservedAndOwnTemporaryRemoved() throws Exception {
        Path input = file("input", binary(90000));
        FileOperations.Monitor concurrent = new ActionMonitor() {
            @Override void act(String name, long bytes, long files) throws IOException {
                if (bytes > 0 && !Files.exists(target.resolve("joined"))) Files.write(target.resolve("joined"), new byte[]{42});
            }
        };
        assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(input)), at(target), "joined", concurrent));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.resolve("joined")));
        targetNames("joined");
    }

    @Test public void backendRenameConflictAlsoCleansOnlyOwnedTemporary() throws Exception {
        Path input = text("input", "abc");
        fs = new PlainFiles(root, false) {
            @Override public FileLocation rename(FileLocation from, String name) throws IOException {
                Files.write(target.resolve(name), new byte[]{17});
                return super.rename(from, name);
            }
        };
        assertThrows(IOException.class, () -> split(input, 8));
        targetNames("part.001");
        assertArrayEquals(new byte[]{17}, Files.readAllBytes(target.resolve("part.001")));
    }

    @Test public void cancellationBeforeStartCreatesNothing() throws Exception {
        Path input = text("input", "abc");
        FileOperations.Monitor cancelled = new CancelAfter(0, 0);
        InterruptedIOException error = assertThrows(InterruptedIOException.class,
            () -> FileDataTools.split(fs, at(input), at(target), "part", 2, cancelled));
        assertTrue(error.getMessage(), error.getMessage().contains("completed outputs (0)"));
        assertThrows(InterruptedIOException.class,
            () -> FileDataTools.merge(fs, Collections.singletonList(at(input)), at(target), "joined", cancelled));
        assertThrows(InterruptedIOException.class, () -> FileDataTools.verifyManifest(fs, at(input), cancelled));
        targetNames();
    }

    @Test public void midPartCancellationCleansTemporaryAndReportsNoOutputs() throws Exception {
        Path input = file("input", binary(180000));
        InterruptedIOException error = assertThrows(InterruptedIOException.class,
            () -> FileDataTools.split(fs, at(input), at(target), "part", 150000, new CancelAfter(2, Long.MAX_VALUE)));
        assertTrue(error.getMessage(), error.getMessage().contains("completed outputs (0): []"));
        targetNames(); assertTrue(Files.exists(input));
    }

    @Test public void cancellationInSecondPartRetainsFirstAndListsIt() throws Exception {
        Path input = file("input", binary(180000));
        InterruptedIOException error = assertThrows(InterruptedIOException.class,
            () -> FileDataTools.split(fs, at(input), at(target), "part", 65536, new CancelAfter(65537, Long.MAX_VALUE)));
        targetNames("part.001");
        assertTrue(error.getMessage(), error.getMessage().contains(at(target.resolve("part.001")).uri));
        assertArrayEquals(Arrays.copyOf(Files.readAllBytes(input), 65536), Files.readAllBytes(target.resolve("part.001")));
    }

    @Test public void cancellationAtCompletedBoundaryDoesNotRemovePublishedPart() throws Exception {
        Path input = file("input", binary(100));
        assertThrows(InterruptedIOException.class,
            () -> FileDataTools.split(fs, at(input), at(target), "part", 10, new CancelAfter(Long.MAX_VALUE, 1)));
        targetNames("part.001");
    }

    @Test public void mergeCancellationDiscardsIncompleteOutput() throws Exception {
        Path input = file("input", binary(180000));
        assertThrows(InterruptedIOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(input)),
            at(target), "joined", new CancelAfter(1, Long.MAX_VALUE)));
        targetNames(); assertEquals(180000, Files.size(input));
    }

    @Test public void failedSecondRenameRetainsCompletedSplitAndCleansIncomplete() throws Exception {
        Path input = text("input", "abcdef");
        fs = new PlainFiles(root, false) {
            @Override public FileLocation rename(FileLocation from, String name) throws IOException {
                if (name.equals("part.002")) throw new IOException("injected rename failure");
                return super.rename(from, name);
            }
        };
        IOException error = assertThrows(IOException.class, () -> split(input, 3));
        assertTrue(error.getMessage(), error.getMessage().contains("injected rename failure"));
        assertTrue(error.getMessage(), error.getMessage().contains("completed outputs (1)"));
        targetNames("part.001");
    }

    @Test public void readFailureAfterFirstPartKeepsOnlyCompletedOutput() throws Exception {
        Path input = file("input", binary(90000));
        fs = new PlainFiles(root, false) {
            @Override public InputStream read(FileLocation p) throws IOException {
                return new FilterInputStream(super.read(p)) {
                    int left = 65538;
                    @Override public int read() throws IOException { if (left-- <= 0) throw new IOException("injected read failure"); return super.read(); }
                    @Override public int read(byte[] b, int off, int length) throws IOException {
                        if (left <= 0) throw new IOException("injected read failure");
                        int count = super.read(b, off, Math.min(left, length));
                        if (count > 0) left -= count;
                        return count;
                    }
                };
            }
        };
        IOException error = assertThrows(IOException.class, () -> split(input, 65536));
        assertTrue(error.getMessage(), error.getMessage().contains("injected read failure"));
        targetNames("part.001");
    }

    @Test public void failedOutputCloseDoesNotPublishOrLeaveTemporary() throws Exception {
        Path input = text("input", "abc");
        fs = new PlainFiles(root, false) {
            @Override public OutputStream write(FileLocation p, boolean replace) throws IOException {
                return new FilterOutputStream(super.write(p, replace)) {
                    @Override public void close() throws IOException { super.close(); throw new IOException("injected close failure"); }
                };
            }
        };
        IOException error = assertThrows(IOException.class, () -> split(input, 3));
        assertTrue(error.getMessage(), error.getMessage().contains("injected close failure")); targetNames();
        assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(input)), at(target), "joined", FileOperations.NONE));
        targetNames();
    }

    @Test public void cleanupFailureIsSuppressedRatherThanMaskingWriteFailure() throws Exception {
        Path input = text("input", "abc");
        fs = new PlainFiles(root, false) {
            @Override public OutputStream write(FileLocation p, boolean replace) throws IOException { throw new IOException("write broke"); }
            @Override public void delete(FileLocation p) throws IOException { throw new IOException("cleanup broke"); }
        };
        IOException error = assertThrows(IOException.class, () -> split(input, 3));
        assertTrue(error.getMessage(), error.getMessage().contains("write broke"));
        assertEquals("cleanup broke", error.getCause().getSuppressed()[0].getMessage());
        // TemporaryFolder removes this deliberately unremovable fixture after the test.
    }

    @Test public void sizeChangeDuringSplitIsRejectedBeforePublishingPart() throws Exception {
        Path input = file("input", binary(90000));
        FileOperations.Monitor modify = new ActionMonitor() {
            boolean changed;
            @Override void act(String name, long bytes, long files) throws IOException {
                if (!changed && bytes > 0) { Files.write(input, new byte[]{1}, StandardOpenOption.APPEND); changed = true; }
            }
        };
        IOException error = assertThrows(IOException.class,
            () -> FileDataTools.split(fs, at(input), at(target), "part", 65536, modify));
        assertTrue(error.getMessage(), error.getMessage().contains("Source changed")); targetNames();
    }

    @Test public void sameSizeMtimeChangeDuringSplitIsRejected() throws Exception {
        Path input = file("input", binary(90000)); long stamp = Files.getLastModifiedTime(input).toMillis();
        FileOperations.Monitor modify = new ActionMonitor() {
            @Override void act(String name, long bytes, long files) throws IOException {
                if (bytes > 0) Files.setLastModifiedTime(input, FileTime.fromMillis(stamp + 10000));
            }
        };
        assertThrows(IOException.class, () -> FileDataTools.split(fs, at(input), at(target), "part", 65536, modify)); targetNames();
    }

    @Test public void mergeRechecksEarlierSourcesAfterReadingLaterSources() throws Exception {
        Path first = text("first", "abc"), second = file("second", binary(90000));
        long stamp = Files.getLastModifiedTime(first).toMillis();
        FileOperations.Monitor modify = new ActionMonitor() {
            @Override void act(String name, long bytes, long files) throws IOException {
                if (name.equals("second")) Files.setLastModifiedTime(first, FileTime.fromMillis(stamp + 10000));
            }
        };
        IOException error = assertThrows(IOException.class, () -> FileDataTools.merge(fs,
            Arrays.asList(at(first), at(second)), at(target), "joined", modify));
        assertTrue(error.getMessage(), error.getMessage().contains("Source changed")); targetNames();
    }

    @Test public void shortStreamWithUnchangedMetadataIsRejected() throws Exception {
        Path input = text("input", "abcdef");
        fs = new PlainFiles(root, false) {
            @Override public InputStream read(FileLocation p) { return new ByteArrayInputStream(new byte[]{1, 2}); }
        };
        assertThrows(IOException.class, () -> split(input, 8)); targetNames();
        assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(input)), at(target), "joined", FileOperations.NONE)); targetNames();
    }

    @Test public void opaqueProviderLocationsUseCreatedTemporaryAndReturnedRenameLocation() throws Exception {
        Path input = file("input", binary(140000));
        fs = new PlainFiles(root, true) {
            @Override public boolean exists(FileLocation p) { return !path(p).getFileName().toString().startsWith(".provider-") && super.exists(p); }
        };
        List<FileLocation> parts = split(input, 65536);
        assertFalse(parts.get(0).isLocal()); assertEquals(3, fs.temporaries);
        FileLocation joined = FileDataTools.merge(fs, parts, at(target), "joined", FileOperations.NONE);
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(fs.path(joined)));
        Path manifest = text("checksums", SHA256_ABC + "  nested/a\n");
        Files.createDirectory(root.resolve("nested")); text("nested/a", "abc");
        assertTrue(verify(manifest).contains("1 OK"));
        assertThrows(InterruptedIOException.class, () -> FileDataTools.merge(fs, parts, at(target), "cancelled", new CancelAfter(1, Long.MAX_VALUE)));
        targetNames("part.001", "part.002", "part.003", "joined");
    }

    @Test public void zeroAndShortReadsStillProduceFullBinaryRoundtripAndDigest() throws Exception {
        Path input = file("input", binary(130007));
        fs = new PlainFiles(root, false) {
            @Override public InputStream read(FileLocation p) throws IOException {
                return new FilterInputStream(super.read(p)) {
                    boolean zero;
                    @Override public int read(byte[] b, int off, int length) throws IOException {
                        zero = !zero; return zero ? 0 : super.read(b, off, Math.min(7, length));
                    }
                };
            }
        };
        List<FileLocation> parts = split(input, 65536);
        FileLocation joined = FileDataTools.merge(fs, parts, at(target), "joined", FileOperations.NONE);
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(fs.path(joined)));
        Path manifest = text("sha256", hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))) + " *input\n");
        assertTrue(verify(manifest).contains("1 OK"));
    }

    @Test public void manifestKnownVectorsSupportSfvGnuBomCrlfSpacesAndUnicode() throws Exception {
        text("中文 data.bin", "abc"); text("empty", ""); Files.createDirectory(root.resolve("nested")); text("nested/a", "abc");
        Path manifest = text("manifest.without-extension", "\ufeff; SFV comment\r\n# another comment\r\n\r\n"
            + "中文 data.bin 352441C2\r\n" + MD5_ABC.toUpperCase(Locale.ROOT) + " *中文 data.bin\r\n"
            + SHA1_ABC + "  ./nested/a\r\n" + SHA256_ABC + "  中文 data.bin\r\n"
            + "d41d8cd98f00b204e9800998ecf8427e  empty");
        String report = verify(manifest);
        assertTrue(report, report.startsWith("Verified 5 entries: 5 OK, 0 mismatched, 0 missing, 0 errors."));
        assertTrue(report, report.contains("OK: 中文 data.bin"));
    }

    @Test public void manifestReportsMismatchMissingAndUnreadableWithoutSuccessClaim() throws Exception {
        Path data = text("a", "abc"), blocked = text("blocked", "abc");
        fs = new PlainFiles(root, false) {
            @Override public InputStream read(FileLocation p) throws IOException {
                if (path(p).equals(blocked)) throw new IOException("permission denied fixture");
                return super.read(p);
            }
        };
        Path manifest = text("manifest", MD5_ABC + "  a\n" + "00000000000000000000000000000000 *a\n"
            + SHA1_ABC + "  missing\n" + SHA256_ABC + "  blocked\n");
        String report = verify(manifest);
        assertTrue(report, report.startsWith("Verified 4 entries: 1 OK, 1 mismatched, 1 missing, 1 errors."));
        assertTrue(report, report.contains("MISMATCH: a (MD5) expected 00000000000000000000000000000000, actual " + MD5_ABC));
        assertTrue(report, report.contains("MISSING: missing"));
        assertTrue(report, report.contains("ERROR: blocked: permission denied fixture"));
        assertEquals(3, Files.size(data));
    }

    @Test public void manifestTraversalAbsoluteUriAndWindowsPathsNeverReadTargets() throws Exception {
        text("outside", "abc");
        for (String path : new String[]{"../outside", "./../outside", "/outside", "sub/../outside", "C:/outside",
                "..\\outside", "file:///outside", "content://provider/secret", "sub//outside", "sub/./outside", "sub/", "sub/\0outside"}) {
            Path manifest = text("manifest", MD5_ABC + "  " + path + "\n");
            fs.reads.clear();
            IOException failure = assertThrows(IOException.class, () -> verify(manifest));
            assertTrue(failure.getMessage(), failure.getMessage().contains("Unsafe manifest path"));
            assertEquals(Collections.singletonList(manifest), fs.reads);
        }
    }

    @Test public void manifestRejectsSymlinkFilesAndAncestorDirectories() throws Exception {
        Path outside = temporary.newFolder("outside").toPath(); Files.write(outside.resolve("a"), new byte[]{1});
        Files.createSymbolicLink(root.resolve("link-dir"), outside);
        Files.createSymbolicLink(root.resolve("link-file"), outside.resolve("a"));
        Files.createSymbolicLink(root.resolve("inside-link"), text("inside", "abc"));
        for (String path : new String[]{"link-dir/a", "link-file", "inside-link"}) {
            Path manifest = text("manifest", MD5_ABC + "  " + path + "\n");
            fs.reads.clear(); assertThrows(IOException.class, () -> verify(manifest));
            assertEquals(Collections.singletonList(manifest), fs.reads);
        }
    }

    @Test public void manifestRejectsBackendChildEscapingContainment() throws Exception {
        Path outside = temporary.newFolder("outside").toPath().resolve("secret"); Files.write(outside, new byte[]{1});
        Path manifest = text("manifest", MD5_ABC + "  safe-looking\n");
        fs = new PlainFiles(root, false) {
            @Override public FileLocation child(FileLocation directory, String name) { return at(outside); }
        };
        assertThrows(IOException.class, () -> verify(manifest));
        assertEquals(Collections.singletonList(manifest), fs.reads);
    }

    @Test public void malformedEmptyHugeAndInvalidUtf8ManifestsAreErrors() throws Exception {
        for (String text : new String[]{"", "; comments only\n", "no checksum here\n", "abcd  a\n",
                SHA256_ABC + " a\n", "a" + new String(new char[65500]).replace('\0', ' '),
                new String(new char[65537]).replace('\0', 'x')}) {
            Path manifest = text("manifest", text);
            assertThrows(IOException.class, () -> verify(manifest));
        }
        Path invalidUtf8 = file("invalid", new byte[]{(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> verify(invalidUtf8)); targetNames();
    }

    @Test public void gnuEscapedMarkerIsParsedAndUnsafeDecodedNamesRejected() throws Exception {
        text("a", "abc");
        assertTrue(verify(text("manifest", "\\" + MD5_ABC + "  a\n")).contains("1 OK"));
        for (String name : new String[]{"a\\nsecret", "a\\rsecret", "a\\\\secret", "a\\qsecret", "a\\"}) {
            Path manifest = text("manifest", "\\" + MD5_ABC + "  " + name + "\n");
            assertThrows(IOException.class, () -> verify(manifest));
        }
    }

    @Test public void manifestVerificationCancellationStopsWithoutReturningReport() throws Exception {
        file("a", binary(150000));
        Path manifest = text("manifest", SHA256_ABC + "  a\n");
        assertThrows(InterruptedIOException.class,
            () -> FileDataTools.verifyManifest(fs, at(manifest), new CancelAfter(1, Long.MAX_VALUE)));
        assertEquals(150000, Files.size(root.resolve("a"))); targetNames();
    }

    @Test public void checksumSourceMutationIsAnErrorAndManifestMutationThrows() throws Exception {
        Path data = text("a", "abc"); Path manifest = text("manifest", MD5_ABC + "  a\n");
        long dataStamp = Files.getLastModifiedTime(data).toMillis();
        FileOperations.Monitor changeData = new ActionMonitor() {
            @Override void act(String name, long bytes, long files) throws IOException {
                Files.setLastModifiedTime(data, FileTime.fromMillis(dataStamp + 10000));
            }
        };
        String report = FileDataTools.verifyManifest(fs, at(manifest), changeData);
        assertTrue(report, report.contains("0 OK, 0 mismatched, 0 missing, 1 errors"));
        assertTrue(report, report.contains("Source changed"));
        long manifestStamp = Files.getLastModifiedTime(manifest).toMillis();
        FileOperations.Monitor changeManifest = new ActionMonitor() {
            @Override void act(String name, long bytes, long files) throws IOException {
                Files.setLastModifiedTime(manifest, FileTime.fromMillis(manifestStamp + 10000));
            }
        };
        assertThrows(IOException.class, () -> FileDataTools.verifyManifest(fs, at(manifest), changeManifest));
    }

    @Test public void reportBoundsDetailsButCountsEveryEntry() throws Exception {
        String filename = String.join("", Collections.nCopies(200, "a")); text(filename, "abc");
        StringBuilder manifest = new StringBuilder();
        for (int i = 0; i < 1400; i++) manifest.append(MD5_ABC).append("  ").append(filename).append('\n');
        String report = verify(text("manifest", manifest.toString()));
        assertTrue(report, report.startsWith("Verified 1400 entries: 1400 OK"));
        assertTrue(report, report.contains("Further details omitted"));
        assertTrue(report.length() < 263000);
    }

    @Test public void danglingTargetSymlinkAndSymlinkInputsAreNotReplacedOrFollowed() throws Exception {
        Path input = text("a", "abc");
        Files.createSymbolicLink(target.resolve("part.001"), root.resolve("missing"));
        assertThrows(IOException.class, () -> split(input, 3));
        assertTrue(Files.isSymbolicLink(target.resolve("part.001")));
        Path link = Files.createSymbolicLink(root.resolve("link"), input);
        assertThrows(IOException.class, () -> split(link, 3));
        assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(link)), at(target), "joined", FileOperations.NONE));
        assertThrows(IOException.class, () -> verify(link));
        Path directoryLink = Files.createSymbolicLink(root.resolve("directory-link"), target);
        assertThrows(IOException.class, () -> FileDataTools.merge(fs, Collections.singletonList(at(input)), at(directoryLink), "joined", FileOperations.NONE));
        targetNames("part.001");
    }

    @Test public void sourceChangedAfterPublishedPartIsReportedWithRetainedOutput() throws Exception {
        Path input = file("input", binary(100));
        long stamp = Files.getLastModifiedTime(input).toMillis();
        FileOperations.Monitor modify = new ActionMonitor() {
            @Override void act(String name, long bytes, long files) throws IOException {
                if (files == 1) Files.setLastModifiedTime(input, FileTime.fromMillis(stamp + 10000));
            }
        };
        IOException error = assertThrows(IOException.class,
            () -> FileDataTools.split(fs, at(input), at(target), "part", 10, modify));
        assertTrue(error.getMessage(), error.getMessage().contains("Source changed"));
        assertTrue(error.getMessage(), error.getMessage().contains("completed outputs (1): [" + at(target.resolve("part.001")).uri));
        targetNames("part.001");
    }

    @Test public void inputCloseFailureReportsAlreadyPublishedSplitWithoutRemovingIt() throws Exception {
        Path input = text("input", "abc");
        fs = new PlainFiles(root, false) {
            @Override public InputStream read(FileLocation p) throws IOException {
                return new FilterInputStream(super.read(p)) {
                    @Override public void close() throws IOException { super.close(); throw new IOException("input close failed"); }
                };
            }
        };
        IOException error = assertThrows(IOException.class, () -> split(input, 4));
        assertTrue(error.getMessage(), error.getMessage().contains("input close failed"));
        assertTrue(error.getMessage(), error.getMessage().contains("completed outputs (1)"));
        targetNames("part.001");
        assertThrows(IOException.class,
            () -> FileDataTools.merge(fs, Collections.singletonList(at(input)), at(target), "joined", FileOperations.NONE));
        targetNames("part.001");
    }

    @Test public void manifestTruncatedStreamCannotSilentlySkipEntries() throws Exception {
        text("a", "abc");
        String entry = MD5_ABC + "  a\n";
        Path manifest = text("manifest", entry + entry);
        fs = new PlainFiles(root, false) {
            @Override public InputStream read(FileLocation p) throws IOException {
                if (path(p).equals(manifest)) return new ByteArrayInputStream(entry.getBytes(StandardCharsets.UTF_8));
                return super.read(p);
            }
        };
        IOException error = assertThrows(IOException.class, () -> verify(manifest));
        assertTrue(error.getMessage(), error.getMessage().contains("incomplete bytes"));
    }

    @Test public void unknownMetadataStillStreamsAndChecksKnownFingerprintFields() throws Exception {
        Path input = file("input", binary(150003));
        fs = new PlainFiles(root, false) {
            @Override public FileEntry stat(FileLocation p) throws IOException {
                FileEntry entry = super.stat(p);
                return entry.directory ? entry : new FileEntry(p, entry.name, false, entry.symbolicLink, -1, 0);
            }
        };
        List<FileLocation> parts = split(input, 65536);
        FileLocation joined = FileDataTools.merge(fs, parts, at(target), "joined", FileOperations.NONE);
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(fs.path(joined)));
    }

    @Test public void manifestFilenamesWithPercentCharactersRemainLiteral() throws Exception {
        text("%2e%2e", "abc");
        assertTrue(verify(text("manifest", MD5_ABC + "  %2e%2e\n")).contains("1 OK"));
        assertTrue(verify(text("manifest", "empty 00000000\n" + MD5_ABC + "  %2e%2e\n")).contains("1 missing"));
        text("empty", "");
        assertTrue(verify(text("manifest", "empty 00000000\n")).contains("1 OK"));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private abstract static class ActionMonitor implements FileOperations.Monitor {
        IOException failure;
        abstract void act(String name, long bytes, long files) throws IOException;
        @Override public void checkCancelled() throws InterruptedIOException {
            if (failure != null) { InterruptedIOException error = new InterruptedIOException("Fixture action failed"); error.initCause(failure); throw error; }
        }
        @Override public void progress(String name, long bytes, long files) {
            try { act(name, bytes, files); } catch (IOException e) { failure = e; }
        }
    }

    private static final class CancelAfter implements FileOperations.Monitor {
        final long byteLimit, fileLimit;
        long bytes, files;
        CancelAfter(long bytes, long files) { byteLimit = bytes; fileLimit = files; }
        @Override public void checkCancelled() throws InterruptedIOException {
            if (bytes >= byteLimit || files >= fileLimit) throw new InterruptedIOException("cancelled fixture");
        }
        @Override public void progress(String name, long bytes, long files) { this.bytes = bytes; this.files = files; }
    }

    /** Real NIO files, optionally exposed with opaque, non-hierarchical provider URIs. */
    private static class PlainFiles implements FileAccess {
        final Path root;
        final boolean opaque;
        final Map<Path, FileLocation> locations = new HashMap<>();
        final Map<FileLocation, Path> paths = new HashMap<>();
        final List<Path> reads = new ArrayList<>();
        int temporaries;
        PlainFiles(Path root, boolean opaque) { this.root = root; this.opaque = opaque; }
        FileLocation at(Path path) {
            if (!opaque) return FileLocation.local(path.toFile());
            FileLocation existing = locations.get(path);
            if (existing != null) return existing;
            FileLocation created = new FileLocation("fixture://volume/document/" + locations.size());
            locations.put(path, created); paths.put(created, path); return created;
        }
        Path path(FileLocation location) { return opaque ? paths.get(location) : location.file().toPath(); }
        @Override public FileEntry stat(FileLocation p) throws IOException {
            Path path = path(p);
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new FileEntry(p, path.getFileName().toString(), attrs.isDirectory(), attrs.isSymbolicLink(), attrs.size(), attrs.lastModifiedTime().toMillis());
        }
        @Override public List<FileEntry> list(FileLocation p) throws IOException {
            List<FileEntry> result = new ArrayList<>();
            try (Stream<Path> children = Files.list(path(p))) {
                for (Path child : (Iterable<Path>) children::iterator) result.add(stat(at(child)));
            }
            return result;
        }
        @Override public FileLocation parent(FileLocation p) { Path parent = path(p).getParent(); return parent == null ? null : at(parent); }
        @Override public FileLocation child(FileLocation p, String name) { FileLocation.validateName(name); return at(path(p).resolve(name)); }
        @Override public InputStream read(FileLocation p) throws IOException { reads.add(path(p)); return Files.newInputStream(path(p)); }
        @Override public OutputStream write(FileLocation p, boolean replace) throws IOException {
            return Files.newOutputStream(path(p), replace
                ? new StandardOpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING}
                : new StandardOpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW});
        }
        @Override public FileLocation createTemporary(FileLocation directory) throws IOException {
            temporaries++;
            return at(Files.createTempFile(path(directory), ".provider-", ".stage"));
        }
        @Override public void mkdir(FileLocation p) throws IOException { Files.createDirectory(path(p)); }
        @Override public FileLocation rename(FileLocation p, String name) throws IOException {
            FileLocation result = child(parent(p), name);
            Files.move(path(p), path(result)); return result;
        }
        @Override public void delete(FileLocation p) throws IOException { Files.delete(path(p)); }
        @Override public boolean exists(FileLocation p) { return Files.exists(path(p), LinkOption.NOFOLLOW_LINKS); }
        @Override public boolean contains(FileLocation directory, FileLocation p) throws IOException {
            return path(p).toFile().getCanonicalFile().toPath().startsWith(path(directory).toFile().getCanonicalFile().toPath());
        }
    }
}
