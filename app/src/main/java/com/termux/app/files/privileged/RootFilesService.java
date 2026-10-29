package com.termux.app.files.privileged;

import android.content.Intent;
import android.os.IBinder;
import com.topjohnwu.superuser.ipc.RootService;

/** Instantiated by libsu only, never Context.startService(). */
public final class RootFilesService extends RootService {
    private PrivilegedFileService files;

    @Override public IBinder onBind(Intent intent) {
        files = new PrivilegedFileService(getApplicationInfo().uid, true);
        return files;
    }

    @Override public void onDestroy() {
        if (files != null) files.closeService();
        super.onDestroy();
    }
}
