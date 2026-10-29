package com.termux.app.files.ui;

import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class PaneStateTest {
    private static FileLocation location(String path) { return FileLocation.local(new File(path)); }
    private static FileEntry entry(String name, boolean directory, long size, long modified) {
        return new FileEntry(location("/home/" + name), name, directory, false, size, modified);
    }
    private static List<String> names(List<FileEntry> entries) {
        return entries.stream().map(entry -> entry.name).collect(Collectors.toList());
    }
    private static PaneState populated() {
        PaneState pane = new PaneState(location("/home"));
        pane.accept(Arrays.asList(entry("z.txt", false, 1, 30), entry("a.txt", false, 9, 10),
            entry("b.java", false, 5, 20), entry("folder", true, 0, 0), entry(".secret", false, 0, 0)));
        return pane;
    }

    @Test public void columnsKeepIndependentHistoryAndNavigationClearsForward() {
        PaneState left = populated(), right = populated();
        left.navigate(location("/tmp")); left.navigate(location("/var"));
        assertTrue(left.history(true)); assertEquals(location("/tmp"), left.directory);
        assertEquals(location("/home"), right.directory); assertTrue(right.back.isEmpty());
        assertTrue(left.history(false)); assertEquals(location("/var"), left.directory);
        left.history(true); left.navigate(location("/opt")); assertFalse(left.history(false));
    }

    @Test public void currentFolderRefreshDoesNotDuplicateHistory() {
        PaneState pane = populated(); pane.navigate(pane.directory);
        assertTrue(pane.back.isEmpty()); assertEquals(0, pane.entries.size());
    }

    @Test public void historiesAreBoundedAndPreserveSafAncestry() {
        PaneState pane = populated();
        for (int i = 0; i < 120; i++) pane.navigate(location("/tmp/" + i));
        assertEquals(100, pane.back.size());
        FileLocation tree = new FileLocation("content://provider/document/root", "content://provider/tree/root", Collections.emptyList());
        FileLocation child = tree.descendant("content://provider/document/child");
        pane.navigate(child); pane.navigate(location("/tmp")); pane.history(true);
        FileLocation restored = FileLocation.fromToken(pane.directory.token());
        assertEquals(tree.uri, restored.recordedParent().uri); assertEquals(tree.treeUri, restored.treeUri);
    }

    @Test public void directoriesStayFirstInEverySortDirection() {
        PaneState pane = populated();
        for (PaneState.Sort sort : PaneState.Sort.values()) for (boolean reverse : new boolean[] {false, true}) {
            pane.sort = sort; pane.descending = reverse;
            assertEquals("folder", pane.visible().get(0).name);
        }
        pane.sort = PaneState.Sort.SIZE; pane.descending = true;
        assertEquals(Arrays.asList("folder", "a.txt", "b.java", "z.txt"), names(pane.visible()));
    }

    @Test public void literalAndRegexFiltersAreDistinctAndCaseInsensitive() {
        PaneState pane = populated(); pane.filter = ".TXT";
        assertEquals(Arrays.asList("a.txt", "z.txt"), names(pane.visible()));
        pane.regex = true; pane.filter = "^[az]\\.txt$";
        assertEquals(2, pane.visible().size());
        pane.filter = "^b"; assertEquals(Collections.singletonList("b.java"), names(pane.visible()));
    }

    @Test public void leadingFilterSyntaxIsLiteralUnlessRegexWasRequested() {
        PaneState pane = populated(); pane.accept(Arrays.asList(entry("!wow", false, 0, 0), entry("wow", false, 0, 0)));
        pane.filter = "!"; assertEquals(Collections.singletonList("!wow"), names(pane.visible()));
        pane.filter = "/"; assertTrue(pane.visible().isEmpty());
    }

    @Test public void rangeSelectUsesVisibleSortedRowsAndKeepsAnchor() {
        PaneState pane = populated(); List<FileEntry> visible = pane.visible();
        pane.select(visible.get(1), false); pane.select(visible.get(3), true);
        assertEquals(Arrays.asList("a.txt", "b.java", "z.txt"), names(pane.selection()));
        pane.selected.clear(); pane.select(visible.get(2), true);
        assertEquals(Arrays.asList("a.txt", "b.java"), names(pane.selection()));
    }

    @Test public void contextMenuSelectionRetainsRangeAnchor() {
        PaneState pane = populated(); List<FileEntry> visible = pane.visible();
        pane.mark(visible.get(1)); pane.mark(visible.get(3)); pane.select(visible.get(3), true);
        assertEquals(Arrays.asList("a.txt", "b.java", "z.txt"), names(pane.selection()));
    }

    @Test public void selectionAllInvertAndHiddenToggleOnlyAffectVisibleEntries() {
        PaneState pane = populated(); pane.selectAll(false); assertEquals(4, pane.selection().size());
        pane.selectAll(true); assertTrue(pane.selection().isEmpty());
        pane.hidden = true; pane.selectAll(false); assertEquals(5, pane.selection().size());
        pane.hidden = false; assertEquals(4, pane.selection().size());
        pane.filter = "a.txt"; pane.selectAll(true); assertTrue(pane.selection().isEmpty());
        pane.filter = ""; assertEquals(3, pane.selection().size());
    }

    @Test public void sameExtensionExcludesFoldersAndUsesCaseInsensitiveSuffix() {
        PaneState pane = populated(); pane.sameExtension(entry("SAMPLE.TXT", false, 0, 0));
        assertEquals(Arrays.asList("a.txt", "z.txt"), names(pane.selection()));
    }

    @Test public void refreshPrunesDeletedSelectionAndNavigationClearsSelectionAndSearch() {
        PaneState pane = populated(); pane.selectAll(false); pane.searchLabel = "query";
        pane.accept(Collections.singletonList(entry("a.txt", false, 9, 10)));
        assertEquals(Collections.singletonList("a.txt"), names(pane.selection()));
        pane.scrollPosition = 6; pane.navigate(location("/tmp"));
        assertTrue(pane.selection().isEmpty()); assertNull(pane.searchLabel); assertEquals(0, pane.scrollPosition);
    }
}
