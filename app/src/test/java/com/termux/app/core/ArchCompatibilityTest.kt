package com.termux.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchCompatibilityTest {
    @Test
    fun nativeTermuxPackageUsesNativePacman() {
        val result = ArchCompatibilityPolicy.decide(
            ArchPackageDescriptor(
                name = "zsh",
                architecture = "aarch64",
                runtime = ArchPackageRuntime.TERMUX_ANDROID,
            ),
            deviceArchitecture = "arm64-v8a",
        )

        assertEquals(ArchCompatibilityDecision.NativePacman, result)
    }

    @Test
    fun archGlibcPackageIsIsolatedInProot() {
        val result = ArchCompatibilityPolicy.decide(
            ArchPackageDescriptor(
                name = "ripgrep",
                architecture = "aarch64",
                runtime = ArchPackageRuntime.ARCH_GLIBC,
            ),
            deviceArchitecture = "aarch64",
        )

        assertTrue(result is ArchCompatibilityDecision.ProotArch)
        assertTrue((result as ArchCompatibilityDecision.ProotArch).reason.contains("candidate only"))
    }

    @Test
    fun glibcPackageCannotRunInNativeProfile() {
        val result = ArchCompatibilityPolicy.decide(
            ArchPackageDescriptor(
                name = "glibc-only-tool",
                architecture = "aarch64",
                runtime = ArchPackageRuntime.TERMUX_ANDROID,
                requiresGlibc = true,
            ),
            deviceArchitecture = "aarch64",
        )

        assertTrue(result is ArchCompatibilityDecision.Unsupported)
        assertTrue((result as ArchCompatibilityDecision.Unsupported).reason.contains("glibc"))
    }

    @Test
    fun systemdKernelAndRootRequirementsAreUnsupported() {
        val descriptors = listOf(
            ArchPackageDescriptor("systemd-service", runtime = ArchPackageRuntime.ARCH_GLIBC, requiresSystemd = true),
            ArchPackageDescriptor("kernel-module", runtime = ArchPackageRuntime.ARCH_GLIBC, requiresKernelModules = true),
            ArchPackageDescriptor("root-daemon", runtime = ArchPackageRuntime.ARCH_GLIBC, requiresRoot = true),
        )

        descriptors.forEach { descriptor ->
            assertTrue(ArchCompatibilityPolicy.decide(descriptor) is ArchCompatibilityDecision.Unsupported)
        }
    }

    @Test
    fun aurIsNotImplicitlyCompatible() {
        val result = ArchCompatibilityPolicy.decide(
            ArchPackageDescriptor("aur-package", runtime = ArchPackageRuntime.AUR_SOURCE),
        )
        assertTrue(result is ArchCompatibilityDecision.Unsupported)
        assertTrue((result as ArchCompatibilityDecision.Unsupported).reason.contains("AUR"))
    }

    @Test
    fun unknownRuntimeAndForeignArchitectureAreRejected() {
        val unknown = ArchCompatibilityPolicy.decide(
            ArchPackageDescriptor("unknown-runtime"),
        )
        assertTrue(unknown is ArchCompatibilityDecision.Unsupported)

        val foreignAbi = ArchCompatibilityPolicy.decide(
            ArchPackageDescriptor(
                name = "foreign",
                architecture = "riscv64",
                runtime = ArchPackageRuntime.TERMUX_ANDROID,
            ),
            deviceArchitecture = "aarch64",
        )
        assertTrue(foreignAbi is ArchCompatibilityDecision.Unsupported)
    }

    @Test
    fun binaryWithoutAbiOrDeviceArchitectureIsRejected() {
        val noPackageAbi = ArchCompatibilityPolicy.decide(
            ArchPackageDescriptor(name = "unknown-abi", runtime = ArchPackageRuntime.ARCH_GLIBC),
            deviceArchitecture = "aarch64",
        )
        assertTrue(noPackageAbi is ArchCompatibilityDecision.Unsupported)

        val noDeviceAbi = ArchCompatibilityPolicy.decide(
            ArchPackageDescriptor(
                name = "unknown-device",
                architecture = "aarch64",
                runtime = ArchPackageRuntime.ARCH_GLIBC,
            ),
        )
        assertTrue(noDeviceAbi is ArchCompatibilityDecision.Unsupported)
    }

    @Test
    fun prootDistroCommandIsShellSafe() {
        assertEquals(
            listOf("proot-distro", "login", "archlinux", "--", "pacman", "-Syu"),
            ArchCompatibilityPolicy.prootDistroCommand(
                arguments = listOf("pacman", "-Syu"),
            ),
        )
        assertEquals(
            listOf("proot-distro", "install", "archlinux"),
            ArchCompatibilityPolicy.prootDistroInstallCommand(),
        )
    }
}
