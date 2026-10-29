package com.termux.app.files.privileged;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** Metadata/errors stay bounded; file contents never pass through a Binder parcel. */
interface IPrivilegedFiles {
    Bundle execute(String operation, in Bundle arguments) = 0;
    ParcelFileDescriptor openFile(String path, boolean write, boolean replace, out Bundle result) = 1;
    // Shizuku's reserved destroy transaction (wire transaction = 16777115).
    void destroy() = 16777114;
}
