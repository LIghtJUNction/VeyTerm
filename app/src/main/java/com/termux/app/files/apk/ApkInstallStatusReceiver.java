package com.termux.app.files.apk;

import android.content.*;
import android.content.pm.PackageInstaller;
import android.net.Uri;

/** Manifest exported=false, no intent filters. Only our narrowly addressed mutable PendingIntent. */
public final class ApkInstallStatusReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ApkInstaller.action(context).equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null || !"apk-install-status".equals(data.getScheme()) || !context.getPackageName().equals(data.getAuthority())
            || data.getPathSegments().size() != 1) return;
        String token = data.getLastPathSegment(); SharedPreferences p = ApkInstaller.prefs(context);
        int expected = p.getInt(token + ".session", -1);
        if (expected < 0 || expected != intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2)
            || !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return;
        int previous = p.getInt(token + ".status", -100);
        if (previous >= 0) return; // Terminal callbacks cannot be overwritten by late pending broadcasts.
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status < PackageInstaller.STATUS_PENDING_USER_ACTION) return;
        SharedPreferences.Editor edit = p.edit().putInt(token + ".status", status)
            .putString(token + ".message", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            .remove(token + ".confirmation");
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) try {
                edit.putString(token + ".confirmation", ApkInstaller.encodeConfirmation(confirmation));
            } catch (Exception e) {
                edit.putString(token + ".message", "System confirmation could not be retained: " + e.getMessage() + "; installation is not confirmed");
            }
        }
        edit.commit();
    }
}
