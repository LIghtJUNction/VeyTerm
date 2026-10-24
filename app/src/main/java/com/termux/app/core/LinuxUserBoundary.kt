package com.termux.app.core

/** Explicit result for sudo requests; Android permissions are never treated as root. */
sealed interface SudoDecision {
    data object ExecuteWithSudo : SudoDecision
    data object ExecuteThroughSu : SudoDecision
    data object DeniedOnNonRootDevice : SudoDecision
    data object SudoUnavailable : SudoDecision
}

/** The privilege mechanisms that can actually be observed by the app. */
enum class SudoMode {
    REAL_SUDO,
    ROOT_SU,
    APP_SANDBOX,
    UNAVAILABLE,
}

data class SudoCapability(
    val mode: SudoMode,
    val executable: String? = null,
    val explanation: String,
)

/**
 * Keeps the Android privilege boundary explicit. A `su` binary is only useful
 * when its probe succeeded; merely finding a file named `su` is not enough.
 */
object SudoCapabilityDetector {
    fun detect(
        sudoInstalled: Boolean,
        suInstalled: Boolean,
        suCanElevate: Boolean,
        sudoCanElevate: Boolean = false,
    ): SudoCapability = when {
        sudoInstalled && sudoCanElevate -> SudoCapability(
            mode = SudoMode.REAL_SUDO,
            executable = "sudo",
            explanation = "sudo passed an elevation probe; command execution remains subject to its policy.",
        )
        suInstalled && suCanElevate -> SudoCapability(
            mode = SudoMode.ROOT_SU,
            executable = "su",
            explanation = "a working su provider can elevate this session.",
        )
        sudoInstalled || suInstalled -> SudoCapability(
            mode = SudoMode.APP_SANDBOX,
            executable = if (sudoInstalled) "sudo" else "su",
            explanation = "a privilege command was found but did not grant root; the app remains sandboxed.",
        )
        else -> SudoCapability(
            mode = SudoMode.UNAVAILABLE,
            explanation = "no usable sudo or su provider is available on this device.",
        )
    }
}

object SudoPolicy {
    /** Compatibility helper used by the UI policy tests. */
    fun decide(deviceIsRoot: Boolean, sudoInstalled: Boolean): SudoDecision = when {
        !sudoInstalled -> SudoDecision.SudoUnavailable
        deviceIsRoot -> SudoDecision.ExecuteWithSudo
        else -> SudoDecision.DeniedOnNonRootDevice
    }

    fun decide(capability: SudoCapability): SudoDecision = when (capability.mode) {
        SudoMode.REAL_SUDO -> SudoDecision.ExecuteWithSudo
        SudoMode.ROOT_SU -> SudoDecision.ExecuteThroughSu
        SudoMode.APP_SANDBOX -> SudoDecision.DeniedOnNonRootDevice
        SudoMode.UNAVAILABLE -> SudoDecision.SudoUnavailable
    }
}

/** Metadata shown by the Linux-style file view instead of Android internals. */
data class LinuxFileEntry(
    val virtualPath: String,
    val name: String,
    val owner: String,
    val group: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val permissions: String,
    val symbolicLinkTarget: String? = null,
    val type: LinuxFileType = LinuxFileType.REGULAR,
) {
    val isHidden: Boolean get() = name.startsWith('.') && name != "." && name != ".."
}

enum class LinuxFileType {
    DIRECTORY,
    REGULAR,
    SYMBOLIC_LINK,
    OTHER,
}

data class LinuxFileViewOptions(
    val includeHidden: Boolean = true,
    val longFormat: Boolean = true,
    val directoriesFirst: Boolean = true,
)

/** Pure file-view policy used by the terminal drawer and AI context panel. */
object LinuxFileView {
    val standardOptions = LinuxFileViewOptions()

    fun visibleEntries(
        entries: Iterable<LinuxFileEntry>,
        options: LinuxFileViewOptions = standardOptions,
    ): List<LinuxFileEntry> = entries
        .filter { options.includeHidden || !it.isHidden }
        .sortedWith(compareBy<LinuxFileEntry> {
            if (options.directoriesFirst && it.type == LinuxFileType.DIRECTORY) 0 else 1
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** Render the stable columns used by `ls -l`-style output. */
    fun formatLong(entry: LinuxFileEntry): String {
        val kind = when (entry.type) {
            LinuxFileType.DIRECTORY -> 'd'
            LinuxFileType.SYMBOLIC_LINK -> 'l'
            LinuxFileType.REGULAR -> '-'
            LinuxFileType.OTHER -> '?'
        }
        val target = entry.symbolicLinkTarget?.let { " -> $it" }.orEmpty()
        return "$kind${entry.permissions.padEnd(9, '-').take(9)} " +
            "${entry.owner} ${entry.group} ${entry.sizeBytes} ${entry.virtualPath}$target"
    }
}

/**
 * Maps the private Android paths to the paths a Linux user should see. The
 * mapping is deliberately virtual: commands still execute against the real
 * app-private paths, while UI/AI context never leaks `/data/data/...`.
 */
object LinuxPathMapper {
    const val DEFAULT_PREFIX = "/data/data/com.veyterm/files/usr"
    const val DEFAULT_HOME = "/data/data/com.veyterm/files/home"
    const val DEFAULT_INTERNAL_ROOT = "/data/data/com.veyterm/files"

    fun toVirtual(
        realPath: String,
        prefix: String = DEFAULT_PREFIX,
        home: String = DEFAULT_HOME,
        internalRoot: String = DEFAULT_INTERNAL_ROOT,
    ): String? {
        val real = normalizeAbsolute(realPath) ?: return null
        val normalizedPrefix = normalizeAbsolute(prefix) ?: return null
        val normalizedHome = normalizeAbsolute(home) ?: return null
        val normalizedRoot = normalizeAbsolute(internalRoot) ?: return null

        relativeTo(real, normalizedHome)?.let { relative ->
            return if (relative.isEmpty()) "~" else "~/$relative"
        }
        if (real == normalizedRoot) return "/"
        relativeTo(real, normalizedPrefix)?.let { relative ->
            return prefixRelativeToVirtual(relative)
        }
        // Do not expose unrelated Android private paths or external provider
        // paths as if they were part of the Linux filesystem.
        return null
    }

    fun toReal(
        virtualPath: String,
        prefix: String = DEFAULT_PREFIX,
        home: String = DEFAULT_HOME,
        internalRoot: String = DEFAULT_INTERNAL_ROOT,
    ): String? {
        val virtual = normalizeVirtual(virtualPath) ?: return null
        val normalizedPrefix = normalizeAbsolute(prefix) ?: return null
        val normalizedHome = normalizeAbsolute(home) ?: return null
        val normalizedRoot = normalizeAbsolute(internalRoot) ?: return null

        if (virtual == "~") return normalizedHome
        if (virtual.startsWith("~/")) return appendSafe(normalizedHome, virtual.removePrefix("~/"))
        if (virtual == "/") return normalizedRoot

        val relative = virtual.removePrefix("/")
        return when {
            relative == "home" || relative.startsWith("home/") ->
                appendSafe(normalizedHome, relative.removePrefix("home").removePrefix("/"))
            relative == "etc" || relative.startsWith("etc/") ->
                appendSafe(normalizedPrefix, relative)
            relative == "var" || relative.startsWith("var/") ->
                appendSafe(normalizedPrefix, relative)
            relative == "tmp" || relative.startsWith("tmp/") ->
                appendSafe(normalizedPrefix, relative)
            relative == "usr" -> normalizedPrefix
            relative.startsWith("usr/") ->
                appendSafe(normalizedPrefix, relative.removePrefix("usr/"))
            else -> appendSafe(normalizedPrefix, relative)
        }
    }

    private fun prefixRelativeToVirtual(relative: String): String {
        if (relative.isEmpty()) return "/usr"
        val first = relative.substringBefore('/')
        val rest = relative.removePrefix(first).removePrefix("/")
        val virtualRoot = when (first) {
            "bin", "sbin", "lib", "libexec", "share", "include" -> "/usr/$first"
            "etc", "var", "tmp" -> "/$first"
            else -> "/$first"
        }
        return if (rest.isEmpty()) virtualRoot else "$virtualRoot/$rest"
    }

    private fun appendSafe(base: String, relative: String): String? {
        val clean = normalizeRelative(relative) ?: return null
        return if (clean.isEmpty()) base else "$base/$clean"
    }

    private fun relativeTo(path: String, base: String): String? {
        if (path == base) return ""
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else null
    }

    private fun normalizeAbsolute(path: String): String? {
        if (!path.startsWith('/') || path.contains('\u0000')) return null
        val clean = normalizeRelative(path.removePrefix("/")) ?: return null
        return if (clean.isEmpty()) "/" else "/$clean"
    }

    private fun normalizeVirtual(path: String): String? {
        if (path == "~") return "~"
        if (path.startsWith("~/")) {
            val clean = normalizeRelative(path.removePrefix("~/")) ?: return null
            return if (clean.isEmpty()) "~" else "~/$clean"
        }
        return normalizeAbsolute(path)
    }

    private fun normalizeRelative(path: String): String? {
        if (path.contains('\u0000')) return null
        val components = path.split('/').filter { it.isNotEmpty() }
        if (components.any { it == "." || it == ".." }) return null
        return components.joinToString("/")
    }
}
