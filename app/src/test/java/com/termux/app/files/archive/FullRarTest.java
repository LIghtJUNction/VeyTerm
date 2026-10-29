package com.termux.app.files.archive;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import static com.termux.app.files.archive.ArchiveTools.*;
import static org.junit.Assert.*;

/** Run only in a separate JVM with the actual pinned standalone full decoder.
 * The BSD/native and missing-JNI suites retain their original separate JVMs.
 */
public class FullRarTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final Options options = new Options();
    private static File helper;
    @BeforeClass public static void enable() throws Exception {
        helper = new File(System.getProperty("veyterm.rar.command"));
        RarExecutable.setExecutable(helper);
    }
    @AfterClass public static void disable() throws Exception { RarExecutable.setExecutable(null); }
    private interface IO { void run() throws Exception; }
    private IOException fails(IO action) throws Exception {
        try { action.run(); fail("Expected IOException"); }
        catch (IOException e) { return e; }
        throw new AssertionError();
    }
    private File fixture(String name) { return new File(System.getProperty("veyterm.rar.fixtures"), name + ".rar"); }
    private File rar(String name) { return fixture("test_read_format_" + name); }
    private File save(byte[] data) throws Exception { File f = tmp.newFile(); Files.write(f.toPath(), data); return f; }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] b = new byte[32768]; int n;
            while ((n = input.read(b)) >= 0) digest.update(b, 0, n);
        }
        StringBuilder text = new StringBuilder();
        for (byte b : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    private void noStaging(File dest) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.walk(dest.toPath())) {
            assertFalse(files.anyMatch(f -> f.getFileName().toString().startsWith(".vey-")));
        }
    }
    @Test public void encryptedHeadersAndSolidRar4AndRar5ExtractExactBytesAndKeepSource() throws Exception {
        options.password = "password".toCharArray();
        for (String name : Arrays.asList("rar4_encrypted_filenames", "rar4_solid_encrypted", "rar4_solid_encrypted_filenames",
                "rar5_encrypted_filenames", "rar5_solid_encrypted", "rar5_solid_encrypted_filenames")) {
            File f = rar(name); String original = digest(f);
            List<Entry> entries = list(f, options, null);
            assertEquals(name, 4, entries.size());
            for (Entry e : entries) { assertTrue(e.encrypted); assertEquals(18, e.size); assertTrue(e.modified > 0); }
            test(f, options, null);
            File dest = tmp.newFolder(); extract(f, dest, null, options, null);
            for (char letter = 'a'; letter <= 'd'; letter++) {
                String member = letter + ".txt";
                assertArrayEquals(name, bytes("This is from " + member), Files.readAllBytes(new File(dest, member).toPath()));
            }
            File selected = tmp.newFolder(); extract(f, selected, Collections.singleton("d.txt"), options, null);
            assertEquals(1, Objects.requireNonNull(selected.list()).length);
            assertEquals(original, digest(f)); noStaging(dest); noStaging(selected);
        }
        assertArrayEquals("password".toCharArray(), options.password);
    }
    @Test public void dataEncryptionUsesCorrectUpstreamPasswordsAndMixedPasswordArchiveFailsClosed() throws Exception {
        options.password = "12345678".toCharArray();
        for (String name : Arrays.asList("rar_encryption_data", "rar_encryption_header")) {
            File f = rar(name); test(f, options, null);
            File dest = tmp.newFolder(); extract(f, dest, null, options, null);
            assertTrue(list(f, options, null).stream().anyMatch(e -> e.encrypted));
        }
        for (String version : Arrays.asList("rar4", "rar5")) {
            File original = rar(version + "_encrypted");
            assertEquals(4, list(original, options, null).size());
            for (String password : Arrays.asList("password", "password2")) {
                options.password = password.toCharArray(); fails(() -> test(original, options, null));
                File dest = tmp.newFolder(); File target = new File(dest, "a.txt"); Files.write(target.toPath(), bytes("keep"));
                options.replaceExisting = true;
                fails(() -> extract(original, dest, Collections.singleton("a.txt"), options, null));
                assertArrayEquals(bytes("keep"), Files.readAllBytes(target.toPath())); noStaging(dest);
            }
            // Keep the FIRST TWO original file records unchanged: b.txt decrypts
            // with 'password', while the original complete archive has password2
            // on d.txt. This independently tests clear-header encrypted payloads.
            File subset = save(firstTwo(original, version.equals("rar5")));
            options.password = "password".toCharArray(); test(subset, options, null);
            File dest = tmp.newFolder(); extract(subset, dest, null, options, null);
            assertArrayEquals(bytes("This is from b.txt"), Files.readAllBytes(new File(dest, "b.txt").toPath()));
        }
    }
    @Test public void missingAndWrongPasswordsNeverOverwriteAndNeverAppearInErrors() throws Exception {
        for (String password : Arrays.asList("", "secret-do-not-log")) {
            options.password = password.toCharArray(); options.replaceExisting = true;
            for (String name : Arrays.asList("rar4_encrypted_filenames", "rar5_encrypted_filenames", "rar4_solid_encrypted", "rar5_solid_encrypted")) {
                File f = rar(name); String original = digest(f);
                IOException error = fails(() -> test(f, options, null));
                assertFalse(error.toString().contains("secret-do-not-log"));
                File dest = tmp.newFolder(); File target = new File(dest,"a.txt"); Files.write(target.toPath(), bytes("keep"));
                fails(() -> extract(f,dest,Collections.singleton("a.txt"),options,null));
                assertArrayEquals(bytes("keep"), Files.readAllBytes(target.toPath()));
                assertEquals(original,digest(f)); noStaging(dest);
            }
        }
    }
    @Test public void localRar4AndRar5VolumesJoinAndMissingMiddleVolumeFails() throws Exception {
        for (boolean old : Arrays.asList(true,false)) {
            String base = old ? "test_rar_multivolume_single_file.part" : "test_read_format_rar5_multiarchive.part";
            int count = old ? 3 : 8; File folder = tmp.newFolder(); List<String> original = new ArrayList<>();
            for (int i=1;i<=count;i++) {
                String n=base+(old ? Integer.toString(i):String.format(Locale.ROOT,"%02d",i));
                File f=new File(folder,n+".rar"); Files.copy(fixture(n).toPath(),f.toPath()); original.add(digest(f));
            }
            File first=new File(folder,base+(old ? "1":"01")+".rar");
            List<Entry> entries=list(first,options,null); assertEquals(old ? 1:2,entries.size()); test(first,options,null);
            File dest=tmp.newFolder(); extract(first,dest,null,options,null);
            if (old) assertEquals("ee16390e87152d7dec632ae2b37566da6135966e46b5166e6f40a1c827fa61ad",digest(new File(dest,entries.get(0).name)));
            else {
                long[] crc={0x35277473L,0xE59665F8L};
                for(int i=0;i<2;i++) { CRC32 c=new CRC32(); c.update(Files.readAllBytes(new File(dest,entries.get(i).name).toPath())); assertEquals(crc[i],c.getValue()); }
            }
            for (int i=1;i<=count;i++) assertEquals(original.get(i-1),digest(new File(folder,base+(old?Integer.toString(i):String.format(Locale.ROOT,"%02d",i))+".rar")));
            File middle=new File(folder,base+(old?"2":"02")+".rar"); assertTrue(middle.delete());
            fails(() -> test(first,options,null)); File absent=new File(tmp.newFolder(),"absent");
            fails(() -> extract(first,absent,null,options,null)); assertFalse(absent.exists());
        }
    }
    @Test public void literalWildcardMemberNamesNeverSelectAnotherFile() throws Exception {
        byte[] one=ArchiveToolsTest.rar4("a?.txt","first"),two=ArchiveToolsTest.rar4("ab.txt","second");
        ByteArrayOutputStream data=new ByteArrayOutputStream(); data.write(one,0,one.length-7);data.write(two,20,two.length-20);
        File f=save(data.toByteArray()); File dest=tmp.newFolder(); extract(f,dest,Collections.singleton("a?.txt"),options,null);
        assertArrayEquals(bytes("first"),Files.readAllBytes(new File(dest,"a?.txt").toPath())); assertFalse(new File(dest,"ab.txt").exists());
    }
    @Test public void corruptionIsCheckedBeforePublishingEvenFirstSelectedFile() throws Exception {
        File good=rar("rar5_solid_encrypted"); byte[] data=Files.readAllBytes(good.toPath()); data[data.length-15]^=1;
        File bad=save(data); options.password="password".toCharArray();options.replaceExisting=true;
        File dest=tmp.newFolder(); File target=new File(dest,"a.txt"); Files.write(target.toPath(),bytes("keep"));
        fails(() -> test(bad,options,null));fails(() -> extract(bad,dest,Collections.singleton("a.txt"),options,null));
        assertArrayEquals(bytes("keep"),Files.readAllBytes(target.toPath()));noStaging(dest);
        File crc=save(RarArchiveTest.rar5("safe",bytes("bad CRC"),0100644,0,null,true,0));
        File safe=new File(dest,"safe"); Files.write(safe.toPath(),bytes("keep"));
        fails(() -> extract(crc,dest,null,options,null));assertArrayEquals(bytes("keep"),Files.readAllBytes(safe.toPath()));noStaging(dest);
    }
    @Test public void actualHelperCancellationKeepsSourceTargetAndNoPumpThreads() throws Exception {
        File f=rar("rar_multi_lzss_blocks");String original=digest(f);File dest=tmp.newFolder();
        File target=new File(dest,"multi_lzss_blocks_test.txt");Files.write(target.toPath(),bytes("keep"));options.replaceExisting=true;
        Job[] job={null}; job[0]=new Job((phase,name,done,total)->{if(phase.equals("extract")&&done>=32768)job[0].cancel();});
        assertTrue(fails(() -> extract(f,dest,null,options,job[0])) instanceof InterruptedIOException);
        assertArrayEquals(bytes("keep"),Files.readAllBytes(target.toPath()));assertEquals(original,digest(f));noStaging(dest);
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("vey-rar-")));
        options.password = "password".toCharArray();
        for (String name : Arrays.asList("rar4_solid_encrypted", "rar5_solid_encrypted_filenames")) {
            File encrypted = rar(name); String hash = digest(encrypted); File folder = tmp.newFolder();
            File existing = new File(folder, "a.txt"); Files.write(existing.toPath(), bytes("keep"));
            Job[] cancelled = {null};
            cancelled[0] = new Job((phase, entry, done, total) -> { if (phase.equals("extract") && done > 0) cancelled[0].cancel(); });
            assertTrue(fails(() -> extract(encrypted, folder, null, options, cancelled[0])) instanceof InterruptedIOException);
            assertArrayEquals(bytes("keep"), Files.readAllBytes(existing.toPath()));
            assertEquals(hash, digest(encrypted)); noStaging(folder);
        }
    }
    @Test public void actualHelperStreamingAndMemoryAndSizeLimits() throws Exception {
        File f=rar("rar_multi_lzss_blocks");File dest=tmp.newFolder();long[] last={0};int[] calls={0};
        extract(f,dest,null,options,new Job((phase,name,done,total)->{if(phase.equals("extract")){assertTrue(done-last[0]<=32768);last[0]=done;calls[0]++;}}));
        assertEquals(20131111,last[0]);assertTrue(calls[0]>600);
        options.maxMemoryKiB=1024; assertTrue(fails(() -> test(f,options,null)).getMessage().contains("memory limit"));
        options.maxMemoryKiB=65536;options.maxEntries=1;fails(() -> list(rar("rar5_solid_encrypted"),options,null));
        options.maxEntries=100;options.maxEntryBytes=1;fails(() -> list(f,options,null));
        options.maxEntryBytes=100000000;options.maxTotalBytes=1;fails(() -> list(f,options,null));
    }
    @Test public void hostileNamesLinksSpecialsDuplicatesAndConflictsFailBeforeCreatingDestination() throws Exception {
        for(String name:Arrays.asList("../escape","/absolute","a//b","C:drive","bad\0tail","line\n        Type: File","tab\tname","esc\u001bname","bad\r\nname")) {
            File f=save(RarArchiveTest.rar5(name,bytes("x"),0100644,0,null,false,0)); File dest=new File(tmp.newFolder(),"absent");
            fails(() -> extract(f,dest,Collections.emptySet(),options,null)); assertFalse(dest.exists());
            File old=save(ArchiveToolsTest.rar4(name,"x")); fails(() -> list(old,options,null));
        }
        for(int mode:new int[]{0120777,0010644,0020644,0060644}) {
            File f=save(RarArchiveTest.rar5("special",bytes("x"),mode,0,null,false,0)); fails(() -> list(f,options,null));
        }
        for(String name:Arrays.asList("rar5_symlink","rar5_redir_varint_2byte","rar_compress_normal")) fails(() -> list(rar(name),options,null));
        for(String secondName:Arrays.asList("same","same/child")) {
            byte[] a=ArchiveToolsTest.rar4("same","a"),b=ArchiveToolsTest.rar4(secondName,"b");ByteArrayOutputStream out=new ByteArrayOutputStream();
            out.write(a,0,a.length-7);out.write(b,20,b.length-20);File f=save(out.toByteArray());fails(() -> list(f,options,null));
        }
    }
    @Test public void utf8AndReadOnlyApiAndExplicitConfiguration() throws Exception {
        File f=save(RarArchiveTest.rar5("目录/👋🌎.txt",bytes("unicode"),0100644,0,null,false,0));
        assertEquals("目录/👋🌎.txt",list(f,options,null).get(0).name);File dest=tmp.newFolder();extract(f,dest,null,options,null);
        assertArrayEquals(bytes("unicode"),Files.readAllBytes(new File(dest,"目录/👋🌎.txt").toPath()));
        fails(() -> addReplace(f,Collections.emptyList(),options,null));fails(() -> delete(f,Collections.singleton("x"),options,null));fails(() -> rename(f,"x","y",options,null));
        fails(() -> RarExecutable.setExecutable(new File("unrar")));
        File wildcard=new File(tmp.newFolder(),"archive?.rar");Files.copy(f.toPath(),wildcard.toPath());fails(() -> list(wildcard,options,null));
    }
    @Test public void timeoutStderrBoundsMetadataAmbiguityAndLateExitNeverPublish() throws Exception {
        File fake = new File(System.getProperty("veyterm.rar.commandFixture"));
        for (String mode : Arrays.asList("sleep", "stderr", "duplicate")) {
            File archive = new File(tmp.newFolder(), mode + ".rar"); Files.write(archive.toPath(), ArchiveToolsTest.rar4("safe", "x"));
            IOException error = fails(() -> new CommandRar(fake, archive, options, () -> {}, 200));
            if (mode.equals("sleep")) assertTrue(error.getMessage(), error.getMessage().contains("timed out"));
        }
        try {
            RarExecutable.setExecutable(fake); options.replaceExisting = true;
            for (String mode : Arrays.asList("late-crc", "overflow")) {
                File archive = new File(tmp.newFolder(), mode + ".rar"); Files.write(archive.toPath(), ArchiveToolsTest.rar4("safe", "x"));
                File dest = tmp.newFolder(); File target = new File(dest, "safe"); Files.write(target.toPath(), bytes("keep"));
                fails(() -> extract(archive, dest, null, options, null));
                assertArrayEquals(bytes("keep"), Files.readAllBytes(target.toPath())); noStaging(dest);
            }
        } finally { RarExecutable.setExecutable(helper); }
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().startsWith("vey-rar-")));
    }
    @Test public void rar4MultipleLocalVolumesAndSourceVolumeOverwriteAreProtected() throws Exception {
        File folder = tmp.newFolder();
        for (int i = 1; i <= 6; i++) {
            String name = "test_rar_multivolume_multiple_files.part" + i;
            Files.copy(fixture(name).toPath(), new File(folder, name + ".rar").toPath());
        }
        File first = new File(folder, "test_rar_multivolume_multiple_files.part1.rar");
        List<Entry> entries = list(first, options, null); assertEquals(2, entries.size()); test(first, options, null);
        File dest = tmp.newFolder(); extract(first, dest, Collections.singleton(entries.get(1).name), options, null);
        assertEquals(20111, new File(dest, entries.get(1).name).length());
        // Real two-volume STORE archive whose output would overwrite volume 2.
        byte[] stored = splitVolume(false);
        File source = new File(folder, "source.part1.rar"); Files.write(source.toPath(), splitVolume(true));
        File later = new File(folder, "source.part2.rar"); Files.write(later.toPath(), stored);
        assertEquals("source.part2.rar", list(source, options, null).get(0).name);
        test(source, options, null);
        options.replaceExisting = true;
        fails(() -> extract(source, folder, null, options, null)); assertArrayEquals(stored, Files.readAllBytes(later.toPath()));
    }
    private static byte[] splitVolume(boolean first) throws Exception {
        byte[] data = ArchiveToolsTest.rar4("source.part2.rar", first ? "keep" : "source");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(10, (short)(first ? 0x111 : 0x11));
        b.putShort(23, (short)(0x8000 | (first ? 2 : 1))); b.putInt(31, 10);
        if (!first) { CRC32 full = new CRC32(); full.update(bytes("keepsource")); b.putInt(36, (int)full.getValue()); }
        int end = data.length - 7; b.putShort(end + 3, (short)(first ? 0x4001 : 0x4000));
        for (int offset : new int[]{7, 20, end}) {
            CRC32 crc = new CRC32(); crc.update(data, offset + 2, (b.getShort(offset + 5) & 65535) - 2);
            b.putShort(offset, (short)crc.getValue());
        }
        return data;
    }
    private static byte[] firstTwo(File archive,boolean rar5) throws Exception {
        byte[] all=Files.readAllBytes(archive.toPath()); int offset=rar5?8:20;
        if(rar5) { int[] at={offset+4};long mainSize=vint(all,at);offset=at[0]+(int)mainSize; }
        for(int i=0;i<2;i++) {
            if(rar5) { int[] at={offset+4};long header=vint(all,at);int end=at[0]+(int)header;vint(all,at);long flags=vint(all,at);
                if((flags&1)!=0)vint(all,at);long packed=(flags&2)!=0?vint(all,at):0;offset=end+(int)packed;
            } else { ByteBuffer b=ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);offset+=(b.getShort(offset+5)&65535)+b.getInt(offset+7); }
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(all,0,offset);
        if(rar5) { byte[] end={3,5,0,0};CRC32 crc=new CRC32();crc.update(end);long c=crc.getValue();for(int i=0;i<4;i++){out.write((int)c&255);c>>>=8;}out.write(end); }
        else out.write(all,all.length-7,7);
        return out.toByteArray();
    }
    private static long vint(byte[] bytes,int[] at) { long v=0;int shift=0,b;do{b=bytes[at[0]++]&255;v|=(long)(b&127)<<shift;shift+=7;}while((b&128)!=0);return v; }
}
