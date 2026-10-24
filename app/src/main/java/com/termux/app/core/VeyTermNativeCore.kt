package com.termux.app.core

/**
 * Optional Rust boundary. The existing PTY and bootstrap JNI libraries are
 * intentionally untouched until Android-target Rust builds are reproducible.
 */
object VeyTermNativeCore {
    const val LIBRARY_NAME: String = "veyterm_core"

    @Volatile
    private var loaded = false

    @JvmStatic
    fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary(LIBRARY_NAME)
                    loaded = true
                }
            }
        }
    }

    @JvmStatic
    fun abiVersion(): Int {
        ensureLoaded()
        return nativeCoreAbiVersion()
    }

    @JvmStatic
    private external fun nativeCoreAbiVersion(): Int
}
