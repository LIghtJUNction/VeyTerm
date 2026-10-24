package com.termux.app.core

/** The runtime family a package targets. File format alone is not enough. */
enum class ArchPackageRuntime {
    TERMUX_ANDROID,
    ARCH_GLIBC,
    /** AUR source is never admitted by the default compatibility policy. */
    AUR_SOURCE,
    SCRIPT_ONLY,
    UNKNOWN,
}

enum class ArchCompatibilityMode {
    NATIVE_PACMAN,
    PROOT_ARCH,
    UNSUPPORTED,
}

data class ArchPackageDescriptor(
    val name: String,
    val architecture: String? = null,
    val runtime: ArchPackageRuntime = ArchPackageRuntime.UNKNOWN,
    val requiresGlibc: Boolean = false,
    val requiresSystemd: Boolean = false,
    val requiresKernelModules: Boolean = false,
    val requiresRoot: Boolean = false,
) {
    init {
        require(name.isNotBlank())
    }
}

sealed interface ArchCompatibilityDecision {
    data object NativePacman : ArchCompatibilityDecision
    data class ProotArch(val reason: String) : ArchCompatibilityDecision
    data class Unsupported(val reason: String) : ArchCompatibilityDecision
}

/**
 * Compatibility is explicit at the package boundary. Native packages use the
 * Android/Termux ABI; glibc packages are isolated in a proot Arch rootfs.
 * Kernel modules, systemd services and real-root assumptions cannot be made
 * compatible by a userspace wrapper and are rejected with a useful reason.
 */
object ArchCompatibilityPolicy {
    fun decide(
        descriptor: ArchPackageDescriptor,
        deviceArchitecture: String? = null,
    ): ArchCompatibilityDecision {
        if (descriptor.requiresKernelModules) {
            return ArchCompatibilityDecision.Unsupported("package requires kernel modules")
        }
        if (descriptor.requiresSystemd) {
            return ArchCompatibilityDecision.Unsupported("systemd is unavailable in Android/proot mode")
        }
        if (descriptor.requiresRoot) {
            return ArchCompatibilityDecision.Unsupported("package requires real root privileges")
        }
        if (descriptor.requiresGlibc && descriptor.runtime == ArchPackageRuntime.TERMUX_ANDROID) {
            return ArchCompatibilityDecision.Unsupported(
                "glibc binaries must run in the separate proot Arch userland",
            )
        }
        if (descriptor.runtime == ArchPackageRuntime.TERMUX_ANDROID ||
            descriptor.runtime == ArchPackageRuntime.ARCH_GLIBC) {
            if (descriptor.architecture == null) {
                return ArchCompatibilityDecision.Unsupported("binary package architecture is not declared")
            }
            if (deviceArchitecture == null) {
                return ArchCompatibilityDecision.Unsupported("device architecture is unknown")
            }
        }
        if (deviceArchitecture != null && descriptor.architecture != null &&
            !architectureMatches(descriptor.architecture, deviceArchitecture)) {
            return ArchCompatibilityDecision.Unsupported(
                "package architecture ${descriptor.architecture} does not match $deviceArchitecture",
            )
        }
        return when (descriptor.runtime) {
            ArchPackageRuntime.TERMUX_ANDROID,
            ArchPackageRuntime.SCRIPT_ONLY -> ArchCompatibilityDecision.NativePacman
            ArchPackageRuntime.ARCH_GLIBC -> ArchCompatibilityDecision.ProotArch(
                "candidate only: verify dependencies and device behavior inside the isolated Arch glibc rootfs",
            )
            ArchPackageRuntime.AUR_SOURCE -> ArchCompatibilityDecision.Unsupported(
                "AUR recipes are disabled by default and require explicit source review",
            )
            ArchPackageRuntime.UNKNOWN -> ArchCompatibilityDecision.Unsupported(
                "package runtime is not declared",
            )
        }
    }

    fun prootDistroCommand(
        distro: String = "archlinux",
        arguments: List<String> = emptyList(),
    ): List<String> {
        require(distro.matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "invalid distro name" }
        return listOf("proot-distro", "login", distro) +
            if (arguments.isEmpty()) emptyList() else listOf("--") + arguments
    }

    fun prootDistroInstallCommand(distro: String = "archlinux"): List<String> {
        require(distro.matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "invalid distro name" }
        return listOf("proot-distro", "install", distro)
    }

    private fun architectureMatches(packageArchitecture: String, deviceArchitecture: String): Boolean {
        val packageArch = normalizeArchitecture(packageArchitecture)
        val deviceArch = normalizeArchitecture(deviceArchitecture)
        return packageArch == deviceArch ||
            (packageArch == "armv7" && deviceArch == "armv7") ||
            (packageArch == "x86" && deviceArch == "x86")
    }

    private fun normalizeArchitecture(value: String): String = when (value.lowercase()) {
        "aarch64", "arm64", "arm64-v8a" -> "aarch64"
        "arm", "armv7", "armeabi-v7a", "armhf" -> "armv7"
        "i686", "x86" -> "x86"
        "x86_64", "amd64" -> "x86_64"
        else -> value.lowercase()
    }
}
