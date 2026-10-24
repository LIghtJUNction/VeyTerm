package com.termux.app.terminal.io

/**
 * A user-configured hardware-key shortcut.
 *
 * This is deliberately a small Kotlin value type. The shortcut parser and the
 * terminal input path remain Java for now, so the fields stay Java-visible.
 */
data class KeyboardShortcut(
    @JvmField val codePoint: Int,
    @JvmField val shortcutAction: Int,
)
