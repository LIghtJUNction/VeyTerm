package com.termux.app.files.apk;

import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.core.content.FileProvider;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.security.auth.x500.X500Principal;

/** Android facade. All methods involving files/PackageManager/crypto must run on a worker thread. */
public final class ApkTools {
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String DEVICE_KEY_ALIAS = "veyterm-apk-user-v1";
    private ApkTools() {}

    @SuppressWarnings("deprecation")
    public static String metadata(Context context, File apk) throws IOException {
        ApkZip.checkCancelled();
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES
            | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS | PackageManager.GET_META_DATA
            | PackageManager.GET_CONFIGURATIONS | PackageManager.GET_INSTRUMENTATION
            | PackageManager.MATCH_DISABLED_COMPONENTS;
        PackageManager pm = context.getPackageManager();
        PackageInfo p = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (p == null || p.applicationInfo == null) throw new IOException("Android could not parse this APK manifest");
        ApplicationInfo app = p.applicationInfo; app.sourceDir = apk.getAbsolutePath(); app.publicSourceDir = app.sourceDir;
        StringBuilder s = new StringBuilder();
        s.append("File: ").append(apk).append("\nBytes: ").append(apk.length()).append("\nLabel: ").append(app.loadLabel(pm))
            .append("\nPackage: ").append(p.packageName).append("\nVersion: ").append(p.versionName).append(" (")
            .append(Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode).append(")")
            .append("\nTarget SDK: ").append(app.targetSdkVersion);
        if (Build.VERSION.SDK_INT >= 24) s.append("\nMinimum SDK: ").append(app.minSdkVersion);
        s.append("\nDebuggable: ").append((app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .append("\nNative extraction: ").append((app.flags & 0x10000000) != 0)
            .append("\nSplits: ").append(Arrays.toString(p.splitNames)).append("\n\nRequested permissions:\n");
        if (p.requestedPermissions != null) for (String permission : p.requestedPermissions) s.append(permission).append('\n');
        s.append("\nDeclared permissions:\n");
        if (p.permissions != null) for (PermissionInfo permission : p.permissions)
            s.append(permission.name).append(" protectionLevel=0x").append(Integer.toHexString(permission.protectionLevel)).append('\n');
        appendComponents(s, "Activities", p.activities); appendComponents(s, "Receivers", p.receivers);
        appendComponents(s, "Services", p.services); appendComponents(s, "Providers", p.providers);
        s.append("\nInstrumentation:\n");
        if (p.instrumentation != null) for (InstrumentationInfo i : p.instrumentation) s.append(i.name).append(" -> ").append(i.targetPackage).append('\n');
        s.append("\nRequired features:\n");
        if (p.reqFeatures != null) for (FeatureInfo f : p.reqFeatures) s.append(f).append('\n');
        return s.toString();
    }

    private static void appendComponents(StringBuilder s, String title, ComponentInfo[] components) {
        s.append('\n').append(title).append(":\n");
        if (components != null) for (ComponentInfo c : components) {
            s.append(c.name).append(" exported=").append(c.exported).append(" enabled=").append(c.enabled)
                .append(" process=").append(c.processName);
            if (c instanceof ActivityInfo) s.append(" permission=").append(((ActivityInfo)c).permission);
            if (c instanceof ServiceInfo) s.append(" permission=").append(((ServiceInfo)c).permission);
            if (c instanceof ProviderInfo) {
                ProviderInfo p = (ProviderInfo)c;
                s.append(" authority=").append(p.authority).append(" read=").append(p.readPermission).append(" write=").append(p.writePermission);
            }
            s.append('\n');
        }
    }

    public static final class InstalledApp {
        public final String packageName, label;
        public final int splitCount;
        InstalledApp(PackageInfo p, PackageManager pm) {
            packageName = p.packageName; label = p.applicationInfo.loadLabel(pm).toString();
            splitCount = p.applicationInfo.splitSourceDirs == null ? 0 : p.applicationInfo.splitSourceDirs.length;
        }
        @Override public String toString() { return label + "\n" + packageName + " (" + splitCount + " splits)"; }
    }

    public static List<InstalledApp> installedApps(Context context) throws IOException {
        List<InstalledApp> result = new ArrayList<>(); PackageManager pm = context.getPackageManager();
        for (PackageInfo p : pm.getInstalledPackages(0)) {
            ApkZip.checkCancelled(); if (p.applicationInfo != null) result.add(new InstalledApp(p, pm));
        }
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label)); return result;
    }

    /** Publish a directory containing base.apk plus every installed split. Never claim base alone is complete. */
    public static List<File> extractInstalled(Context context, String packageName, File outputDirectory) throws Exception {
        PackageInfo p = context.getPackageManager().getPackageInfo(packageName, 0);
        if (p.applicationInfo == null) throw new IOException("Installed package has no APK paths");
        File temp = ApkZip.temporary(outputDirectory);
        try {
            if (!temp.delete() || !temp.mkdir()) throw new IOException("Cannot create extraction directory");
            List<String> names = new ArrayList<>();
            copyInstalled(p.applicationInfo.sourceDir, new File(temp, "base.apk")); names.add("base.apk");
            String[] splits = p.applicationInfo.splitSourceDirs;
            if (splits != null) for (int i = 0; i < splits.length; i++) {
                String name = String.format(Locale.ROOT, "split_%03d.apk", i);
                copyInstalled(splits[i], new File(temp, name)); names.add(name);
            }
            ApkZip.publish(temp, outputDirectory);
            List<File> result = new ArrayList<>(); for (String n : names) result.add(new File(outputDirectory, n)); return result;
        } finally { ApkFormats.deleteScratch(temp); }
    }

    private static void copyInstalled(String source, File output) throws IOException {
        if (source == null) throw new IOException("Missing installed APK path");
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(output)) {
            ApkZip.copy(in, out, ApkZip.MAX_TOTAL); out.getFD().sync();
        }
        ApkZip.test(output);
    }

    /** A locally generated, non-exportable RSA identity. It persists across launches, not app-data deletion. */
    @SuppressWarnings("deprecation")
    public static synchronized ApkSignatures.Identity deviceIdentity(Context context) throws Exception {
        ApkZip.checkCancelled(); KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(DEVICE_KEY_ALIAS)) {
            Calendar end = Calendar.getInstance(); end.add(Calendar.YEAR, 25);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            if (Build.VERSION.SDK_INT >= 23) {
                generator.initialize(new KeyGenParameterSpec.Builder(DEVICE_KEY_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=VeyTerm user APK key"))
                    .setCertificateSerialNumber(new BigInteger(128, new SecureRandom())).setCertificateNotAfter(end.getTime()).build());
            } else {
                generator.initialize(new KeyPairGeneratorSpec.Builder(context.getApplicationContext()).setAlias(DEVICE_KEY_ALIAS)
                    .setKeySize(2048).setSubject(new X500Principal("CN=VeyTerm user APK key"))
                    .setSerialNumber(new BigInteger(128, new SecureRandom())).setStartDate(new Date())
                    .setEndDate(end.getTime()).build());
            }
            generator.generateKeyPair();
        }
        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate c : ks.getCertificateChain(DEVICE_KEY_ALIAS)) chain.add((X509Certificate)c);
        return new ApkSignatures.Identity((PrivateKey)ks.getKey(DEVICE_KEY_ALIAS, null), chain);
    }

    /** Copy into the only FileProvider-exposed directory; don't expose arbitrary application paths. */
    public static File prepareInstall(Context context, File apk) throws IOException {
        File dir = new File(context.getCacheDir(), "apk-install");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create install staging directory");
        File[] old = dir.listFiles(); long expiry = System.currentTimeMillis() - 86400000L;
        if (old != null) for (File f : old)
            if (f.getName().matches("apk-[0-9a-f-]+\\.apk") && f.lastModified() < expiry) f.delete();
        File output = new File(dir, "apk-" + UUID.randomUUID() + ".apk");
        File temp = ApkZip.temporary(output);
        try {
            try (InputStream in = new FileInputStream(apk); FileOutputStream out = new FileOutputStream(temp)) {
                ApkZip.copy(in, out, ApkZip.MAX_TOTAL); out.getFD().sync();
            }
            ApkZip.test(temp); ApkZip.publish(temp, output); return output;
        } finally { temp.delete(); }
    }

    public static Intent installIntent(Context context, File preparedApk) throws IOException {
        File root = new File(context.getCacheDir(), "apk-install").getCanonicalFile();
        if (!root.equals(preparedApk.getCanonicalFile().getParentFile()) || !preparedApk.isFile())
            throw new IOException("Use prepareInstall before creating an install intent");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(FileProvider.getUriForFile(context, context.getPackageName() + ".apktools.files", preparedApk),
            "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); return intent;
    }
}
