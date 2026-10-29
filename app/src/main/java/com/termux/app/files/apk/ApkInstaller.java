package com.termux.app.files.apk;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.util.Base64;
import java.io.*;
import java.util.*;

/** One full install session containing a validated base and explicitly selected matching splits. */
public final class ApkInstaller {
    private ApkInstaller() {}
    static final String PREFS = "apk_install_status";
    private static final int SUBMITTED = -100;

    static String action(Context context) { return context.getPackageName() + ".apk.INSTALL_STATUS"; }
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static int install(Context context, List<ApkPackageSet.Part> selected) throws Exception {
        ComponentName receiver = new ComponentName(context, ApkInstallStatusReceiver.class);
        ActivityInfo info = context.getPackageManager().getReceiverInfo(receiver, 0);
        if (info.exported || !info.enabled) throw new IOException("Install status receiver must be enabled and private");
        List<ApkPackageSet.Part> parts = ApkPackageSet.validate(selected, Build.VERSION.SDK_INT, Arrays.asList(Build.SUPPORTED_ABIS));
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(parts.get(0).packageName);
        long total = 0; for (ApkPackageSet.Part part : parts) total += part.file.length();
        params.setSize(total);
        if (Build.VERSION.SDK_INT >= 31) Api31.requireUserAction(params);
        int id = installer.createSession(params); boolean committed = false;
        String token = UUID.randomUUID().toString(); PendingIntent callback = null;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            int index = 0;
            for (ApkPackageSet.Part part : parts) {
                ApkZip.checkCancelled();
                ApkZip.requireFingerprint(part.file, part.fingerprint);
                String name = index++ == 0 ? "base.apk" : String.format(Locale.ROOT, "split_%03d.apk", index);
                try (InputStream in = new FileInputStream(part.file); OutputStream out = session.openWrite(name, 0, part.file.length())) {
                    ApkZip.copy(in, out, ApkZip.MAX_TOTAL); session.fsync(out);
                }
                ApkZip.requireFingerprint(part.file, part.fingerprint);
            }
            Intent status = new Intent(action(context)).setComponent(receiver)
                .setData(new Uri.Builder().scheme("apk-install-status").authority(context.getPackageName()).appendPath(token).build());
            int flags = Build.VERSION.SDK_INT >= 31 ? Api31.mutableFlag() : 0;
            callback = PendingIntent.getBroadcast(context, id, status, flags);
            // Persist before commit: callbacks can arrive before commit() returns or after process death.
            SharedPreferences.Editor record = prefs(context).edit().putString("latest", token)
                .putInt(token + ".session", id).putInt(token + ".status", SUBMITTED)
                .putString(token + ".package", parts.get(0).packageName)
                .putString(token + ".message", "Submitted to Android; waiting for confirmation/result");
            if (!record.commit()) throw new IOException("Cannot persist installation status");
            ApkZip.checkCancelled(); session.commit(callback.getIntentSender()); committed = true;
            return id;
        } finally {
            if (!committed) {
                try { installer.abandonSession(id); } catch (RuntimeException ignored) { }
                if (callback != null) callback.cancel();
                prefs(context).edit().putInt(token + ".status", PackageInstaller.STATUS_FAILURE)
                    .putString(token + ".message", "Session was not committed").commit();
            }
        }
    }

    /** Status is authoritative only when the system has delivered the status receiver callback. */
    public static String status(Context context) {
        SharedPreferences p = prefs(context); String token = p.getString("latest", null);
        if (token == null) return "";
        int status = p.getInt(token + ".status", SUBMITTED);
        String label = status == PackageInstaller.STATUS_SUCCESS ? "Android confirmed installation succeeded"
            : status == PackageInstaller.STATUS_PENDING_USER_ACTION ? "Android requires user confirmation"
            : status == SUBMITTED ? "Installation submitted; completion not confirmed"
            : "Android installation failed/cancelled (status " + status + ")";
        return label + "\n" + p.getString(token + ".package", "") + ": " + p.getString(token + ".message", "");
    }

    /** Caller must be a visible Activity. A background callback never forces a confirmation UI open. */
    public static Intent confirmation(Context context) throws Exception {
        SharedPreferences p = prefs(context); String token = p.getString("latest", null);
        if (token == null || p.getInt(token + ".status", SUBMITTED) != PackageInstaller.STATUS_PENDING_USER_ACTION) return null;
        String encoded = p.getString(token + ".confirmation", null);
        if (encoded == null) return null;
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
        Parcel parcel = Parcel.obtain();
        try { parcel.unmarshall(bytes, 0, bytes.length); parcel.setDataPosition(0); return Intent.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }

    static String encodeConfirmation(Intent intent) throws IOException {
        Parcel parcel = Parcel.obtain();
        try {
            intent.writeToParcel(parcel, 0); byte[] bytes = parcel.marshall();
            if (bytes.length > 512 * 1024) throw new IOException("System confirmation intent exceeds persistence limit");
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } finally { parcel.recycle(); }
    }

    private static final class Api31 {
        static void requireUserAction(PackageInstaller.SessionParams params) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        static int mutableFlag() { return PendingIntent.FLAG_MUTABLE; }
    }
}
