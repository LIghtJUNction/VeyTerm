package com.termux.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinuxUserBoundaryTest {
    @Test
    fun nonRootNeverPretendsToElevate() {
        assertEquals(SudoDecision.DeniedOnNonRootDevice, SudoPolicy.decide(deviceIsRoot = false, sudoInstalled = true))
        assertEquals(SudoDecision.ExecuteWithSudo, SudoPolicy.decide(deviceIsRoot = true, sudoInstalled = true))
        assertEquals(SudoMode.ROOT_SU, SudoCapabilityDetector.detect(
            sudoInstalled = false,
            suInstalled = true,
            suCanElevate = true,
        ).mode)
        assertEquals(SudoMode.REAL_SUDO, SudoCapabilityDetector.detect(
            sudoInstalled = true,
            suInstalled = false,
            suCanElevate = false,
            sudoCanElevate = true,
        ).mode)
        assertEquals(SudoMode.APP_SANDBOX, SudoCapabilityDetector.detect(
            sudoInstalled = true,
            suInstalled = false,
            suCanElevate = false,
        ).mode)
        assertEquals(SudoDecision.DeniedOnNonRootDevice, SudoPolicy.decide(
            SudoCapabilityDetector.detect(
                sudoInstalled = false,
                suInstalled = true,
                suCanElevate = false,
            )))
    }

    @Test
    fun privatePathsUseLinuxNamesWithoutLeakingAndroidInternals() {
        assertEquals("/etc/pacman.conf", LinuxPathMapper.toVirtual("/data/data/com.veyterm/files/usr/etc/pacman.conf"))
        assertEquals("/usr/bin/zsh", LinuxPathMapper.toVirtual("/data/data/com.veyterm/files/usr/bin/zsh"))
        assertEquals("~", LinuxPathMapper.toVirtual("/data/data/com.veyterm/files/home"))
        assertEquals("/data/data/com.veyterm/files/usr/bin/zsh", LinuxPathMapper.toReal("/usr/bin/zsh"))
        assertEquals("/data/data/com.veyterm/files/home/project", LinuxPathMapper.toReal("~/project"))
        assertEquals("/data/data/com.veyterm/files", LinuxPathMapper.toReal("/"))
        assertNull(LinuxPathMapper.toReal("/../data/data/com.veyterm/files"))
    }

    @Test
    fun standardFileViewIncludesHiddenEntriesAndDirectoriesFirst() {
        val entries = listOf(
            LinuxFileEntry("~/notes.txt", "notes.txt", "u0_a1", "u0_a1", 4, 0, "rw-r--r--"),
            LinuxFileEntry("~/.zshrc", ".zshrc", "u0_a1", "u0_a1", 4, 0, "rw-------"),
            LinuxFileEntry("~/src", "src", "u0_a1", "u0_a1", 0, 0, "rwxr-xr-x", type = LinuxFileType.DIRECTORY),
        )
        assertEquals(listOf("src", ".zshrc", "notes.txt"), LinuxFileView.visibleEntries(entries).map { it.name })
        assertEquals("drwxr-xr-x u0_a1 u0_a1 0 ~/src", LinuxFileView.formatLong(entries[2]))
    }
}
