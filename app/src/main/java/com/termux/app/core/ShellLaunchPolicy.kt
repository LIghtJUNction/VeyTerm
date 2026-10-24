package com.termux.app.core

/**
 * Pure policy for selecting the interactive shell shipped by the bootstrap.
 * File probing remains in the existing Termux session runner so this class is
 * safe to use from tests and from a background bootstrap task.
 */
enum class VeyTermShell(val binaryName: String, val rcFileName: String) {
    ZSH("zsh", ".zshrc"),
    BASH("bash", ".bashrc"),
    FISH("fish", ".config/fish/config.fish"),
    SH("sh", "")
}

data class ShellSelection(
    val shell: VeyTermShell,
    val fallbackUsed: Boolean,
)

object ShellLaunchPolicy {
    /** zsh is the product default; bash is the first compatibility fallback. */
    val priority: List<VeyTermShell> = listOf(
        VeyTermShell.ZSH,
        VeyTermShell.BASH,
        VeyTermShell.FISH,
        VeyTermShell.SH,
    )

    fun choose(availableBinaries: Set<String>): ShellSelection {
        val normalized = availableBinaries.mapTo(HashSet()) { it.trim().lowercase() }
        val selectedIndex = priority.indexOfFirst { it.binaryName in normalized }
        val index = if (selectedIndex >= 0) selectedIndex else priority.lastIndex
        return ShellSelection(priority[index], index > 0)
    }
}
