package com.termux.app.files.editor;

import org.junit.Test;
import static org.junit.Assert.*;

public class EditorHistoryTest {
    @Test public void undoRedoAndBranchingEdits() {
        EditorHistory history = new EditorHistory(); history.record("a"); history.record("ab");
        assertEquals("ab", history.undo("abc")); assertEquals("a", history.undo("ab"));
        assertEquals("ab", history.redo("a")); history.record("ab");
        assertEquals("new branch", history.redo("new branch"));
    }
    @Test public void boundedHistoryRetainsMostRecentFiftyEdits() {
        EditorHistory history = new EditorHistory();
        for (int i = 0; i < 100; i++) history.record(Integer.toString(i));
        String value = "100"; for (int i = 0; i < 100; i++) value = history.undo(value);
        assertEquals("50", value);
    }
    @Test public void clearPreventsUndoAcrossPages() {
        EditorHistory history = new EditorHistory(); history.record("page one"); history.clear();
        assertEquals("page two", history.undo("page two"));
    }
}
