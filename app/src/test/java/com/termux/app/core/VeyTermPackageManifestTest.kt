package com.termux.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.termux.shared.termux.TermuxBootstrap

class VeyTermPackageManifestTest {
    @Test
    fun pacmanManifestDeclaresUnprivilegedParuBootstrap() {
        val manifest = VeyTermPackageManifests.pacmanAndroid7
        assertEquals(manifest, VeyTermPackageManifests.default)
        assertEquals("pacman-android-7", manifest.variant)
        assertEquals(VeyTermPackageManager.PACMAN, manifest.manager)
        assertTrue("paru" in manifest.requiredPackages)
        assertTrue("sudo" in manifest.requiredPackages)
        assertTrue("make" in manifest.requiredPackages)
        assertTrue("python-pip" in manifest.requiredPackages)
        assertTrue("termux-keyring" in manifest.requiredPackages)
        assertFalse("archlinux-keyring" in manifest.requiredPackages)
        assertEquals("pkg.tar.xz", manifest.manager.packageFormat)
        assertTrue(manifest.supportsUnprivilegedBuilds)
        assertFalse(manifest.aurEnabledByDefault)
        assertTrue(manifest.sourceReady)
        assertEquals(TermuxBootstrap.PackageManager.PACMAN,
            TermuxBootstrap.PackageManager.managerOf("pacman"))
        assertEquals(TermuxBootstrap.PackageVariant.PACMAN_ANDROID_7,
            TermuxBootstrap.PackageVariant.variantOf("pacman-android-7"))
    }
}
