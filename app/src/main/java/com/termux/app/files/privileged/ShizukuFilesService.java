package com.termux.app.files.privileged;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;

/** Shizuku 13+ invokes the Context constructor, not an Android service lifecycle. */
public final class ShizukuFilesService extends PrivilegedFileService {
    private final int clientUid;

    public ShizukuFilesService(Context context) {
        super(context.getApplicationInfo().uid, false);
        clientUid = context.getApplicationInfo().uid;
    }

    @Override public void destroy() {
        int uid = Binder.getCallingUid();
        if (uid != clientUid && uid != 0 && uid != 2000)
            throw new SecurityException("Only the client or Shizuku may stop this service");
        closeService();
        // Let Binder write its reply before terminating the dedicated UserService process.
        new Handler(Looper.getMainLooper()).postDelayed(() -> System.exit(0), 100);
    }
}
