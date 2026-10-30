package com.termux.app.files.fs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public final class FileListing {
    public enum Sort { NAME, SIZE, MODIFIED, EXTENSION }
    private FileListing() { }
    public static List<FileEntry> apply(List<FileEntry> entries, String filter, boolean hidden, Sort sort, boolean reverse) {
        String value = filter == null ? "" : filter;
        boolean invert = value.startsWith("!");
        if (invert) value = value.substring(1);
        boolean regex = value.startsWith("/");
        Pattern pattern = Pattern.compile(regex ? value.substring(1) : Pattern.quote(value), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<FileEntry> result = new ArrayList<>();
        for (FileEntry entry : entries) {
            if (!hidden && entry.name.startsWith(".")) continue;
            if (pattern.matcher(entry.name).find() != invert) result.add(entry);
        }
        Comparator<FileEntry> byName = (a, b) -> {
            int compared = a.name.compareToIgnoreCase(b.name);
            return compared != 0 ? compared : a.name.compareTo(b.name);
        };
        Comparator<FileEntry> order;
        switch (sort) {
            case SIZE: order = Comparator.comparingLong(entry -> entry.size); break;
            case MODIFIED: order = Comparator.comparingLong(entry -> entry.modified); break;
            case EXTENSION: order = Comparator.comparing(FileEntry::extension); break;
            default: order = byName;
        }
        order = order.thenComparing(byName);
        if (reverse) order = order.reversed();
        result.sort(Comparator.comparing((FileEntry entry) -> !entry.directory).thenComparing(order));
        return result;
    }
}
