package com.termux.app.files.fs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FileOperationsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final PlainFiles fs = new PlainFiles();
    private final FileOperations operations = new FileOperations(fs);
    private FileLocation location(File file) { return FileLocation.local(file); }
    private File file(File parent, String name, String text) throws IOException {
        File file = new File(parent, name);
        Files.write(file.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return file;
    }
    private String contents(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
    private FileOperations.ConflictResolver replace() { return (a, b) -> FileOperations.Conflict.REPLACE; }

    @Test public void recursiveCopyPreservesBytesAndSource() throws Exception {
        File source = temporary.newFolder("source");
        File nested = new File(source, "nested"); assertTrue(nested.mkdir());
        file(nested, "中文 name.txt", "one\r\ntwo\n");
        File target = temporary.newFolder("target");
        FileOperations.Result result = operations.transfer(Collections.singletonList(location(source)), location(target), false, replace(), FileOperations.NONE);
        assertEquals("one\r\ntwo\n", contents(new File(target, "source/nested/中文 name.txt")));
        assertTrue(new File(nested, "中文 name.txt").isFile());
        assertEquals(3, result.files);
    }
    @Test public void keepBothDoesNotOverwriteExistingContents() throws Exception {
        File source = temporary.newFolder("source"), target = temporary.newFolder("target");
        File input = file(source, "a.txt", "new"); file(target, "a.txt", "old");
        operations.transfer(Collections.singletonList(location(input)), location(target), false,
            (a,b) -> FileOperations.Conflict.KEEP_BOTH, FileOperations.NONE);
        assertEquals("old", contents(new File(target,"a.txt")));
        assertEquals("new", contents(new File(target,"a (1).txt")));
    }
    @Test public void cancellationRetainsOldTargetAndCleansIncompleteCopy() throws Exception {
        File source = temporary.newFolder("source"), target = temporary.newFolder("target");
        File input = file(source,"large.bin", ""); Files.write(input.toPath(), new byte[150000]);
        file(target,"large.bin","original");
        FileOperations.Monitor cancelled = new FileOperations.Monitor() {
            long bytes;
            @Override public void checkCancelled() throws InterruptedIOException { if (bytes > 0) throw new InterruptedIOException("cancel"); }
            @Override public void progress(String name,long count,long files) { bytes=count; }
        };
        assertThrows(InterruptedIOException.class, () -> operations.transfer(Collections.singletonList(location(input)),location(target),true,replace(),cancelled));
        assertEquals("original", contents(new File(target,"large.bin")));
        assertTrue(input.exists());
        assertArrayEquals(new String[]{"large.bin"}, target.list());
    }
    @Test public void skippedChildPreventsRemovingAnySourceDirectory() throws Exception {
        File source=temporary.newFolder("source"), target=temporary.newFolder("target");
        file(source,"a","a");file(source,"b","b");
        File same=new File(target,"source");assertTrue(same.mkdir());file(same,"b","old");
        operations.transfer(Collections.singletonList(location(source)),location(target),true,
            (a,b) -> a.directory ? FileOperations.Conflict.REPLACE : FileOperations.Conflict.SKIP,FileOperations.NONE);
        assertTrue(new File(source,"a").exists()); assertTrue(new File(source,"b").exists());
        assertEquals("old",contents(new File(same,"b")));
    }
    @Test public void filesCreatedDuringMoveAreNotDeleted() throws Exception {
        File source=temporary.newFolder("source"), target=temporary.newFolder("target");file(source,"old","old data");
        FileOperations.Monitor concurrent=new FileOperations.Monitor() {
            boolean written;
            @Override public void checkCancelled() { }
            @Override public void progress(String name,long bytes,long files) {
                if(!written && bytes>0) { try { file(source,"new-live-data","keep me");written=true; } catch(IOException e){throw new RuntimeException(e);} }
            }
        };
        assertThrows(IOException.class, () -> operations.transfer(Collections.singletonList(location(source)),location(target),true,replace(),concurrent));
        assertEquals("keep me",contents(new File(source,"new-live-data")));
        assertEquals("old data",contents(new File(target,"source/old")));
    }
    @Test public void deletingSymlinkDoesNotTraverseItsTarget() throws Exception {
        File outside=temporary.newFolder("outside");file(outside,"precious","keep");
        File source=temporary.newFolder("source"); File link=new File(source,"link");
        Files.createSymbolicLink(link.toPath(),outside.toPath());
        operations.remove(Collections.singletonList(location(source)),FileOperations.NONE);
        assertEquals("keep",contents(new File(outside,"precious")));assertFalse(source.exists());
    }
    @Test public void copyingIntoDescendantIsRejectedBeforeChanges() throws Exception {
        File source=temporary.newFolder("source");File child=new File(source,"child");assertTrue(child.mkdir());file(source,"a","data");
        assertThrows(IOException.class, () -> operations.transfer(Collections.singletonList(location(source)),location(child),false,replace(),FileOperations.NONE));
        assertEquals(0,child.list().length);
    }
    @Test public void batchRenameSupportsCyclesAndRejectsDuplicatePlans() throws Exception {
        File source=temporary.newFolder("source");File a=file(source,"a","alpha"), b=file(source,"b","beta");
        Map<FileLocation,String> plan=new LinkedHashMap<>();plan.put(location(a),"b");plan.put(location(b),"a");
        operations.batchRename(plan,FileOperations.NONE);
        assertEquals("beta",contents(a));assertEquals("alpha",contents(b));
        assertThrows(IOException.class, () -> operations.renamePlan(Arrays.asList(location(a),location(b)),".+","same",1));
    }
    @Test public void failedBatchRenameRollsBackAllOriginalNames() throws Exception {
        File source=temporary.newFolder("source");File a=file(source,"a","alpha"), b=file(source,"b","beta");
        Map<FileLocation,String> plan=new LinkedHashMap<>();plan.put(location(a),"new-a");plan.put(location(b),"fail-once");
        fs.failName="fail-once";
        assertThrows(IOException.class, () -> operations.batchRename(plan,FileOperations.NONE));
        assertEquals("alpha",contents(a));assertEquals("beta",contents(b));assertEquals(2,source.list().length);
    }
    @Test public void checksumsUseCompleteContents() throws Exception {
        File input=file(temporary.getRoot(),"a","abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",operations.checksum(location(input),"SHA-256",FileOperations.NONE));
        assertEquals("352441c2",operations.checksum(location(input),"CRC32",FileOperations.NONE));
    }
    @Test public void filtersAndSearchRespectRegexHiddenAndDirectoryBoundaries() throws Exception {
        File root=temporary.newFolder("root");file(root,"note1.txt","first\nneedle\n");file(root,".hidden","secret");
        List<FileEntry> shown=FileListing.apply(fs.list(location(root)),"/\\d",false,FileListing.Sort.NAME,false);
        assertEquals(1,shown.size());assertEquals("note1.txt",shown.get(0).name);
        FileSearch.Result found=new FileSearch(fs).search(location(root),"note","needle",false,false,true,FileOperations.NONE,null);
        assertEquals(1,found.hits.size());assertEquals(2,found.hits.get(0).line);assertTrue(found.errors.isEmpty());
    }
    @Test public void locationTokensPreserveSafAncestryAndUnicode() {
        FileLocation root=new FileLocation("content://provider/tree/root/document/root","content://provider/tree/root",Collections.emptyList());
        FileLocation child=root.descendant("content://provider/document/%E4%B8%AD%20name");
        FileLocation restored=FileLocation.fromToken(child.token());
        assertEquals(child,restored);assertEquals(root,restored.recordedParent());
        assertThrows(IllegalArgumentException.class,()->FileLocation.validateName("../bad"));
    }

    @Test public void transferKeepsOriginalFailureWhenCleanupAlsoFails() throws Exception {
        File source = file(temporary.getRoot(), "source", "original");
        File destination = temporary.newFolder("destination");
        IOException original = new IOException("Source became unreadable");
        SecurityException cleanup = new SecurityException("Provider access expired");
        PlainFiles revoked = new PlainFiles() {
            boolean failed;
            @Override public InputStream read(FileLocation location) throws IOException {
                failed = true; throw original;
            }
            @Override public boolean exists(FileLocation location) {
                if (failed) throw cleanup;
                return super.exists(location);
            }
        };
        IOException reported = assertThrows(IOException.class, () -> new FileOperations(revoked).transfer(
            Collections.singletonList(location(source)), location(destination), false, replace(), FileOperations.NONE));
        assertSame(original, reported);
        assertArrayEquals(new Throwable[]{cleanup}, reported.getSuppressed());
        assertEquals("original", contents(source));
    }

    @Test public void movingThroughAnAliasOfTheSameParentPreservesTheFile() throws Exception {
        File real = temporary.newFolder("real");
        File folder = new File(real, "folder"); assertTrue(folder.mkdir());
        File source = file(folder, "note.txt", "precious");
        File alias = new File(temporary.getRoot(), "alias");
        Files.createSymbolicLink(alias.toPath(), real.toPath());
        for (boolean move : new boolean[]{false, true}) {
            IOException failure = assertThrows(IOException.class, () -> operations.transfer(
                Collections.singletonList(location(source)), location(new File(alias, "folder")), move, replace(), FileOperations.NONE));
            assertEquals("Source and destination are the same file", failure.getMessage());
            assertEquals("precious", contents(source));
            assertEquals(1, folder.list().length);
        }
    }

    @Test public void keepBothCanDuplicateInTheSameDirectory() throws Exception {
        File folder = temporary.newFolder("duplicate");
        File source = file(folder, "notes.txt", "original");
        operations.transfer(Collections.singletonList(location(source)), location(folder), false,
            (s, t) -> FileOperations.Conflict.KEEP_BOTH, FileOperations.NONE);
        assertEquals("original", contents(source));
        assertEquals("original", contents(new File(folder, "notes (1).txt")));
        assertEquals(2, folder.list().length);
    }

    @Test public void replacementRollsBackWhenAnUnrecognisedAliasRemovesTheSource() throws Exception {
        File server = temporary.newFolder("alias-server");
        File real = new File(server, "folder"); assertTrue(real.mkdir());
        File source = file(real, "notes.txt", "precious");
        File alias = new File(temporary.getRoot(), "other-address");
        Files.createSymbolicLink(alias.toPath(), server.toPath());
        FileAccess opaque = new PlainFiles() {
            @Override public boolean contains(FileLocation a, FileLocation b) { return a.equals(b); }
        };
        assertThrows(IOException.class, () -> new FileOperations(opaque).transfer(Collections.singletonList(location(source)),
            location(new File(alias, "folder")), true, replace(), FileOperations.NONE));
        assertEquals("precious", contents(source));
        assertEquals(1, real.list().length);
    }

    private static class PlainFiles implements FileAccess {
        String failName;
        @Override public FileEntry stat(FileLocation p) throws IOException {
            File f=p.file();BasicFileAttributes a=Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            return new FileEntry(p,f.getName(),f.isDirectory(),a.isSymbolicLink(),a.size(),a.lastModifiedTime().toMillis());
        }
        @Override public List<FileEntry> list(FileLocation p) throws IOException {
            File[] files=p.file().listFiles();if(files==null)throw new IOException("not a directory");
            List<FileEntry> result=new ArrayList<>();for(File f:files)result.add(stat(FileLocation.local(f)));return result;
        }
        @Override public FileLocation parent(FileLocation p) { File parent=p.file().getParentFile();return parent==null?null:FileLocation.local(parent); }
        @Override public FileLocation child(FileLocation p,String name) {FileLocation.validateName(name);return FileLocation.local(new File(p.file(),name));}
        @Override public InputStream read(FileLocation p) throws IOException {return Files.newInputStream(p.file().toPath());}
        @Override public OutputStream write(FileLocation p,boolean replace) throws IOException {return Files.newOutputStream(p.file().toPath(),replace?new StandardOpenOption[]{StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING}:new StandardOpenOption[]{StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW});}
        @Override public void mkdir(FileLocation p) throws IOException {Files.createDirectory(p.file().toPath());}
        @Override public FileLocation rename(FileLocation p,String name) throws IOException {
            if(name.equals(failName)){failName=null;throw new IOException("injected rename failure");}
            FileLocation target=child(parent(p),name);Files.move(p.file().toPath(),target.file().toPath());return target;
        }
        @Override public void delete(FileLocation p) throws IOException {Files.delete(p.file().toPath());}
        @Override public boolean exists(FileLocation p) {return Files.exists(p.file().toPath(),LinkOption.NOFOLLOW_LINKS);}
        @Override public boolean contains(FileLocation d,FileLocation p) throws IOException {return p.file().getCanonicalFile().toPath().startsWith(d.file().getCanonicalFile().toPath());}
        @Override public String readLink(FileLocation p) throws IOException {return Files.readSymbolicLink(p.file().toPath()).toString();}
        @Override public void symlink(String target,FileLocation p) throws IOException {Files.createSymbolicLink(p.file().toPath(),new File(target).toPath());}
        @Override public void setModified(FileLocation p,long time) throws IOException {Files.setLastModifiedTime(p.file().toPath(),java.nio.file.attribute.FileTime.fromMillis(time));}
    }
}
