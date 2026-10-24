package com.termux.app.core

/** Package manager identity used by the planned Arch-based bootstrap. */
enum class VeyTermPackageManager(
    val command: String,
    val packageFormat: String,
) {
    PACMAN("pacman", "pkg.tar.zst"),
    APT("apt", "deb"),
}

data class VeyTermBootstrapManifest(
    val variant: String,
    val manager: VeyTermPackageManager,
    val prefix: String,
    val requiredPackages: List<String>,
    val requiredFiles: List<String>,
    val supportsUnprivilegedBuilds: Boolean,
    val aurEnabledByDefault: Boolean,
    val sourceReady: Boolean,
) {
    init {
        require(variant.isNotBlank())
        require(prefix == "/data/data/com.veyterm/files/usr")
        require(requiredPackages.distinct() == requiredPackages)
    }
}

object VeyTermPackageManifests {
    /** Product target. The APK baseline remains explicit below until sourceReady. */
    val default: VeyTermBootstrapManifest
        get() = pacmanAndroid7

    /**
     * The target PACMAN bootstrap contract. It is deliberately marked
     * sourceReady=false until a separately reproducible Arch/Termux packages
     * source publishes signed Android archives and checksums.
     */
    val pacmanAndroid7: VeyTermBootstrapManifest = VeyTermBootstrapManifest(
        variant = "pacman-android-7",
        manager = VeyTermPackageManager.PACMAN,
        prefix = "/data/data/com.veyterm/files/usr",
        requiredPackages = listOf(
            "pacman",
            "paru",
            "sudo",
            "base-devel",
            "git",
            "ca-certificates",
            "archlinux-keyring",
            "openssl",
            "curl",
            "zstd",
            "fakeroot",
        ),
        requiredFiles = listOf(
            "etc/pacman.conf",
            "etc/makepkg.conf",
            "etc/pacman.d/gnupg",
            "var/lib/pacman/sync",
            "bin/pacman",
            "bin/paru",
            "bin/sudo",
        ),
        supportsUnprivilegedBuilds = true,
        aurEnabledByDefault = false,
        sourceReady = false,
    )

    /** The currently shipped apt APK remains a compatibility baseline only. */
    val aptBaseline: VeyTermBootstrapManifest = VeyTermBootstrapManifest(
        variant = "apt-android-7",
        manager = VeyTermPackageManager.APT,
        prefix = "/data/data/com.veyterm/files/usr",
        requiredPackages = emptyList(),
        requiredFiles = emptyList(),
        supportsUnprivilegedBuilds = false,
        aurEnabledByDefault = false,
        sourceReady = true,
    )
}
