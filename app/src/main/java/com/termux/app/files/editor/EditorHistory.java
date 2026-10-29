package com.termux.app.files.editor;

import java.util.ArrayDeque;

/** Bounded page history; redo is invalidated by a new edit. */
public final class EditorHistory {
    private final ArrayDeque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private int characters;
    public void record(String before) {
        undo.addLast(before); characters += before.length(); redo.clear();
        while (undo.size() > 50 || characters > 2 * 1024 * 1024) characters -= undo.removeFirst().length();
    }
    public String undo(String current) {
        if (undo.isEmpty()) return current;
        redo.addLast(current); String value = undo.removeLast(); characters -= value.length(); return value;
    }
    public String redo(String current) {
        if (redo.isEmpty()) return current;
        undo.addLast(current); characters += current.length(); return redo.removeLast();
    }
    public void clear() { undo.clear(); redo.clear(); characters = 0; }
}
