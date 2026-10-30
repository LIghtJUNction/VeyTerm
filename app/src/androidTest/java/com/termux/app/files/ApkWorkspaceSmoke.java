package com.termux.app.files;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.SystemClock;

import com.android.apksig.ApkVerifier;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.ResXmlDocument;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;
import com.termux.app.files.apk.ApkFormats;
import com.termux.app.files.apk.ApkInstaller;
import com.termux.app.files.apk.ApkPackageSet;
import com.termux.app.files.apk.ApkSignatures;
import com.termux.app.files.apk.ApkTools;
import com.termux.app.files.apk.ApkZip;
import com.termux.app.files.decompile.DecompilerSession;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.security.auth.x500.X500Principal;

/** Platform runner. Fixtures and assertions execute in the target Android process, without JUnit. */
public final class ApkWorkspaceSmoke extends Instrumentation {
    private static final String PREFIX = "apk-device-";
    private static final String CLASS = "fixture.DeviceSmoke";
    private static final String TYPE = "Lfixture/DeviceSmoke;";
    private static final String BEFORE = "android-smoke-before";
    private static final String AFTER = "android-smoke-after";
    private static final DecompilerSession.Cancellation CANCEL = DecompilerSession.THREAD_CANCEL;
    private Bundle args;
    private final Bundle result = new Bundle();
    private Context target;
    private int assertions;
    private String stage = "arguments";

    @Override public void onCreate(Bundle arguments) {
        args = arguments == null ? new Bundle() : arguments;
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        boolean ok = false;
        try {
            target = getTargetContext();
            check("com.veyterm".equals(target.getPackageName()), "target context must be com.veyterm");
            String scenario = args.getString("scenario", "core");
            if ("status".equals(scenario)) installStatus();
            else {
                check("core".equals(scenario) || "install".equals(scenario), "scenario core/install/status");
                core("install".equals(scenario));
            }
            ok = true;
        } catch (Throwable failure) {
            result.putString("failure", stage + ": " + android.util.Log.getStackTraceString(failure));
        }
        result.putString("result", ok ? "PASS" : "FAIL");
        result.putInt("assertions", assertions);
        result.putString("stage", stage);
        result.putString("stream", "\nApkWorkspaceSmoke " + (ok ? "PASS" : "FAIL")
                + " assertions=" + assertions + " stage=" + stage + "\n"
                + result.getString("failure", "") + "\n");
        finish(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void core(boolean install) throws Exception {
        String id = UUID.randomUUID().toString();
        File root = new File(target.getCacheDir().getCanonicalFile(), PREFIX + id);
        check(root.mkdir(), "exclusive random cache root");
        File work = new File(root, "work");
        boolean retain = false;
        try {
            check(work.mkdir(), "private scratch directory");
            result.putString("run_id", id);
            String pkg = "com.veyterm.smoke.apkdevice.p" + id.replace("-", "");
            result.putString("fixture_package", pkg);
            stage("smali");
            File dex = dex(work);
            stage("axml-arsc");
            byte[] manifest = manifest(pkg);
            byte[] resources = resources(pkg);
            File unsigned = new File(work, "unsigned.apk");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(unsigned))) {
                entry(zip, "AndroidManifest.xml", manifest);
                entry(zip, "resources.arsc", resources);
                entry(zip, "classes.dex", read(dex));
                entry(zip, "assets/proof.txt", AFTER.getBytes(StandardCharsets.UTF_8));
            }
            stage("source-xml");
            String sourceXml = com.termux.app.files.apk.ApkSourceXml.decode(unsigned, "AndroidManifest.xml");
            check(sourceXml.contains("Device smoke after"), "source XML decodes the actual manifest");
            byte[] sourceEdited = com.termux.app.files.apk.ApkSourceXml.compile(unsigned,
                sourceXml.replace("Device smoke after", "Source XML device"));
            check(ApkFormats.xmlPreview(sourceEdited).contains("Source XML device"), "source XML compiles the changed Android label");
            stage("device-keystore-signing");
            File deviceSigned = new File(work, "device-key.apk");
            ApkSignatures.sign(unsigned, deviceSigned, ApkTools.deviceIdentity(target), true, true);
            ApkVerifier.Result deviceVerified = ApkSignatures.verify(deviceSigned, new File(deviceSigned.getPath() + ".idsig"));
            check(deviceVerified.isVerified() && deviceVerified.isVerifiedUsingV4Scheme(), "AndroidKeyStore device key signs a verified APK and v4 sidecar");
            File deviceAgain = new File(work, "device-key-again.apk");
            ApkSignatures.sign(unsigned, deviceAgain, ApkTools.deviceIdentity(target), true, false);
            ApkVerifier.Result againVerified = ApkSignatures.verify(deviceAgain);
            check(againVerified.isVerified() && Arrays.equals(deviceVerified.getSignerCertificates().get(0).getEncoded(),
                againVerified.getSignerCertificates().get(0).getEncoded()), "device signing identity is reused across calls");
            stage("pkcs12-import");
            ApkSignatures.Identity identity = identity(work);
            stage("sign-verify");
            File signed = new File(root, "fixture.apk");
            ApkSignatures.sign(unsigned, signed, identity, true, true);
            File idsig = new File(signed.getPath() + ".idsig");
            ApkVerifier.Result verified = ApkSignatures.verify(signed, idsig);
            check(verified.isVerified(), "APK + detached v4 verify: " + verified.getErrors());
            check(verified.isVerifiedUsingV3Scheme(), "v3 verified");
            check(idsig.isFile() && idsig.length() > 0 && verified.isVerifiedUsingV4Scheme(), "detached v4 verified");
            // A verifier may skip older schemes outside the APK's current platform range.
            scheme(signed, 21, 23, true);
            scheme(signed, 24, 27, false);
            check(!ApkSignatures.verify(signed).isVerifiedUsingV4Scheme(), "v4 never inferred without sidecar");
            String cert = hex(MessageDigest.getInstance("SHA-256").digest(verified.getSignerCertificates().get(0).getEncoded()));
            result.putString("certificate_sha256", cert);
            check(ApkTools.metadata(target, signed).contains("Package: " + pkg), "Android PackageManager parses generated manifest");
            PackageInfo archive = target.getPackageManager().getPackageArchiveInfo(signed.getPath(), 0);
            check(archive != null && archive.applicationInfo != null && pkg.equals(archive.packageName)
                    && "Device smoke after".contentEquals(archive.applicationInfo.loadLabel(target.getPackageManager())),
                    "Android reads edited binary manifest label");
            // Invalidating a signed entry must invalidate its cryptographic signature.
            File patch = new File(work, "patch.txt");
            write(patch, "changed after signing".getBytes(StandardCharsets.UTF_8));
            File changed = new File(work, "changed.apk");
            ApkZip.rewrite(signed, changed, java.util.Collections.singletonMap("assets/proof.txt", patch),
                    java.util.Collections.emptySet());
            check(!ApkSignatures.verify(changed).isVerified(), "changed unsigned output cannot verify");
            stage("jadx-dex");
            javaSource(dex, work);
            stage("jadx-apk");
            javaSource(signed, work);
            JSONObject receipt = new JSONObject().put("run_id", id).put("package", pkg).put("certificate_sha256", cert);
            if (install) {
                stage("packageinstaller-submit");
                // Never replace an existing package or obscure another unfinished user install.
                try {
                    target.getPackageManager().getPackageInfo(pkg, 0);
                    throw new AssertionError("Fixture package already exists");
                } catch (android.content.pm.PackageManager.NameNotFoundException expected) { }
                SharedPreferences prefs = installPrefs();
                String previous = prefs.getString("latest", null);
                check(previous == null || prefs.getInt(previous + ".status", -100) >= 0, "another installation is not pending");
                receipt.put("previous_latest", previous == null ? JSONObject.NULL : previous);
                File prepared = new File(work, "install-set");
                java.util.List<ApkPackageSet.Part> parts = ApkPackageSet.prepare(signed, prepared);
                // Persist the artifact identity before submission, including if commit throws afterwards.
                saveReceipt(root, receipt);
                retain = true;
                int session = ApkInstaller.install(target, parts);
                String token = prefs.getString("latest", null);
                check(token != null && pkg.equals(prefs.getString(token + ".package", ""))
                        && session == prefs.getInt(token + ".session", -1), "own submitted session record");
                receipt.put("session", session).put("token", token);
                saveReceipt(root, receipt);
                result.putInt("session_id", session);
                result.putString("install_token", token);
                stage("packageinstaller-callback");
                long end = SystemClock.elapsedRealtime() + 20000;
                while (prefs.getInt(token + ".status", -100) == -100 && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
                int status = prefs.getInt(token + ".status", -100);
                result.putInt("install_status", status);
                result.putString("install_state", ApkInstaller.status(target));
                check(status == PackageInstaller.STATUS_PENDING_USER_ACTION, "real Receiver must receive pending user action; status=" + status);
                check(ApkInstaller.confirmation(target) != null, "Receiver persisted a real Android confirmation intent");
                // The parent opens the visible APK tools Activity and confirms; submission is not success.
                result.putString("acceptance", "CORE_PASS_INSTALL_PENDING_USER_ACTION");
            } else {
                retain = "true".equals(args.getString("keep_apk", "false"));
                if (retain) saveReceipt(root, receipt);
                result.putString("acceptance", "CORE_PASS_INSTALL_NOT_RUN");
            }
            if (retain) {
                result.putString("signed_apk", signed.getAbsolutePath());
                result.putString("idsig", idsig.getAbsolutePath());
                result.putString("receipt", new File(root, "receipt.json").getAbsolutePath());
            }
            stage("cleanup");
        } finally {
            // Never enumerate/clear cache siblings, production keystores, or another app's data.
            try { deleteOwned(work, root); }
            finally {
                if (!retain) deleteOwned(root, root);
                else result.putString("retained_cache", root.getAbsolutePath());
            }
        }
    }

    private File dex(File work) throws Exception {
        File source = new File(work, "source"); check(source.mkdir(), "Smali sources");
        write(new File(source, "DeviceSmoke.smali"), (".class public " + TYPE + "\n.super Ljava/lang/Object;\n"
                + ".method public static message()Ljava/lang/String;\n.registers 1\nconst-string v0, \"" + BEFORE
                + "\"\nreturn-object v0\n.end method\n"
                + ".method public static answer()I\n.registers 1\nconst/16 v0, 0x2a\nreturn v0\n.end method\n").getBytes(StandardCharsets.UTF_8));
        write(new File(source, "Untouched.smali"), (".class public Lfixture/Untouched;\n.super Ljava/lang/Object;\n"
                + ".field public static final MARKER:Ljava/lang/String; = \"unchanged-neighbor\"\n").getBytes(StandardCharsets.UTF_8));
        File original = new File(work, "original.dex"); ApkFormats.assemble(source, original, 28);
        byte[] before = read(original);
        check(ApkFormats.classes(before).size() == 2, "real assembled two-class DEX");
        String smali = ApkFormats.smali(before, TYPE);
        check(smali.contains(BEFORE) && smali.contains("answer()I"), "real class disassembly");
        byte[] edited = ApkFormats.replaceClass(before, TYPE, smali.replace(BEFORE, AFTER), work);
        check(ApkFormats.strings(edited).contains(AFTER) && !ApkFormats.strings(edited).contains(BEFORE), "edited instructions compiled");
        check(ApkFormats.smali(before, "Lfixture/Untouched;").equals(ApkFormats.smali(edited, "Lfixture/Untouched;")), "other class preserved");
        File disassembly = new File(work, "disassembly"); ApkFormats.disassemble(edited, disassembly);
        File rebuilt = new File(work, "rebuilt.dex"); ApkFormats.assemble(disassembly, rebuilt, 28);
        check(ApkFormats.smali(read(rebuilt), TYPE).equals(ApkFormats.smali(edited, TYPE)), "directory disassembly and rebuild preserve instructions");
        check(Arrays.equals(before, read(original)), "original DEX unchanged");
        return rebuilt;
    }

    private byte[] manifest(String pkg) throws Exception {
        ResXmlDocument doc = new ResXmlDocument(); ResXmlElement manifest = doc.newElement("manifest");
        manifest.newAttribute("package", 0).setValueAsString(pkg);
        number(manifest, "versionCode", 0x0101021b, 1);
        manifest.getOrCreateAndroidAttribute("versionName", 0x0101021c).setValueAsString("1.0");
        ResXmlElement sdk = manifest.newElement("uses-sdk");
        number(sdk, "minSdkVersion", 0x0101020c, 21);
        number(sdk, "targetSdkVersion", 0x01010270, 36);
        manifest.newElement("application").getOrCreateAndroidAttribute("label", 0x01010001).setValueAsString("Device smoke before");
        doc.refreshFull(); byte[] original = doc.getBytes();
        String json = ApkFormats.decodeAxml(original);
        check(json.contains("Device smoke before"), "binary AXML decoded");
        byte[] edited = ApkFormats.compileAxml(json.replace("Device smoke before", "Device smoke after"));
        ResXmlDocument parsed = new ResXmlDocument(); parsed.readBytes(new ByteArrayInputStream(edited));
        check(pkg.equals(parsed.getDocumentElement().searchAttributeByName("package").getValueAsString()), "AXML package retained");
        check(ApkFormats.xmlPreview(edited).contains("Device smoke after"), "edited AXML redecoded");
        check(parsed.getDocumentElement().getElement("application").searchAttributeByName("label").getNameId() == 0x01010001, "AXML framework attribute ID retained");
        return edited;
    }

    private byte[] resources(String pkg) throws Exception {
        TableBlock table = new TableBlock(); PackageBlock p = table.newPackage(0x7f, pkg);
        p.getOrCreate("", "string", "greeting").setValueAsString("Resource before");
        int id = p.getOrCreate("", "string", "greeting").getResourceId();
        p.getOrCreate("fr", "string", "greeting").setValueAsString("Bonjour");
        p.getOrCreate("", "bool", "enabled").setValueAsBoolean(true);
        p.getOrCreate("", "string", "alias").setValueAsReference(id);
        table.refreshFull(); String json = ApkFormats.decodeArsc(table.getBytes());
        check(json.contains("Resource before"), "binary ARSC decoded");
        byte[] edited = ApkFormats.compileArsc(json.replace("Resource before", "Resource after"));
        TableBlock parsed = new TableBlock(); parsed.readBytes(new ByteArrayInputStream(edited));
        PackageBlock out = parsed.getPackageBlockById(0x7f);
        check(out.getOrCreate("", "string", "greeting").getResourceId() == id
                && "Resource after".equals(out.getOrCreate("", "string", "greeting").getValueAsString()), "ARSC value changed with resource ID retained");
        check("Bonjour".equals(out.getOrCreate("fr", "string", "greeting").getValueAsString()), "ARSC locale retained");
        check(out.getOrCreate("", "bool", "enabled").getValueAsBoolean()
                && out.getOrCreate("", "string", "alias").getValueType() == ValueType.REFERENCE
                && out.getOrCreate("", "string", "alias").getResValue().getData() == id, "ARSC bool and reference retained");
        return edited;
    }

    private ApkSignatures.Identity identity(File work) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        // A small self-signed X.509 fixture, using only Android JCA (no host keytool/BC registration).
        byte[] algorithm = new byte[]{0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00};
        byte[] name = new X500Principal("CN=VeyTerm APK smoke ephemeral").getEncoded();
        SimpleDateFormat date = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US); date.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] validity = der(0x30, der(0x18, date.format(new Date(now - 86400000L)).getBytes(StandardCharsets.US_ASCII)),
                der(0x18, date.format(new Date(now + 315360000000L)).getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs = der(0x30, der(0x02, new BigInteger(128, new SecureRandom()).add(BigInteger.ONE).toByteArray()),
                algorithm, name, validity, name, pair.getPublic().getEncoded());
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(pair.getPrivate()); signer.update(tbs);
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(der(0x30, tbs, algorithm, der(0x03, new byte[]{0}, signer.sign()))));
        certificate.checkValidity(); certificate.verify(pair.getPublic());
        char[] password = UUID.randomUUID().toString().toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, password);
            store.setKeyEntry("smoke", pair.getPrivate(), password, new Certificate[]{certificate});
            File file = new File(work, "ephemeral.p12");
            try (FileOutputStream out = new FileOutputStream(file)) { store.store(out, password); out.getFD().sync(); }
            KeyStore loaded = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(file)) { loaded.load(in, password); }
            check(MessageDigest.isEqual(certificate.getEncoded(), loaded.getCertificate("smoke").getEncoded()), "Android PKCS12 saved and loaded");
            check(Arrays.equals(pair.getPrivate().getEncoded(), loaded.getKey("smoke", password).getEncoded()), "exportable private key roundtrip");
            return ApkSignatures.importKey(file, "PKCS12", password, "smoke", password);
        } finally { Arrays.fill(password, '\0'); }
    }

    private void scheme(File apk, int min, int max, boolean v1) throws Exception {
        ApkVerifier.Result v = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(min).setMaxCheckedPlatformVersion(max).build().verify();
        check(v.isVerified() && (v1 ? v.isVerifiedUsingV1Scheme() : v.isVerifiedUsingV2Scheme()), (v1 ? "v1" : "v2") + " platform-range verification: " + v.getErrors());
    }

    private void javaSource(File input, File work) throws Exception {
        File cache = new File(work, "jadx"); if (!cache.exists()) check(cache.mkdir(), "JADX cache");
        byte[] original = read(input);
        try (DecompilerSession session = DecompilerSession.open(input, cache, CANCEL)) {
            check(session.classCount() == 2 && session.dexCount() == 1, "JADX loaded generated DEX classes");
            check(session.search(DecompilerSession.Search.STRINGS, AFTER, 0, CANCEL).hits.size() == 1, "edited DEX string indexed");
            DecompilerSession.Source source = session.decompile(CLASS, CANCEL);
            String java = new String(read(source.file), StandardCharsets.UTF_8);
            check(source.errors == 0 && source.warnings == 0, "JADX code generation without errors/warnings");
            check(java.contains("package fixture;") && java.contains("class DeviceSmoke")
                    && java.contains("String message()") && java.contains("return \"" + AFTER + "\";")
                    && java.contains("int answer()") && java.contains("return 42;"), "real Java method bodies: " + java);
            check(!java.contains(BEFORE) && !java.contains("UnsupportedOperationException"), "Java reflects edited instructions");
        }
        String[] children = cache.list(); check(children != null && children.length == 0, "JADX session removes private scratch");
        check(Arrays.equals(original, read(input)), "decompiler leaves input unchanged");
    }

    private void installStatus() throws Exception {
        stage("packageinstaller-final-status");
        String id = args.getString("run_id", "");
        check(id.equals(UUID.fromString(id).toString()), "canonical run_id required");
        File root = new File(target.getCacheDir().getCanonicalFile(), PREFIX + id);
        check(root.getCanonicalFile().equals(root.getAbsoluteFile()), "receipt path must not be a symlink");
        JSONObject receipt = new JSONObject(new String(read(new File(root, "receipt.json")), StandardCharsets.UTF_8));
        String pkg = "com.veyterm.smoke.apkdevice.p" + id.replace("-", "");
        check(id.equals(receipt.getString("run_id")) && pkg.equals(receipt.getString("package")), "receipt belongs to generated fixture");
        String token = receipt.getString("token");
        SharedPreferences prefs = installPrefs();
        check(receipt.getInt("session") == prefs.getInt(token + ".session", -1)
                && pkg.equals(prefs.getString(token + ".package", "")), "own session persisted");
        int status = prefs.getInt(token + ".status", -100);
        result.putInt("install_status", status);
        result.putString("fixture_package", pkg);
        check(status == PackageInstaller.STATUS_SUCCESS, "Receiver has not confirmed success: " + status);
        PackageInfo installed = target.getPackageManager().getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES);
        check(installed.versionCode == 1 && installed.signatures != null && installed.signatures.length == 1, "fixture installed by Android");
        check(receipt.getString("certificate_sha256").equals(hex(MessageDigest.getInstance("SHA-256").digest(installed.signatures[0].toByteArray()))), "installed fixture signer matches generated key");
        result.putString("acceptance", "ANDROID_RECEIVER_SUCCESS_AND_FIXTURE_INSTALLED");
    }

    private SharedPreferences installPrefs() { return target.getSharedPreferences("apk_install_status", Context.MODE_PRIVATE); }
    private void saveReceipt(File root, JSONObject receipt) throws Exception {
        write(new File(root, "receipt.json"), receipt.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private static void number(ResXmlElement node, String name, int id, int value) {
        node.getOrCreateAndroidAttribute(name, id).setValueType(ValueType.DEC);
        node.getOrCreateAndroidAttribute(name, id).setData(value);
    }
    private void stage(String name) {
        stage = name; Bundle status = new Bundle(); status.putString("stream", "ApkWorkspaceSmoke: " + name + "\n"); sendStatus(0, status);
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); assertions++; }
    private static void entry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name)); out.write(bytes); out.closeEntry();
    }
    private static byte[] read(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) { if (out.size() + count > 4 * 1024 * 1024) throw new IOException("Smoke fixture exceeds 4 MiB"); out.write(buffer, 0, count); }
            return out.toByteArray();
        }
    }
    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    private static byte[] der(int tag, byte[]... values) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream(); for (byte[] v : values) data.write(v);
        byte[] body = data.toByteArray(); ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(tag);
        if (body.length < 128) out.write(body.length);
        else {
            int n = 0; for (int length = body.length; length != 0; length >>>= 8) n++;
            out.write(0x80 | n); for (int i = n - 1; i >= 0; i--) out.write(body.length >>> (8 * i));
        }
        out.write(body); return out.toByteArray();
    }
    private static void deleteOwned(File file, File root) throws IOException {
        if (!file.exists()) return;
        String base = root.getAbsolutePath(), path = file.getAbsolutePath();
        if ((!path.equals(base) && !path.startsWith(base + File.separator)) || !file.getCanonicalFile().equals(file.getAbsoluteFile()))
            throw new IOException("Refuse cleanup outside own canonical run root: " + file);
        if (file.isDirectory()) {
            File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot list own scratch: " + file);
            for (File child : children) deleteOwned(child, root);
        }
        if (!file.delete()) throw new IOException("Cannot remove own scratch: " + file);
    }
}
