package com.termux.app.files.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.archivers.zip.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import static org.junit.Assert.*;
import static com.termux.app.files.archive.ArchiveTools.*;

public class ArchiveToolsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final Options defaults = new Options();
    private File file(String name, String text) throws IOException {
        File f = tmp.newFile(name); Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); return f;
    }
    private File archive(String suffix) { return new File(tmp.getRoot(), UUID.randomUUID() + suffix); }
    private Set<String> set(String... names) { return new LinkedHashSet<>(Arrays.asList(names)); }
    private List<Source> sources(File f, String name) { return Collections.singletonList(new Source(f, name)); }
    private List<String> names(File archive, Options o) throws IOException {
        List<String> names = new ArrayList<>(); for (Entry e : list(archive, o, null)) names.add(e.name); return names;
    }
    private String text(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private interface Failing { void run() throws Exception; }
    private void fails(Failing f) throws Exception {
        try { f.run(); fail("Expected IOException"); } catch (IOException expected) { /* Some codec EOFExceptions have no message. */ }
    }
    private void noTemps() {
        File[] f = tmp.getRoot().listFiles((dir, name) -> name.startsWith(".vey-"));
        assertNotNull(f); assertEquals("Temporary files leaked", 0, f.length);
    }

    @Test public void writableFormatRoundTripsAndEdits() throws Exception {
        File a = file("a", "hello 中文\n"), b = file("b", "second\0payload"), replacement = file("new", "replacement");
        File dir = tmp.newFolder("empty");
        for (Format f : Arrays.asList(Format.ZIP, Format.TAR, Format.TAR_GZIP, Format.TAR_XZ, Format.SEVEN_Z)) {
            File arc = archive(".bin");
            create(arc, f, Arrays.asList(new Source(dir, "dir/"), new Source(a, "dir/a"), new Source(b, "b")), defaults, null);
            assertEquals(f, detect(arc)); test(arc, defaults, null);
            assertEquals(Arrays.asList("dir/", "dir/a", "b"), names(arc, defaults));
            File selected = tmp.newFolder(); extract(arc, selected, set("dir"), defaults, null);
            assertEquals(text(a), text(new File(selected, "dir/a"))); assertFalse(new File(selected, "b").exists());
            File all = tmp.newFolder(); extract(arc, all, null, defaults, null);
            assertEquals(text(b), text(new File(all, "b")));
            addReplace(arc, Arrays.asList(new Source(replacement, "dir/a"), new Source(a, "added")), defaults, null);
            rename(arc, "dir", "renamed", defaults, null);
            assertTrue(names(arc, defaults).contains("renamed/a"));
            delete(arc, set("b", "added"), defaults, null); test(arc, defaults, null);
            File updated = tmp.newFolder(); extract(arc, updated, null, defaults, null);
            assertEquals("replacement", text(new File(updated, "renamed/a")));
            delete(arc, set("renamed"), defaults, null);
            assertTrue(names(arc, defaults).isEmpty()); test(arc, defaults, null);
        }
        noTemps();
    }

    @Test public void gzipReallyWritesReadsReplacesAndRenamesSingleFile() throws Exception {
        File a = file("gzip-a", "a\nb\n"), b = file("gzip-b", "new data"); File arc = archive(".gz");
        create(arc, Format.GZIP, sources(a, "payload"), defaults, null);
        assertEquals(Format.GZIP, detect(arc)); assertEquals(Collections.singletonList("payload"), names(arc, defaults));
        test(arc, defaults, null); File out = tmp.newFolder(); extract(arc, out, null, defaults, null);
        assertEquals(text(a), text(new File(out, "payload")));
        addReplace(arc, sources(b, "payload"), defaults, null); rename(arc, "payload", "renamed", defaults, null);
        out = tmp.newFolder(); extract(arc, out, null, defaults, null); assertEquals(text(b), text(new File(out, "renamed")));
        byte[] original = Files.readAllBytes(arc.toPath());
        fails(() -> delete(arc, set("renamed"), defaults, null));
        fails(() -> addReplace(arc, sources(a, "extra"), defaults, null));
        assertArrayEquals(original, Files.readAllBytes(arc.toPath())); noTemps();
    }

    @Test public void zipAndApkAesAndStandardPasswordRoundTrips() throws Exception {
        File input = file("secret", "authenticated content");
        for (Encryption encryption : Arrays.asList(Encryption.AES_256, Encryption.ZIP_STANDARD)) {
            Options o = new Options(); o.password = "good password".toCharArray(); o.encryption = encryption;
            File arc = archive(".apk"); create(arc, Format.ZIP, sources(input, "assets/secret"), o, null);
            assertTrue(list(arc, null, null).get(0).encrypted); test(arc, o, null);
            File out = tmp.newFolder(); extract(arc, out, null, o, null);
            assertEquals(text(input), text(new File(out, "assets/secret")));
            Options wrong = new Options(); wrong.password = "bad".toCharArray();
            fails(() -> test(arc, wrong, null)); fails(() -> test(arc, null, null));
            byte[] before = Files.readAllBytes(arc.toPath());
            fails(() -> delete(arc, set("assets/secret"), wrong, null));
            assertArrayEquals(before, Files.readAllBytes(arc.toPath()));
            // Password alone preserves encryption of old entries during rewrite.
            Options preserve = new Options(); preserve.password = o.password;
            rename(arc, "assets", "files", preserve, null);
            assertTrue(list(arc, null, null).get(0).encrypted); test(arc, preserve, null);
            assertArrayEquals("good password".toCharArray(), o.password);
        }
        noTemps();
    }

    @Test public void sevenZEncryptionRoundTripAndRewrite() throws Exception {
        File input = file("7-secret", "7z encrypted content"); File arc = archive(".7z");
        Options o = new Options(); o.encryption = Encryption.AES_256; o.password = "secret".toCharArray();
        create(arc, Format.SEVEN_Z, sources(input, "secret"), o, null); test(arc, o, null);
        assertTrue(list(arc, o, null).get(0).encrypted);
        Options wrong = new Options(); wrong.password = "wrong".toCharArray();
        fails(() -> test(arc, wrong, null));
        Options preserve = new Options(); preserve.password = o.password;
        rename(arc, "secret", "renamed", preserve, null); test(arc, preserve, null);
        File out = tmp.newFolder(); extract(arc, out, null, preserve, null);
        assertEquals(text(input), text(new File(out, "renamed")));
        fails(() -> test(arc, null, null)); noTemps();
    }

    @Test public void cancellationDuringRewritePreservesOriginalAndReportsProgress() throws Exception {
        File a = file("cancel-data", "content"), arc = archive(".zip"); create(arc, Format.ZIP, sources(a, "a"), defaults, null);
        byte[] before = Files.readAllBytes(arc.toPath()); List<String> phases = new ArrayList<>();
        Job[] holder = new Job[1]; holder[0] = new Job((phase, entry, bytes, total) -> {
            phases.add(phase); if (phase.equals("write") && bytes > 0) holder[0].cancel();
        });
        fails(() -> addReplace(arc, sources(a, "b"), defaults, holder[0]));
        assertTrue(holder[0].isCancelled()); assertTrue(phases.contains("write"));
        assertArrayEquals(before, Files.readAllBytes(arc.toPath())); noTemps();
    }

    @Test public void cancellationDuringReadbackDoesNotCommit() throws Exception {
        File a = file("verify-data", "data"), arc = archive(".zip"); create(arc, Format.ZIP, sources(a, "a"), defaults, null);
        byte[] before = Files.readAllBytes(arc.toPath());
        Job[] holder = new Job[1]; holder[0] = new Job((phase, entry, bytes, total) -> { if (phase.equals("verify")) holder[0].cancel(); });
        fails(() -> rename(arc, "a", "b", defaults, holder[0]));
        assertArrayEquals(before, Files.readAllBytes(arc.toPath())); noTemps();
    }

    @Test public void cancellationAndBadPasswordNeverOverwriteExtractedFile() throws Exception {
        File input = file("overwrite-data", "original payload"), arc = archive(".zip");
        Options o = new Options(); o.password = "password".toCharArray(); o.encryption = Encryption.AES_256;
        create(arc, Format.ZIP, sources(input, "existing"), o, null);
        File dest = tmp.newFolder(), existing = new File(dest, "existing"); Files.write(existing.toPath(), new byte[]{42});
        Options wrong = new Options(); wrong.password = "wrong".toCharArray(); wrong.replaceExisting = true;
        fails(() -> extract(arc, dest, null, wrong, null)); assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        o.replaceExisting = true;
        Job[] j = new Job[1]; j[0] = new Job((phase, entry, bytes, total) -> { if (phase.equals("extract") && bytes > 0) j[0].cancel(); });
        fails(() -> extract(arc, dest, null, o, j[0])); assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        assertEquals(1, dest.list().length);
        extract(arc, dest, null, o, null); assertEquals(text(input), text(existing));
    }

    @Test public void traversalAbsoluteBackslashDriveAndAmbiguousNamesRejectedBeforeExtraction() throws Exception {
        for (String name : Arrays.asList("../escape", "/absolute", "a/../../escape", "a\\escape", "C:/escape", "a//b", "a/./b")) {
            File arc = maliciousZip(name, false); File dest = new File(tmp.newFolder(), "not-created");
            try { fails(() -> extract(arc, dest, Collections.emptySet(), defaults, null)); } catch (AssertionError ex) { throw new AssertionError("unsafe name: " + name, ex); } assertFalse(dest.exists());
            fails(() -> test(arc, defaults, null));
        }
    }

    @Test public void archiveSymlinksAndTarHardlinksAndSpecialFilesRejected() throws Exception {
        File zip = maliciousZip("link", true); fails(() -> test(zip, defaults, null));
        for (byte type : new byte[]{TarConstants.LF_SYMLINK, TarConstants.LF_LINK, TarConstants.LF_FIFO}) {
            File tar = archive(".tar");
            try (TarArchiveOutputStream out = new TarArchiveOutputStream(new FileOutputStream(tar))) {
                TarArchiveEntry h = new TarArchiveEntry("link", type); h.setLinkName("../../outside");
                out.putArchiveEntry(h); out.closeArchiveEntry();
            }
            try { fails(() -> extract(tar, tmp.newFolder(), null, defaults, null)); } catch (AssertionError ex) { throw new AssertionError("TAR type: " + type, ex); }
        }
    }

    @Test public void existingAndDanglingDestinationSymlinksRejected() throws Exception {
        File input = file("link-data", "data"), arc = archive(".zip"); create(arc, Format.ZIP, sources(input, "nested/data"), defaults, null);
        File outside = tmp.newFolder(); File root = tmp.newFolder();
        Files.createSymbolicLink(new File(root, "nested").toPath(), outside.toPath());
        fails(() -> extract(arc, root, null, defaults, null)); assertEquals(0, outside.list().length);
        Files.delete(new File(root, "nested").toPath());
        Files.createSymbolicLink(new File(root, "nested").toPath(), new File(outside, "missing").toPath());
        fails(() -> extract(arc, root, null, defaults, null)); assertFalse(new File(outside, "missing").exists());
    }

    @Test public void sourceSymlinksAndArchiveSymlinksRejected() throws Exception {
        File a = file("real-source", "data"), source = new File(tmp.getRoot(), "source-link");
        Files.createSymbolicLink(source.toPath(), a.toPath()); File arc = archive(".zip");
        fails(() -> create(arc, Format.ZIP, sources(source, "x"), defaults, null)); assertFalse(arc.exists());
        create(arc, Format.ZIP, sources(a, "a"), defaults, null);
        File alias = new File(tmp.getRoot(), "archive-link"); Files.createSymbolicLink(alias.toPath(), arc.toPath());
        byte[] before = Files.readAllBytes(arc.toPath()); fails(() -> rename(alias, "a", "b", defaults, null));
        assertArrayEquals(before, Files.readAllBytes(arc.toPath()));
    }

    @Test public void collisionsMissingSelectionsAndUnsupportedEncryptionRetainOriginal() throws Exception {
        File a = file("conflict-data", "data"), arc = archive(".zip");
        create(arc, Format.ZIP, Arrays.asList(new Source(a, "a"), new Source(a, "b")), defaults, null);
        byte[] before = Files.readAllBytes(arc.toPath());
        fails(() -> rename(arc, "a", "b", defaults, null));
        fails(() -> rename(arc, "missing", "new", defaults, null));
        fails(() -> delete(arc, set("missing"), defaults, null));
        fails(() -> addReplace(arc, sources(new File(tmp.getRoot(), "missing"), "c"), defaults, null));
        fails(() -> addReplace(arc, sources(a, "a/child"), defaults, null));
        fails(() -> create(arc, Format.ZIP, sources(a, "new"), defaults, null));
        assertArrayEquals(before, Files.readAllBytes(arc.toPath()));
        Options encrypted = new Options(); encrypted.encryption = Encryption.AES_256; encrypted.password = "secret".toCharArray();
        fails(() -> create(archive(".tar"), Format.TAR, sources(a, "a"), encrypted, null)); noTemps();
    }

    @Test public void extractionConflictsAndEmptyAndMissingSelections() throws Exception {
        File a = file("select-data", "data"), arc = archive(".zip"); create(arc, Format.ZIP, sources(a, "a"), defaults, null);
        File out = tmp.newFolder(); extract(arc, out, Collections.emptySet(), defaults, null); assertEquals(0, out.list().length);
        fails(() -> extract(arc, out, set("missing"), defaults, null));
        extract(arc, out, null, defaults, null); fails(() -> extract(arc, out, null, defaults, null));
        assertEquals("data", text(new File(out, "a")));
    }

    @Test public void sizeEntryCountAndActualInflatedLimits() throws Exception {
        File a = file("limit-data", "0123456789"), arc = archive(".zip");
        create(arc, Format.ZIP, Arrays.asList(new Source(a, "a"), new Source(a, "b")), defaults, null);
        Options small = new Options(); small.maxEntryBytes = 5; fails(() -> test(arc, small, null));
        Options total = new Options(); total.maxTotalBytes = 15; fails(() -> test(arc, total, null));
        Options count = new Options(); count.maxEntries = 1; fails(() -> list(arc, count, null));
        File gzip = archive(".gz"); create(gzip, Format.GZIP, sources(a, "a"), defaults, null);
        fails(() -> test(gzip, small, null));
        File target = archive(".zip"); fails(() -> create(target, Format.ZIP, sources(a, "a"), small, null));
        assertFalse(target.exists()); noTemps();
    }

    @Test public void damagedPayloadsAndTruncatedCompressedTarFail() throws Exception {
        File a = file("corrupt-data", "0123456789abcdef");
        for (Format format : Arrays.asList(Format.ZIP, Format.GZIP, Format.TAR_GZIP, Format.TAR_XZ, Format.SEVEN_Z)) {
            File arc = archive(".bin"); create(arc, format, sources(a, "a"), defaults, null);
            byte[] bytes = Files.readAllBytes(arc.toPath());
            if (format == Format.ZIP) {
                // Local file header fixed length 30, then UTF-8 name; first compressed byte.
                int name = (bytes[26] & 255) | (bytes[27] & 255) << 8;
                int extra = (bytes[28] & 255) | (bytes[29] & 255) << 8;
                bytes[30 + name + extra] ^= 0x7f;
            } else bytes = Arrays.copyOf(bytes, bytes.length - 8);
            Files.write(arc.toPath(), bytes); fails(() -> test(arc, defaults, null));
            byte[] damaged = Files.readAllBytes(arc.toPath());
            fails(() -> addReplace(arc, sources(a, "b"), defaults, null));
            assertArrayEquals(damaged, Files.readAllBytes(arc.toPath()));
        }
        noTemps();
    }

    @Test public void storedRarReadsTestsExtractsAndRefusesWriting() throws Exception {
        File rar = archive(".rar"); Files.write(rar.toPath(), rar4("payload", "RAR content"));
        assertEquals(Format.RAR, detect(rar)); assertEquals(Collections.singletonList("payload"), names(rar, defaults));
        test(rar, defaults, null); File out = tmp.newFolder(); extract(rar, out, set("payload"), defaults, null);
        assertEquals("RAR content", text(new File(out, "payload")));
        byte[] before = Files.readAllBytes(rar.toPath());
        fails(() -> delete(rar, set("payload"), defaults, null));
        fails(() -> rename(rar, "payload", "new", defaults, null));
        fails(() -> create(archive(".rar"), Format.RAR, sources(file("rar-source", "x"), "x"), defaults, null));
        assertArrayEquals(before, Files.readAllBytes(rar.toPath()));
        before[before.length - 9] ^= 1; Files.write(rar.toPath(), before); fails(() -> test(rar, defaults, null));
    }

    @Test public void rarTraversalAndLinkModeRejected() throws Exception {
        File rar = archive(".rar"); Files.write(rar.toPath(), rar4("../escape", "content"));
        fails(() -> extract(rar, tmp.newFolder(), null, defaults, null));
        byte[] bytes = rar4("link", "target");
        // RAR4 file header begins after 7-byte marker and 13-byte main header.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(20 + 28, 0120777);
        CRC32 crc = new CRC32(); crc.update(bytes, 22, 32 + 4 - 2);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(20, (short)crc.getValue());
        Files.write(rar.toPath(), bytes); fails(() -> test(rar, defaults, null));
    }

    @Test public void duplicateAndFileDirectoryZipEntriesRejected() throws Exception {
        for (List<String> names : Arrays.asList(Arrays.asList("same", "same"), Arrays.asList("file", "file/child"))) {
            File arc = archive(".zip");
            try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(arc)) {
                for (String name : names) { out.putArchiveEntry(new ZipArchiveEntry(name)); out.write(1); out.closeArchiveEntry(); }
            }
            try { fails(() -> test(arc, defaults, null)); } catch (AssertionError ex) { throw new AssertionError("Rejected member fixture: " + names, ex); }
            File dest = new File(tmp.newFolder(), "not-created");
            fails(() -> extract(arc, dest, null, defaults, null)); assertFalse(dest.exists());
        }
    }

    @Test public void tarAbsoluteAndTraversalNamesRejected() throws Exception {
        for (String name : Arrays.asList("/escape", "../escape", "a\\b")) {
            File arc = archive(".tar");
            try (TarArchiveOutputStream out = new TarArchiveOutputStream(new FileOutputStream(arc))) {
                TarArchiveEntry h = new TarArchiveEntry(name, true); h.setSize(1);
                out.putArchiveEntry(h); out.write(1); out.closeArchiveEntry();
            }
            try { fails(() -> test(arc, defaults, null)); } catch (AssertionError ex) { throw new AssertionError("TAR path: " + name, ex); }
        }
    }

    @Test public void injectedSourceReadFailureAndNewArchiveCancellationLeaveNoArtifacts() throws Exception {
        File source = file("failure-source", "data"), arc = archive(".zip");
        create(arc, Format.ZIP, sources(source, "a"), defaults, null); byte[] before = Files.readAllBytes(arc.toPath());
        Job fail = new Job((phase, name, bytes, total) -> { if (phase.equals("write") && name.equals("b")) Files.delete(source.toPath()); });
        fails(() -> addReplace(arc, sources(source, "b"), defaults, fail)); assertArrayEquals(before, Files.readAllBytes(arc.toPath()));
        File fresh = archive(".zip"); File input = file("new-source", "data");
        Job cancelled = new Job(); cancelled.cancel(); fails(() -> create(fresh, Format.ZIP, sources(input, "a"), defaults, cancelled));
        assertFalse(fresh.exists()); noTemps();
    }

    @Test public void explicitReplaceAndArchiveSelfSourceAreHandled() throws Exception {
        File a = file("replace-source", "data"), arc = archive(".zip"); create(arc, Format.ZIP, sources(a, "a"), defaults, null);
        fails(() -> addReplace(arc, sources(arc, "self"), defaults, null));
        Options replace = new Options(); replace.replaceExisting = true;
        create(arc, Format.TAR, sources(a, "b"), replace, null); assertEquals(Format.TAR, detect(arc));
        assertEquals(Collections.singletonList("b"), names(arc, defaults)); noTemps();
    }

    @Test public void sevenZHostileNamesAndLinksRejected() throws Exception {
        for (String name : Arrays.asList("../escape", "/absolute", "a\\escape", "regular")) {
            File arc = archive(".7z");
            try (org.apache.commons.compress.archivers.sevenz.SevenZOutputFile out =
                     new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(arc)) {
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry h =
                    new org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry();
                h.setName(name); h.setSize(1);
                if (name.equals("regular")) { h.setWindowsAttributes((0120777 << 16) | 0x8000); h.setHasWindowsAttributes(true); }
                out.putArchiveEntry(h); out.write(1); out.closeArchiveEntry();
            }
            fails(() -> test(arc, defaults, null));
            File dest = new File(tmp.newFolder(), "not-created");
            fails(() -> extract(arc, dest, null, defaults, null)); assertFalse(dest.exists());
        }
    }

    @Test public void extractingOverSourceArchiveIsRejected() throws Exception {
        File input = file("self-extract-data", "data"), arc = archive(".zip");
        create(arc, Format.ZIP, sources(input, arc.getName()), defaults, null);
        byte[] before = Files.readAllBytes(arc.toPath()); Options replace = new Options(); replace.replaceExisting = true;
        fails(() -> extract(arc, arc.getParentFile(), null, replace, null));
        assertArrayEquals(before, Files.readAllBytes(arc.toPath())); noTemps();
    }

    private File maliciousZip(String name, boolean symlink) throws IOException {
        File f = archive(".zip");
        if (!symlink) {
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new FileOutputStream(f))) {
                out.putNextEntry(new java.util.zip.ZipEntry(name)); out.write("payload".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
            return f;
        }
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(f)) {
            ZipArchiveEntry h = new ZipArchiveEntry(name); if (symlink) h.setUnixMode(0120777);
            out.putArchiveEntry(h); out.write("../target".getBytes(StandardCharsets.UTF_8)); out.closeArchiveEntry();
        }
        return f;
    }
    /** Tiny in-process RAR4 STORE fixture, with genuine header/payload CRCs; no binary fixture download. */
    static byte[] rar4(String name, String content) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.US_ASCII), data = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(new byte[]{'R','a','r','!',0x1a,7,0});
        ByteBuffer main = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
        main.putShort((short)0).put((byte)0x73).putShort((short)0).putShort((short)13).put(new byte[6]);
        out.write(headerCrc(main.array()));
        CRC32 crc = new CRC32(); crc.update(data);
        ByteBuffer fh = ByteBuffer.allocate(32 + n.length).order(ByteOrder.LITTLE_ENDIAN);
        fh.putShort((short)0).put((byte)0x74).putShort((short)0x8000).putShort((short)(32 + n.length));
        fh.putInt(data.length).putInt(data.length).put((byte)3).putInt((int)crc.getValue()).putInt(0x00210000);
        fh.put((byte)20).put((byte)0x30).putShort((short)n.length).putInt(0100644).put(n);
        out.write(headerCrc(fh.array())); out.write(data);
        ByteBuffer end = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
        end.putShort((short)0).put((byte)0x7b).putShort((short)0x4000).putShort((short)7); out.write(headerCrc(end.array()));
        return out.toByteArray();
    }
    private static byte[] headerCrc(byte[] bytes) {
        CRC32 crc = new CRC32(); crc.update(bytes, 2, bytes.length - 2);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort((short)crc.getValue()); return bytes;
    }
}
