package com.termux.app.files.fs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class FileOperationsOpaqueTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nestedUnicodeCopyPreservesNamesContentsAndSource() throws Exception {
        for (boolean archive : new boolean[]{true, false}) {
            OpaqueFiles fs = new OpaqueFiles(temporary.newFolder().toPath(), archive);
            FileLocation source = fs.createDirectory(fs.root, "资料 空间");
            FileLocation nested = fs.createDirectory(source, "子目录 🐱");
            byte[] bytes = "中文\r\n雪と海\n\u0000".getBytes(StandardCharsets.UTF_8);
            FileLocation input = fs.put(nested, "报告 %.txt", bytes);
            FileLocation target = fs.createDirectory(fs.root, "接收");

            FileOperations.Result result = new FileOperations(fs).transfer(
                Collections.singletonList(source), target, false,
                (a, b) -> { throw new AssertionError("Unexpected conflict"); }, FileOperations.NONE);

            assertEquals(Collections.singletonList("资料 空间"), fs.names(target));
            FileLocation copied = fs.child(target, "资料 空间");
            assertEquals(Collections.singletonList("子目录 🐱"), fs.names(copied));
            FileLocation copiedNested = fs.child(copied, "子目录 🐱");
            assertEquals(Collections.singletonList("报告 %.txt"), fs.names(copiedNested));
            FileLocation output = fs.child(copiedNested, "报告 %.txt");
            assertArrayEquals(bytes, fs.bytes(output));
            assertArrayEquals(bytes, fs.bytes(input));
            assertEquals(Collections.singletonList("子目录 🐱"), fs.names(source));
            assertEquals(Collections.singletonList("报告 %.txt"), fs.names(nested));
            assertEquals(3, result.files);
            assertEquals(bytes.length, result.bytes);
            assertTrue(result.outputs.contains(output));
        }
    }

    @Test public void keepBothPreservesOldFilesAndCreatesNamedFileAndDirectoryCopies() throws Exception {
        for (boolean archive : new boolean[]{true, false}) {
            OpaqueFiles fs = new OpaqueFiles(temporary.newFolder().toPath(), archive);
            FileOperations operations = new FileOperations(fs);
            FileLocation source = fs.createDirectory(fs.root, "来源");
            FileLocation target = fs.createDirectory(fs.root, "目标 深层");
            byte[] fresh = new byte[]{0, 1, (byte) 255, 42};
            byte[] old = "旧内容".getBytes(StandardCharsets.UTF_8);
            FileLocation input = fs.put(source, "账单 %.bin", fresh);
            fs.put(target, "账单 %.bin", old);
            fs.put(target, "账单 % (1).bin", new byte[]{7});

            FileOperations.Result result = operations.transfer(Collections.singletonList(input),
                target, false, (a, b) -> FileOperations.Conflict.KEEP_BOTH, FileOperations.NONE);

            assertEquals(Arrays.asList("账单 % (1).bin", "账单 % (2).bin", "账单 %.bin"), fs.names(target));
            assertArrayEquals(old, fs.bytes(fs.child(target, "账单 %.bin")));
            assertArrayEquals(new byte[]{7}, fs.bytes(fs.child(target, "账单 % (1).bin")));
            FileLocation output = fs.child(target, "账单 % (2).bin");
            assertArrayEquals(fresh, fs.bytes(output));
            assertArrayEquals(fresh, fs.bytes(input));
            assertEquals(Collections.singletonList(output), result.outputs);

            FileLocation oldDirectory = fs.createDirectory(target, "来源");
            fs.put(oldDirectory, "保留.txt", old);
            operations.transfer(Collections.singletonList(source), target, false,
                (a, b) -> FileOperations.Conflict.KEEP_BOTH, FileOperations.NONE);
            assertEquals(Collections.singletonList("保留.txt"), fs.names(oldDirectory));
            assertArrayEquals(old, fs.bytes(fs.child(oldDirectory, "保留.txt")));
            FileLocation newDirectory = fs.child(target, "来源 (1)");
            assertEquals(Collections.singletonList("账单 %.bin"), fs.names(newDirectory));
            assertArrayEquals(fresh, fs.bytes(fs.child(newDirectory, "账单 %.bin")));
            assertEquals(Arrays.asList("来源", "来源 (1)", "账单 % (1).bin", "账单 % (2).bin", "账单 %.bin"),
                fs.names(target));
        }
    }

    @Test public void opaqueDocumentRenameCycleAndMidCycleFailureRestoreExactNamesAndBytes() throws Exception {
        OpaqueFiles fs = new OpaqueFiles(temporary.newFolder().toPath(), false);
        FileOperations operations = new FileOperations(fs);
        FileLocation directory = fs.createDirectory(fs.root, "文档");
        String[] names = {"甲 %.txt", "乙 🐱.txt", "丙.txt"};
        byte[][] bytes = {{0, 1, (byte) 255}, {3, 4}, {5, 0, 6}};
        Map<FileLocation, String> cycle = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++)
            cycle.put(fs.put(directory, names[i], bytes[i]), names[(i + 1) % names.length]);

        operations.batchRename(cycle, FileOperations.NONE);
        List<String> expectedNames = Arrays.asList(names.clone());
        Collections.sort(expectedNames);
        assertEquals(expectedNames, fs.names(directory));
        for (int i = 0; i < names.length; i++)
            assertArrayEquals(bytes[i], fs.bytes(fs.child(directory, names[(i + 1) % names.length])));

        Map<FileLocation, String> reverse = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++)
            reverse.put(fs.child(directory, names[(i + 1) % names.length]), names[i]);
        fs.failNextRenameTo = names[2];
        List<String> completed = new ArrayList<>();
        FileOperations.Monitor monitor = new FileOperations.Monitor() {
            @Override public void checkCancelled() { }
            @Override public void progress(String name, long count, long files) { completed.add(name); }
        };
        IOException failure = assertThrows(IOException.class, () -> operations.batchRename(reverse, monitor));

        assertEquals("injected rename failure", failure.getMessage());
        assertEquals(Arrays.asList(names[0], names[1]), completed);
        assertEquals(0, failure.getSuppressed().length);
        assertNull(fs.failNextRenameTo);
        assertEquals(expectedNames, fs.names(directory));
        for (int i = 0; i < names.length; i++)
            assertArrayEquals(bytes[i], fs.bytes(fs.child(directory, names[(i + 1) % names.length])));
    }

    /** Real file storage behind a URI lookup table; callers must use stat for display names. */
    private static final class OpaqueFiles implements FileAccess {
        private final Path base;
        private final boolean archive;
        private final Map<Path, FileLocation> locations = new LinkedHashMap<>();
        private final Map<FileLocation, Path> paths = new LinkedHashMap<>();
        final FileLocation root;
        String failNextRenameTo;

        OpaqueFiles(Path base, boolean archive) throws IOException {
            this.base = base;
            this.archive = archive;
            root = location(base);
        }

        private FileLocation location(Path path) throws IOException {
            FileLocation existing = locations.get(path);
            if (existing != null) return existing;
            // Archive entries encode the WHOLE path, including every slash, after !/.
            // SAF document IDs contain no filename at all, even after URI decoding.
            String uri = archive
                ? "archive:file%3A%2Ffixture.zip!/" + URLEncoder.encode(
                    "归档 根/" + base.relativize(path), "UTF-8")
                : "content://opaque.test/document/" + UUID.randomUUID();
            FileLocation result = new FileLocation(uri);
            locations.put(path, result);
            paths.put(result, path);
            return result;
        }

        private Path path(FileLocation location) throws IOException {
            Path path = paths.get(location);
            if (path == null) throw new IOException("Unknown document ID");
            return path;
        }

        FileLocation put(FileLocation directory, String name, byte[] bytes) throws IOException {
            FileLocation file = child(directory, name);
            try (OutputStream out = write(file, false)) { out.write(bytes); }
            return file;
        }

        byte[] bytes(FileLocation file) throws IOException { return Files.readAllBytes(path(file)); }

        List<String> names(FileLocation directory) throws IOException {
            List<String> names = new ArrayList<>();
            for (FileEntry entry : list(directory)) names.add(entry.name);
            Collections.sort(names);
            return names;
        }

        @Override public FileEntry stat(FileLocation location) throws IOException {
            Path path = path(location);
            return new FileEntry(location, path.getFileName().toString(), Files.isDirectory(path),
                false, Files.size(path), Files.getLastModifiedTime(path).toMillis());
        }

        @Override public List<FileEntry> list(FileLocation directory) throws IOException {
            List<FileEntry> entries = new ArrayList<>();
            try (Stream<Path> children = Files.list(path(directory))) {
                for (Path child : (Iterable<Path>) children::iterator) entries.add(stat(location(child)));
            }
            return entries;
        }

        @Override public FileLocation parent(FileLocation location) throws IOException {
            Path path = path(location);
            return path.equals(base) ? null : location(path.getParent());
        }

        @Override public FileLocation child(FileLocation directory, String name) throws IOException {
            FileLocation.validateName(name);
            return location(path(directory).resolve(name));
        }

        @Override public InputStream read(FileLocation location) throws IOException {
            return Files.newInputStream(path(location));
        }

        @Override public OutputStream write(FileLocation location, boolean replace) throws IOException {
            return Files.newOutputStream(path(location), replace
                ? new StandardOpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING}
                : new StandardOpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW});
        }

        @Override public void mkdir(FileLocation location) throws IOException { Files.createDirectory(path(location)); }

        @Override public FileLocation rename(FileLocation source, String name) throws IOException {
            FileLocation.validateName(name);
            if (name.equals(failNextRenameTo)) {
                failNextRenameTo = null;
                throw new IOException("injected rename failure");
            }
            Path target = path(source).resolveSibling(name);
            Files.move(path(source), target);
            return location(target);
        }

        @Override public void delete(FileLocation location) throws IOException { Files.delete(path(location)); }
        @Override public boolean exists(FileLocation location) throws IOException { return Files.exists(path(location)); }
        @Override public void setModified(FileLocation location, long time) throws IOException {
            Files.setLastModifiedTime(path(location), java.nio.file.attribute.FileTime.fromMillis(time));
        }
        @Override public boolean contains(FileLocation directory, FileLocation location) throws IOException {
            return path(location).startsWith(path(directory));
        }
    }
}
