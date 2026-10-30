package com.termux.app.files.fs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class FileSearch {
    public static final class Hit {
        public final FileEntry entry;
        public final long line;
        public Hit(FileEntry entry, long line) { this.entry = entry; this.line = line; }
    }
    public static final class Result {
        public final List<Hit> hits = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public long examined;
    }
    public interface Listener { void found(Hit hit); }
    private final FileAccess fs;
    public FileSearch(FileAccess fs) { this.fs = fs; }

    public Result search(FileLocation root, String name, String content, boolean regex,
                         boolean caseSensitive, boolean recursive, FileOperations.Monitor monitor,
                         Listener listener) throws IOException {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern names = Pattern.compile(regex ? name : Pattern.quote(name), flags);
        Pattern contents = content.isEmpty() ? null : Pattern.compile(regex ? content : Pattern.quote(content), flags);
        Result result = new Result();
        ArrayDeque<FileLocation> directories = new ArrayDeque<>();
        directories.add(root);
        while (!directories.isEmpty()) {
            monitor.checkCancelled();
            FileLocation directory = directories.removeFirst();
            List<FileEntry> entries;
            try { entries = fs.list(directory); }
            catch (IOException e) { result.errors.add(directory.uri + ": " + e.getMessage()); continue; }
            for (FileEntry entry : entries) {
                monitor.checkCancelled();
                result.examined++;
                monitor.progress(entry.name, 0, result.examined);
                if (recursive && entry.directory && !entry.symbolicLink) directories.addLast(entry.location);
                if (!names.matcher(entry.name).find()) continue;
                long line = 0;
                if (contents != null) {
                    if (entry.directory || entry.symbolicLink) continue;
                    try { line = firstMatch(entry.location, contents, monitor); }
                    catch (InterruptedIOException e) { throw e; }
                    catch (IOException e) { result.errors.add(entry.name + ": " + e.getMessage()); continue; }
                    if (line < 0) continue;
                }
                Hit hit = new Hit(entry, line);
                result.hits.add(hit);
                if (listener != null) listener.found(hit);
            }
        }
        return result;
    }

    private long firstMatch(FileLocation location, Pattern pattern, FileOperations.Monitor monitor) throws IOException {
        try (InputStream stream = fs.read(location);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder()
                 .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            StringBuilder line = new StringBuilder();
            long number = 1;
            char[] chunk = new char[8192];
            int length;
            while ((length = reader.read(chunk)) >= 0) {
                monitor.checkCancelled();
                for (int i = 0; i < length; i++) {
                    char value = chunk[i];
                    if (value == '\n') {
                        if (pattern.matcher(line).find()) return number;
                        line.setLength(0);
                        number++;
                    } else {
                        line.append(value);
                        if (line.length() > 1024 * 1024)
                            throw new IOException("A line exceeds 1 MiB; search this file in its paged editor instead");
                    }
                }
            }
            return pattern.matcher(line).find() ? number : -1;
        }
    }
}
