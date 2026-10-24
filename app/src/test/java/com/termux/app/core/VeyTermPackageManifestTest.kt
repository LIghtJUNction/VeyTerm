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
        assertTrue("base-devel" in manifest.requiredPackages)
        assertTrue(manifest.supportsUnprivilegedBuilds)
        assertFalse(manifest.aurEnabledByDefault)
        assertFalse(manifest.sourceReady)
        assertEquals(TermuxBootstrap.PackageManager.PACMAN,
            TermuxBootstrap.PackageManager.managerOf("pacman"))
        assertEquals(TermuxBootstrap.PackageVariant.PACMAN_ANDROID_7,
            TermuxBootstrap.PackageVariant.variantOf("pacman-android-7"))
    }
}
