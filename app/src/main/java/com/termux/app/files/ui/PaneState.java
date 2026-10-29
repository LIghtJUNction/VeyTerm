package com.termux.app.files.ui;

import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;
import com.termux.app.files.fs.FileListing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure workspace state; Android views never own history or selection. Main-thread confined. */
public final class PaneState {
    public enum Sort { NAME, SIZE, MODIFIED, EXTENSION }
    public FileLocation directory;
    public final List<FileLocation> back = new ArrayList<>();
    public final List<FileLocation> forward = new ArrayList<>();
    public final Set<String> selected = new LinkedHashSet<>();
    public List<FileEntry> entries = new ArrayList<>();
    public Sort sort = Sort.NAME;
    public boolean descending;
    public boolean hidden;
    public String filter = "";
    public boolean regex;
    public String error;
    public String searchLabel;
    public boolean loading;
    public long revision;
    public int scrollPosition;
    public int scrollOffset;
    private String anchor;
    private List<FileEntry> cachedSource, cachedVisible;
    private Sort cachedSort;
    private boolean cachedDescending, cachedHidden, cachedRegex;
    private String cachedFilter;

    public PaneState(FileLocation initial) { directory = initial; }

    public void navigate(FileLocation next) {
        if (!directory.equals(next)) {
            back.add(directory);
            if (back.size() > 100) back.remove(0);
            forward.clear();
            directory = next;
        }
        resetListing();
    }

    public boolean history(boolean backwards) {
        List<FileLocation> source = backwards ? back : forward;
        if (source.isEmpty()) return false;
        (backwards ? forward : back).add(directory);
        directory = source.remove(source.size() - 1);
        resetListing();
        return true;
    }

    private void resetListing() {
        entries = new ArrayList<>();
        selected.clear();
        anchor = null;
        error = null;
        searchLabel = null;
        scrollPosition = 0;
        scrollOffset = 0;
        revision++;
    }

    public void accept(List<FileEntry> result) {
        entries = new ArrayList<>(result);
        Set<String> present = new HashSet<>();
        for (FileEntry entry : entries) present.add(entry.location.uri);
        selected.retainAll(present);
        if (!present.contains(anchor)) anchor = null;
        error = null;
        loading = false;
    }

    public List<FileEntry> visible() {
        if (cachedSource == entries && cachedSort == sort && cachedDescending == descending
            && cachedHidden == hidden && cachedRegex == regex && filter.equals(cachedFilter)) return cachedVisible;
        // Always pass an explicit regex marker so literal names beginning with ! or / stay literal.
        List<FileEntry> result = FileListing.apply(entries, "/" + (regex ? filter : Pattern.quote(filter)), hidden,
            FileListing.Sort.valueOf(sort.name()), descending);
        cachedSource = entries; cachedSort = sort; cachedDescending = descending;
        cachedHidden = hidden; cachedRegex = regex; cachedFilter = filter;
        cachedVisible = Collections.unmodifiableList(result);
        return cachedVisible;
    }

    public void select(FileEntry entry, boolean range) {
        List<FileEntry> visible = visible();
        int start = -1, end = visible.indexOf(entry);
        for (int i = 0; i < visible.size(); i++) if (visible.get(i).location.uri.equals(anchor)) start = i;
        if (range && start >= 0 && end >= 0) {
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) selected.add(visible.get(i).location.uri);
        } else {
            if (!selected.remove(entry.location.uri)) selected.add(entry.location.uri);
            anchor = entry.location.uri;
        }
    }

    public void mark(FileEntry entry) {
        if (selected.isEmpty()) anchor = entry.location.uri;
        selected.add(entry.location.uri);
    }

    public void selectAll(boolean invert) {
        for (FileEntry entry : visible()) {
            if (!invert || !selected.remove(entry.location.uri)) selected.add(entry.location.uri);
        }
    }

    public void sameExtension(FileEntry reference) {
        for (FileEntry entry : visible()) if (!entry.directory && reference.extension().equals(entry.extension()))
            selected.add(entry.location.uri);
    }

    public List<FileEntry> selection() {
        List<FileEntry> result = new ArrayList<>();
        for (FileEntry entry : visible()) if (selected.contains(entry.location.uri)) result.add(entry);
        return result;
    }
}
