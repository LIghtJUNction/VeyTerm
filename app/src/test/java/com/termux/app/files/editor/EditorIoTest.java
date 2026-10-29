package com.termux.app.files.editor;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.Assert.*;

public class EditorIoTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File file(byte[] bytes) throws IOException { File file = temporary.newFile(); Files.write(file.toPath(), bytes); return file; }
    private File text(String value) throws IOException { return file(value.getBytes(StandardCharsets.UTF_8)); }
    private byte[] bytes(File file) throws IOException { return Files.readAllBytes(file.toPath()); }
    private String value(File file) throws IOException { return new String(bytes(file), StandardCharsets.UTF_8); }
    private static String repeat(String value, int count) { StringBuilder out = new StringBuilder(); for (int i = 0; i < count; i++) out.append(value); return out.toString(); }

    @Test public void detectsAndRoundTripsEveryBom() throws Exception {
        for (String name : new String[]{"UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"}) {
            EditorIo.Encoding encoding = EditorIo.encoding(name, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(encoding.bom); out.write(EditorIo.encode("猫\r\n😀", encoding.charset));
            File file = file(out.toByteArray()); EditorIo.Encoding found = EditorIo.detectEncoding(file);
            assertEquals(name, found.charset.name()); assertArrayEquals(encoding.bom, found.bom);
            EditorIo.TextPage page = EditorIo.readTextPage(file, found, found.bom.length, 32);
            assertEquals("猫\r\n😀", page.text);
            byte[] original = bytes(file);
            EditorIo.saveTextPage(EditorIo.snapshot(file), page, found, page.text, found, EditorIo.LineEnding.PRESERVE, null);
            assertArrayEquals(original, bytes(file));
        }
    }
    @Test public void defaultEncodingDoesNotGuessMalformedUtf8() throws Exception {
        File file = file(new byte[]{(byte) 0xff, 65});
        EditorIo.Encoding encoding = EditorIo.detectEncoding(file);
        assertEquals("UTF-8", encoding.charset.name());
        assertThrows(IOException.class, () -> EditorIo.readTextPage(file, encoding, 0, 16));
        assertEquals("ÿA", EditorIo.readTextPage(file, EditorIo.encoding("ISO-8859-1", false), 0, 16).text);
    }
    @Test public void rejectsAmbiguousByteOrderAndUnsupportedBom() {
        assertThrows(IOException.class, () -> EditorIo.encoding("UTF-16", true));
        assertThrows(IOException.class, () -> EditorIo.encoding("ISO-8859-1", true));
        assertThrows(IOException.class, () -> EditorIo.encoding("not-real", false));
    }
    @Test public void pagesReassembleUtf8WithoutDroppingCharacters() throws Exception {
        String original = repeat("a猫😀\r\nb\rc\n", 8000); File file = text(original);
        EditorIo.Encoding encoding = EditorIo.detectEncoding(file); long offset = 0;
        StringBuilder read = new StringBuilder();
        while (offset < file.length()) {
            EditorIo.TextPage page = EditorIo.readTextPage(file, encoding, offset, 101);
            assertTrue(page.end > offset); assertTrue(page.end - page.start <= 101);
            read.append(page.text); offset = page.end;
        }
        assertEquals(original, read.toString());
    }
    @Test public void pagesReassembleUtf16SurrogatePairs() throws Exception {
        EditorIo.Encoding encoding = EditorIo.encoding("UTF-16LE", false);
        String original = repeat("猫😀abc\r\n", 1000); File file = file(EditorIo.encode(original, encoding.charset));
        long offset = 0; StringBuilder read = new StringBuilder();
        while (offset < file.length()) {
            EditorIo.TextPage page = EditorIo.readTextPage(file, encoding, offset, 17);
            read.append(page.text); offset = page.end;
        }
        assertEquals(original, read.toString());
    }
    @Test public void neverSplitsCrLfAcrossPages() throws Exception {
        File file = text(repeat("a", 15) + "\r\ntail"); EditorIo.Encoding encoding = EditorIo.detectEncoding(file);
        EditorIo.TextPage first = EditorIo.readTextPage(file, encoding, 0, 16);
        assertEquals(15, first.end);
        assertTrue(EditorIo.readTextPage(file, encoding, first.end, 16).text.startsWith("\r\n"));
    }
    @Test public void malformedTrailingCharacterIsAnError() throws Exception {
        File file = file(new byte[]{65, (byte) 0xe2, (byte) 0x82});
        assertThrows(IOException.class, () -> EditorIo.readTextPage(file, EditorIo.detectEncoding(file), 0, 16));
    }
    @Test public void middlePageSaveRetainsAllUnseenBytes() throws Exception {
        byte[] original = new byte[350000]; new Random(7).nextBytes(original); File file = file(original);
        byte[] replacement = new byte[]{1, 2, 3};
        EditorIo.saveRange(EditorIo.snapshot(file), 130000, 134096, replacement, null);
        byte[] saved = bytes(file); assertEquals(original.length - 4096 + 3, saved.length);
        assertArrayEquals(Arrays.copyOfRange(original, 0, 130000), Arrays.copyOfRange(saved, 0, 130000));
        assertArrayEquals(replacement, Arrays.copyOfRange(saved, 130000, 130003));
        assertArrayEquals(Arrays.copyOfRange(original, 134096, original.length), Arrays.copyOfRange(saved, 130003, saved.length));
    }
    @Test public void pageEditCanRetainUnseenMalformedBytesExactly() throws Exception {
        byte[] original = new byte[80]; Arrays.fill(original, (byte) 'x'); original[79] = (byte) 0xff; File file = file(original);
        EditorIo.Encoding encoding = EditorIo.detectEncoding(file); EditorIo.TextPage page = EditorIo.readTextPage(file, encoding, 0, 16);
        EditorIo.saveTextPage(EditorIo.snapshot(file), page, encoding, "edited", encoding, EditorIo.LineEnding.PRESERVE, null);
        assertEquals((byte) 0xff, bytes(file)[file.length() > 0 ? (int) file.length() - 1 : 0]);
        assertEquals(70, file.length());
    }
    @Test public void wholeDocumentConversionIncludesUnseenPagesAndAddsBom() throws Exception {
        String original = repeat("head\r\n猫\rnext\n", 17000); File file = text(original);
        EditorIo.Encoding source = EditorIo.detectEncoding(file), target = EditorIo.encoding("UTF-16LE", true);
        EditorIo.TextPage page = EditorIo.readTextPage(file, source, 0, 64);
        String edit = page.text.replace("head", "changed");
        String expected = EditorIo.displayText(edit + original.substring(page.text.length())).replace("\n", "\r\n");
        EditorIo.saveTextPage(EditorIo.snapshot(file), page, source, edit, target, EditorIo.LineEnding.CRLF, null);
        byte[] result = bytes(file); assertArrayEquals(target.bom, Arrays.copyOf(result, 2));
        assertEquals(expected, new String(result, 2, result.length - 2, target.charset));
    }
    @Test public void conversionAllowsCharactersAbsentFromSourceEncoding() throws Exception {
        File file = text("plain ASCII"); EditorIo.Encoding source = EditorIo.encoding("US-ASCII", false), target = EditorIo.encoding("UTF-8", false);
        EditorIo.TextPage page = EditorIo.readTextPage(file, source, 0, 16);
        EditorIo.saveTextPage(EditorIo.snapshot(file), page, source, "你好😀", target, EditorIo.LineEnding.PRESERVE, null);
        assertEquals("你好😀", value(file));
    }
    @Test public void lossySaveIsRejectedAndOriginalSurvives() throws Exception {
        File file = text("猫\n"); byte[] original = bytes(file);
        EditorIo.Encoding source = EditorIo.detectEncoding(file), target = EditorIo.encoding("US-ASCII", false);
        EditorIo.TextPage page = EditorIo.readTextPage(file, source, 0, 16);
        assertThrows(IOException.class, () -> EditorIo.saveTextPage(EditorIo.snapshot(file), page, source, page.text, target, EditorIo.LineEnding.LF, null));
        assertArrayEquals(original, bytes(file)); assertNoTemps(file);
    }
    @Test public void unpairedSurrogateCannotSilentlyDisappearDuringConversion() throws Exception {
        File file = text("original"); EditorIo.Encoding source = EditorIo.detectEncoding(file), target = EditorIo.encoding("UTF-16LE", false);
        EditorIo.TextPage page = EditorIo.readTextPage(file, source, 0, 16);
        assertThrows(IOException.class, () -> EditorIo.saveTextPage(EditorIo.snapshot(file), page, source, "bad\ud800", target, EditorIo.LineEnding.PRESERVE, null));
        assertEquals("original", value(file));
    }
    @Test public void convertingMalformedUnseenSuffixFailsWithoutTruncating() throws Exception {
        File file = file(new byte[]{65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,(byte)0xff});
        byte[] original = bytes(file); EditorIo.Encoding source = EditorIo.detectEncoding(file);
        EditorIo.TextPage page = EditorIo.readTextPage(file, source, 0, 16);
        assertThrows(IOException.class, () -> EditorIo.saveTextPage(EditorIo.snapshot(file), page, source, page.text, EditorIo.encoding("UTF-16LE", true), EditorIo.LineEnding.PRESERVE, null));
        assertArrayEquals(original, bytes(file));
    }
    @Test public void detectsExternalEditEvenWhenSizeAndTimestampAreIdentical() throws Exception {
        File file = text("first"); EditorIo.Snapshot snapshot = EditorIo.snapshot(file);
        Files.write(file.toPath(), "other".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(snapshot.modified));
        assertThrows(EditorIo.ConflictException.class, () -> EditorIo.saveRange(snapshot, 0, 5, new byte[]{1}, null));
        assertEquals("other", value(file)); assertNoTemps(file);
    }
    @Test public void rechecksConflictAfterWritingTemporaryFile() throws Exception {
        File file = text("before"); EditorIo.Snapshot snapshot = EditorIo.snapshot(file);
        assertThrows(EditorIo.ConflictException.class, () -> EditorIo.atomicSave(snapshot, out -> {
            out.write("mine".getBytes(StandardCharsets.UTF_8)); Files.write(file.toPath(), "external".getBytes(StandardCharsets.UTF_8));
        }, null));
        assertEquals("external", value(file)); assertNoTemps(file);
    }
    @Test public void writerFailureLeavesOriginalAndCleansTemporary() throws Exception {
        File file = text("original");
        assertThrows(IOException.class, () -> EditorIo.atomicSave(EditorIo.snapshot(file), out -> { out.write(7); throw new IOException("disk full"); }, null));
        assertEquals("original", value(file)); assertNoTemps(file);
    }
    @Test public void metadataFailureLeavesOriginalAndCleansTemporary() throws Exception {
        File file = text("original");
        assertThrows(IOException.class, () -> EditorIo.atomicSave(EditorIo.snapshot(file), out -> out.write(7), (a, b) -> { throw new IOException("chmod failed"); }));
        assertEquals("original", value(file)); assertNoTemps(file);
    }
    @Test public void snapshotAfterSaveCanBeUsedForNextSave() throws Exception {
        File file = text("abc"); EditorIo.Snapshot saved = EditorIo.saveRange(EditorIo.snapshot(file), 1, 2, new byte[]{'X'}, null);
        EditorIo.saveRange(saved, 2, 3, new byte[]{'Y'}, null); assertEquals("aXY", value(file));
    }
    @Test public void byteSearchCrossesBufferBoundaryAndSupportsOverlap() throws Exception {
        byte[] bytes = new byte[70000]; Arrays.fill(bytes, (byte) 'a');
        System.arraycopy("ababaca".getBytes(StandardCharsets.UTF_8), 0, bytes, 32766, 7); File file = file(bytes);
        assertEquals(32766, EditorIo.findBytes(file, "ababaca".getBytes(StandardCharsets.UTF_8), 0));
        assertEquals(-1, EditorIo.findBytes(file, new byte[]{'z'}, 0));
        assertEquals(32766, EditorIo.findBytes(file, "ababaca".getBytes(StandardCharsets.UTF_8), 32766));
        assertEquals(-1, EditorIo.findBytes(file, "ababaca".getBytes(StandardCharsets.UTF_8), 32767));
    }
    @Test public void literalAndRegexReplacementHaveDistinctDollarSemantics() {
        assertEquals("$1 $1", EditorIo.replace("a.b A.B", "a.b", "$1", false, false));
        assertEquals("b-a", EditorIo.replace("ab", "(a)(b)", "$2-$1", true, true));
        assertThrows(IllegalArgumentException.class, () -> EditorIo.searchPattern("", false, true));
    }
    @Test public void mixedEndingsRoundTripThroughDisplayAndEdits() {
        String original = "a\r\nb\rc\nd";
        assertEquals(original, EditorIo.retainLineEndings(original, EditorIo.displayText(original)));
        assertEquals("A\r\nb\rc\nd", EditorIo.retainLineEndings(original, "A\nb\nc\nd"));
        assertEquals("CRLF 1 / LF 1 / CR 1", EditorIo.lineEndingSummary(original));
    }
    @Test public void jumpLineScansBeyondVisiblePage() throws Exception {
        File file = text(repeat("猫\r\n", 100)); EditorIo.Encoding encoding = EditorIo.detectEncoding(file);
        EditorIo.TextPage page = EditorIo.pageForLine(file, encoding, 75, 31);
        long lines = EditorIo.linesBefore(file, encoding, page.start);
        assertTrue(lines < 75); assertTrue(lines + EditorIo.newlineCount(page.text) >= 74);
        assertThrows(IOException.class, () -> EditorIo.pageForLine(file, encoding, 102, 31));
    }
    @Test public void emptyFileSupportsInsertionAndLineOne() throws Exception {
        File file = text(""); EditorIo.Encoding encoding = EditorIo.detectEncoding(file);
        EditorIo.TextPage page = EditorIo.pageForLine(file, encoding, 1, 16);
        assertEquals("", page.text); EditorIo.saveTextPage(EditorIo.snapshot(file), page, encoding, "first", encoding, EditorIo.LineEnding.PRESERVE, null);
        assertEquals("first", value(file));
    }
    @Test public void hexParsingRejectsPartialAndInvalidBytes() throws Exception {
        assertArrayEquals(new byte[]{0, (byte) 0xff, 16}, EditorIo.parseHex("00 FF\n10"));
        assertThrows(IOException.class, () -> EditorIo.parseHex("F"));
        assertThrows(IOException.class, () -> EditorIo.parseHex("0Z"));
        byte[] bytes = new byte[4096]; new Random(1).nextBytes(bytes); assertArrayEquals(bytes, EditorIo.parseHex(EditorIo.hex(bytes)));
    }
    @Test public void invalidRangesAreRejectedWithoutWriting() throws Exception {
        File file = text("abc"); EditorIo.Snapshot snapshot = EditorIo.snapshot(file);
        assertThrows(IOException.class, () -> EditorIo.saveRange(snapshot, -1, 0, new byte[0], null));
        assertThrows(IOException.class, () -> EditorIo.saveRange(snapshot, 0, 4, new byte[0], null));
        assertThrows(IOException.class, () -> EditorIo.readPage(file, 4, 16));
        assertEquals("abc", value(file));
    }
    @Test public void snapshotCanBeSerializedForUnsavedActivityState() throws Exception {
        File file = text("abc"); ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(data)) { out.writeObject(EditorIo.snapshot(file)); }
        EditorIo.Snapshot restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data.toByteArray()))) { restored = (EditorIo.Snapshot) in.readObject(); }
        EditorIo.verify(restored); Files.write(file.toPath(), new byte[]{1}); assertThrows(EditorIo.ConflictException.class, () -> EditorIo.verify(restored));
    }
    @Test public void bomCanBeAddedAndRemovedWithoutDuplicatingOrDroppingText() throws Exception {
        File file = text("猫\r\ntail"); EditorIo.Encoding plain = EditorIo.encoding("UTF-8", false), bom = EditorIo.encoding("UTF-8", true);
        EditorIo.TextPage page = EditorIo.readTextPage(file, plain, 0, 16);
        EditorIo.Snapshot saved = EditorIo.saveTextPage(EditorIo.snapshot(file), page, plain, page.text, bom, EditorIo.LineEnding.PRESERVE, null);
        assertEquals("\ufeff猫\r\ntail", value(file));
        page = EditorIo.readTextPage(file, bom, 3, 16);
        EditorIo.saveTextPage(saved, page, bom, page.text, plain, EditorIo.LineEnding.PRESERVE, null);
        assertEquals("猫\r\ntail", value(file));
    }
    @Test public void statefulEncodingIsRejectedBeforePagingCanLoseItsState() {
        assertThrows(IOException.class, () -> EditorIo.encoding("ISO-2022-JP", false));
    }
    @Test public void cancellationDuringWriteRetainsOriginal() throws Exception {
        File file = text("original");
        try {
            assertThrows(InterruptedIOException.class, () -> EditorIo.atomicSave(EditorIo.snapshot(file), out -> {
                out.write(7); Thread.currentThread().interrupt();
            }, null));
        } finally { Thread.interrupted(); }
        assertEquals("original", value(file)); assertNoTemps(file);
    }
    @Test public void symlinkSaveReplacesTargetAndRetainsLink() throws Exception {
        File target = text("original"), link = new File(temporary.getRoot(), "link");
        Files.createSymbolicLink(link.toPath(), target.toPath());
        EditorIo.saveRange(EditorIo.snapshot(link), 0, target.length(), "saved".getBytes(StandardCharsets.UTF_8), null);
        assertTrue(Files.isSymbolicLink(link.toPath())); assertEquals("saved", value(target));
    }
    @Test public void lineCountHandlesShortTailAfterLargeMultibytePage() throws Exception {
        File file = text(repeat("猫\r\n", 40000)); EditorIo.Encoding encoding = EditorIo.detectEncoding(file);
        EditorIo.TextPage first = EditorIo.readTextPage(file, encoding, 0, EditorIo.TEXT_PAGE_BYTES);
        EditorIo.TextPage second = EditorIo.readTextPage(file, encoding, first.end, 17);
        assertEquals(EditorIo.newlineCount(first.text) + EditorIo.newlineCount(second.text), EditorIo.linesBefore(file, encoding, second.end));
    }
    @Test public void regexCharacterAccessChecksTimeAndCancellation() {
        assertThrows(IllegalArgumentException.class, () -> EditorIo.searchMatcher(
            EditorIo.searchPattern("a", true, true), "aaaa", 0).find());
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalArgumentException.class, () -> EditorIo.searchMatcher(
                EditorIo.searchPattern("a", true, true), "aaaa").find());
        } finally { Thread.interrupted(); }
    }
    @Test public void excessiveSearchExpressionsAreRejectedBeforeCompilation() {
        assertThrows(IllegalArgumentException.class, () -> EditorIo.searchPattern(repeat("a", 8193), true, true));
    }
    private void assertNoTemps(File file) { assertEquals(0, file.getParentFile().listFiles((dir, name) -> name.startsWith(".vey-editor-")).length); }
}
