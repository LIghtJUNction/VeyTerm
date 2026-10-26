package com.termux.app.core

/** Package manager identity of the native Android bootstrap. */
enum class VeyTermPackageManager(
    val command: String,
    val packageFormat: String,
) {
    PACMAN("pacman", "pkg.tar.xz"),
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
    val default: VeyTermBootstrapManifest
        get() = pacmanAndroid7

    val pacmanAndroid7: VeyTermBootstrapManifest = VeyTermBootstrapManifest(
        variant = "pacman-android-7",
        manager = VeyTermPackageManager.PACMAN,
        prefix = "/data/data/com.veyterm/files/usr",
        requiredPackages = listOf(
            "pacman",
            "paru",
            "sudo",
            "make",
            "binutils",
            "autoconf",
            "automake",
            "bison",
            "flex",
            "git",
            "ca-certificates",
            "termux-keyring",
            "openssl",
            "curl",
            "zstd",
            "fakeroot",
            "proot",
            "proot-distro",
            "python",
            "python-pip",
        ),
        requiredFiles = listOf(
            "etc/pacman.conf",
            "etc/makepkg.conf",
            "etc/pacman.d/gnupg",
            "var/lib/pacman/sync",
            "bin/pacman",
            "bin/paru",
            "bin/sudo",
            "bin/proot",
            "bin/proot-distro",
            "bin/python3",
            "bin/pip",
            "share/pacman/keyrings/veyterm.gpg",
        ),
        supportsUnprivilegedBuilds = true,
        aurEnabledByDefault = false,
        sourceReady = true,
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
