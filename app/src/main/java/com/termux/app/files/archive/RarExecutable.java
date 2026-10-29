package com.termux.app.files.archive;

import java.io.File;
import java.io.IOException;

/** Optional, replaceable public UnRAR command. Never loaded into this JVM.
 * Configure once from FileStores using ApplicationInfo.nativeLibraryDir.
 * Null disables the command and retains the BSD libarchive default.
 */
public final class RarExecutable {
    private static volatile File executable;
    private RarExecutable() {}

    public static void setExecutable(File file) throws IOException {
        if (file == null) { executable = null; return; }
        if (!file.isAbsolute() || !file.isFile() || !file.canExecute())
            throw new IOException("RAR command must be an explicit absolute executable file");
        executable = file.getCanonicalFile();
    }

    static File getExecutable() { return executable; }
}
