package com.termux.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLaunchPolicyTest {
    @Test
    fun zshIsPreferredWhenPresent() {
        val selection = ShellLaunchPolicy.choose(setOf("bash", "zsh"))
        assertEquals(VeyTermShell.ZSH, selection.shell)
        assertFalse(selection.fallbackUsed)
    }

    @Test
    fun bashIsTheFirstFallbackWhenZshIsMissing() {
        val selection = ShellLaunchPolicy.choose(setOf("bash"))
        assertEquals(VeyTermShell.BASH, selection.shell)
        assertTrue(selection.fallbackUsed)
    }
}
