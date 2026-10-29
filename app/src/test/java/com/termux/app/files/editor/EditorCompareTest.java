package com.termux.app.files.editor;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class EditorCompareTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File text(String value) throws IOException { File file = temporary.newFile(); Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); return file; }
    private void write(File file, String value) throws IOException { Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); }
    private File zip(String... entries) throws IOException {
        File file = temporary.newFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new ZipEntry(entries[i])); out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        return file;
    }
    @Test public void binaryReportsChangedRangesAndTrailingBytes() throws Exception {
        EditorCompare.Report report = EditorCompare.compare(text("abcXYf"), text("abc12f!"), EditorCompare.Mode.BINARY);
        assertEquals(3, report.differences); assertEquals(7, report.compared);
        assertTrue(report.toString().contains("0x3..0x4")); assertTrue(report.toString().contains("0x6..0x6"));
    }
    @Test public void equalBinaryHasNoDifferences() throws Exception {
        assertEquals(0, EditorCompare.compare(text("same"), text("same"), EditorCompare.Mode.BINARY).differences);
    }
    @Test public void textReportsLineAndEndOfFileChanges() throws Exception {
        EditorCompare.Report report = EditorCompare.compare(text("one\ntwo\n"), text("one\nchanged\nlast"), EditorCompare.Mode.TEXT);
        assertEquals(2, report.differences); assertTrue(report.toString().contains("<EOF>"));
    }
    @Test public void textDistinguishesLineEndingsAndMissingFinalNewline() throws Exception {
        assertEquals(1, EditorCompare.compare(text("one\r\n"), text("one\n"), EditorCompare.Mode.TEXT).differences);
        assertEquals(1, EditorCompare.compare(text("one"), text("one\n"), EditorCompare.Mode.TEXT).differences);
    }
    @Test public void textDistinguishesBomFromTextContent() throws Exception {
        File left = text("same"), right = text("\ufeffsame");
        EditorCompare.Report report = EditorCompare.compare(left, right, EditorCompare.Mode.TEXT);
        assertEquals(1, report.differences); assertTrue(report.toString().contains("Encoding/BOM differs"));
    }
    @Test public void invalidTextDoesNotPretendToCompareSuccessfully() throws Exception {
        File invalid = temporary.newFile(); Files.write(invalid.toPath(), new byte[]{(byte)0xff});
        assertThrows(IOException.class, () -> EditorCompare.compare(invalid, text("abc"), EditorCompare.Mode.TEXT));
    }
    @Test public void directoryChecksActualContentsNotOnlySizesOrDates() throws Exception {
        File left = temporary.newFolder(), right = temporary.newFolder();
        write(new File(left, "same-size"), "abc"); write(new File(right, "same-size"), "XYZ");
        new File(left, "nested").mkdir(); new File(right, "nested").mkdir();
        write(new File(left, "nested/same"), "unchanged"); write(new File(right, "nested/same"), "unchanged");
        write(new File(left, "only"), "a"); new File(right, "type").mkdir(); write(new File(left, "type"), "file");
        EditorCompare.Report report = EditorCompare.compare(left, right, EditorCompare.Mode.DIRECTORY);
        assertEquals(3, report.differences);
        assertTrue(report.toString().contains("CONTENT same-size")); assertTrue(report.toString().contains("LEFT ONLY only")); assertTrue(report.toString().contains("TYPE type"));
    }
    @Test public void directoryDoesNotFollowCyclesOrDanglingLinks() throws Exception {
        File left = temporary.newFolder(), right = temporary.newFolder();
        for (File directory : new File[]{left, right}) {
            Files.createSymbolicLink(new File(directory, "cycle").toPath(), Paths.get("cycle"));
            Files.createSymbolicLink(new File(directory, "dangling").toPath(), Paths.get("absent"));
            Files.createSymbolicLink(new File(directory, "parent").toPath(), Paths.get(".."));
        }
        assertEquals(0, EditorCompare.compare(left, right, EditorCompare.Mode.DIRECTORY).differences);
        Files.delete(new File(right, "dangling").toPath()); Files.createSymbolicLink(new File(right, "dangling").toPath(), Paths.get("different"));
        assertEquals(1, EditorCompare.compare(left, right, EditorCompare.Mode.DIRECTORY).differences);
    }
    @Test public void zipComparesEntryContentsAndIgnoresOrdering() throws Exception {
        File left = zip("a", "abc", "b", "same"), right = zip("b", "same", "a", "abc");
        assertEquals(0, EditorCompare.compare(left, right, EditorCompare.Mode.ZIP).differences);
        right = zip("a", "XYZ", "only", "new");
        EditorCompare.Report report = EditorCompare.compare(left, right, EditorCompare.Mode.ZIP);
        assertEquals(3, report.differences); assertTrue(report.toString().contains("CONTENT a"));
    }
    @Test public void invalidZipFailsExplicitly() throws Exception {
        assertThrows(IOException.class, () -> EditorCompare.compare(text("not a zip"), zip("a", "b"), EditorCompare.Mode.ZIP));
    }
    @Test public void incompatiblePathTypesFailExplicitly() throws Exception {
        assertThrows(IOException.class, () -> EditorCompare.compare(temporary.newFolder(), text("x"), EditorCompare.Mode.BINARY));
        assertThrows(IOException.class, () -> EditorCompare.compare(text("a"), text("b"), EditorCompare.Mode.DIRECTORY));
    }
    @Test public void reportLimitStillCountsAllDifferences() throws Exception {
        StringBuilder left = new StringBuilder(), right = new StringBuilder();
        for (int i = 0; i < 15000; i++) { left.append("original ").append(i).append('\n'); right.append("different ").append(i).append('\n'); }
        EditorCompare.Report report = EditorCompare.compare(text(left.toString()), text(right.toString()), EditorCompare.Mode.TEXT);
        assertEquals(15000, report.differences); assertTrue(report.limited); assertTrue(report.toString().contains("REPORT LIMIT"));
    }
    @Test public void autoChoosesDirectoryMode() throws Exception {
        File left = temporary.newFolder(), right = temporary.newFolder(); write(new File(left, "a"), "exists");
        assertEquals(1, EditorCompare.compare(left, right, EditorCompare.Mode.AUTO).differences);
    }
}
