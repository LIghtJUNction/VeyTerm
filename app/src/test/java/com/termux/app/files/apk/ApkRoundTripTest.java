package com.termux.app.files.apk;

import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.xml.*;
import com.reandroid.arsc.value.*;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Host JVM format/integrity tests, no Android runtime, bootstrap or checked-in private key. */
public final class ApkRoundTripTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String message) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (Exception e) { failed = true; }
        check(failed, message);
    }
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static void put(File file, byte[] bytes) throws IOException { Files.write(file.toPath(), bytes); }
    private static byte[] get(File file) throws IOException { return Files.readAllBytes(file.toPath()); }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]); dir.mkdirs();
        File keystore = new File(args[1]);
        byte[] dex = dexRoundTrip(dir);
        byte[] axml = axmlRoundTrip();
        byte[] arsc = arscRoundTrip(axml);
        zipAndSigning(dir, keystore, dex, axml, arsc);
        advanced(dir, keystore, axml);
        System.out.println("PASS: " + assertions + " assertions: DEX/Smali, AXML, ARSC, ZIP mutation/diff, v1/v2/v3/v4 signing, source XML, split validation, private drafts/conflicts");
    }

    private static byte[] dexRoundTrip(File dir) throws Exception {
        File source = new File(dir, "source"); check(source.mkdir(), "source directory");
        String first = ".class public Lexample/First;\n.super Ljava/lang/Object;\n"
            + ".field public static count:I = 0x7\n"
            + ".method public static value()Ljava/lang/String;\n.locals 1\nconst-string v0, \"before\"\nreturn-object v0\n.end method\n";
        String second = ".class public Lexample/Second;\n.super Ljava/lang/Object;\n"
            + ".method public static number()I\n.locals 1\nconst/4 v0, 0x2\nreturn v0\n.end method\n";
        put(new File(source, "First.smali"), utf8(first)); put(new File(source, "Second.smali"), utf8(second));
        File dexFile = new File(dir, "classes.dex"); ApkFormats.assemble(source, dexFile, 28); byte[] dex = get(dexFile);
        check(ApkFormats.classes(dex).equals(Arrays.asList("Lexample/First;", "Lexample/Second;")), "actual DEX class index");
        check(ApkFormats.strings(dex).contains("before"), "actual DEX string pool");
        check(ApkFormats.members(dex, "Lexample/First;").size() == 2, "method and field index");
        String decoded = ApkFormats.smali(dex, "Lexample/First;");
        byte[] edited = ApkFormats.replaceClass(dex, "Lexample/First;", decoded.replace("\"before\"", "\"after\""), dir);
        check(ApkFormats.strings(edited).contains("after") && !ApkFormats.strings(edited).contains("before"), "Smali changes real DEX string and instructions");
        check(ApkFormats.classes(edited).size() == 2, "untouched DEX class retained");
        check(ApkFormats.smali(dex, "Lexample/Second;").equals(ApkFormats.smali(edited, "Lexample/Second;")), "untouched method preserved");
        rejects(() -> ApkFormats.replaceClass(dex, "Lexample/First;", decoded.replace("Lexample/First;", "Lexample/Renamed;"), dir), "unsafe class renaming rejected");
        rejects(() -> ApkFormats.replaceClass(dex, "Lexample/First;", "bad source", dir), "bad Smali rejected");
        check(ApkFormats.strings(dex).contains("before"), "failed edit preserves original DEX");
        File exported = new File(dir, "exported"); ApkFormats.disassemble(dex, exported);
        File roundtrip = new File(dir, "roundtrip.dex"); ApkFormats.assemble(exported, roundtrip, 28);
        check(ApkFormats.classes(get(roundtrip)).equals(ApkFormats.classes(dex)), "whole DEX Smali roundtrip");
        check(ApkFormats.smali(get(roundtrip), "Lexample/First;").equals(decoded), "whole DEX instructions roundtrip");
        rejects(() -> ApkFormats.dex(utf8("cdex001\0not-a-standard-dex")), "compact DEX explicitly rejected");
        File escape = new File(source, "escape.smali"); Files.createSymbolicLink(escape.toPath(), new File(dir, "classes.dex").toPath().toAbsolutePath());
        rejects(() -> ApkFormats.assemble(source, new File(dir, "escape.dex"), 28), "Smali symlink traversal rejected"); escape.delete();
        return dex;
    }

    private static byte[] axmlRoundTrip() throws Exception {
        ResXmlDocument doc = new ResXmlDocument(); ResXmlElement manifest = doc.newElement("manifest");
        manifest.newAttribute("package", 0).setValueAsString("example.roundtrip");
        manifest.getOrCreateAndroidAttribute("versionCode", 0x0101021b).setValueType(ValueType.DEC);
        manifest.getOrCreateAndroidAttribute("versionCode", 0x0101021b).setData(1);
        manifest.getOrCreateAndroidAttribute("versionName", 0x0101021c).setValueAsString("1.0");
        ResXmlElement sdk = manifest.newElement("uses-sdk");
        sdk.getOrCreateAndroidAttribute("minSdkVersion", 0x0101020c).setValueType(ValueType.DEC);
        sdk.getOrCreateAndroidAttribute("minSdkVersion", 0x0101020c).setData(21);
        sdk.getOrCreateAndroidAttribute("targetSdkVersion", 0x01010270).setValueType(ValueType.DEC);
        sdk.getOrCreateAndroidAttribute("targetSdkVersion", 0x01010270).setData(28);
        manifest.newElement("uses-permission").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.permission.INTERNET");
        ResXmlElement app = manifest.newElement("application");
        app.getOrCreateAndroidAttribute("label", 0x01010001).setValueAsString("Before label");
        app.newElement("activity").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("example.roundtrip.Main");
        doc.refreshFull(); byte[] binary = doc.getBytes();
        String json = ApkFormats.decodeAxml(binary);
        byte[] roundtrip = ApkFormats.compileAxml(json);
        check(ApkFormats.decodeAxml(roundtrip).contains("example.roundtrip"), "AXML package survives binary JSON binary roundtrip");
        String preview = ApkFormats.xmlPreview(roundtrip);
        check(preview.contains("android.permission.INTERNET") && preview.contains("example.roundtrip.Main"), "AXML permission and component survive");
        byte[] edited = ApkFormats.compileAxml(json.replace("Before label", "After label"));
        check(ApkFormats.xmlPreview(edited).contains("After label"), "AXML edits compile into binary typed values");
        ResXmlDocument parsed = new ResXmlDocument(); parsed.readBytes(new ByteArrayInputStream(edited));
        boolean idPreserved = false;
        Iterator<ResXmlAttribute> attrs = parsed.recursiveAttributes();
        while (attrs.hasNext()) { ResXmlAttribute a = attrs.next(); if (a.getName().equals("label")) idPreserved = a.getNameId() == 0x01010001; }
        check(idPreserved, "AXML Android attribute numeric ID preserved");
        rejects(() -> ApkFormats.compileAxml("{}"), "malformed AXML envelope rejected");
        rejects(() -> ApkFormats.decodeAxml(utf8("<manifest/>")), "plain XML not falsely decoded as binary XML");
        return binary;
    }

    private static byte[] arscRoundTrip(byte[] axml) throws Exception {
        TableBlock table = new TableBlock(); PackageBlock p = table.newPackage(0x7f, "example.roundtrip");
        Entry entry = p.getOrCreate("", "string", "greeting"); entry.setValueAsString("Before resource");
        int id = entry.getResourceId();
        p.getOrCreate("fr", "string", "greeting").setValueAsString("Bonjour");
        p.getOrCreate("", "bool", "enabled").setValueAsBoolean(true);
        p.getOrCreate("", "string", "alias").setValueAsReference(id);
        table.refreshFull(); byte[] original = table.getBytes();
        String json = ApkFormats.decodeArsc(original); byte[] encoded = ApkFormats.compileArsc(json);
        TableBlock parsed = new TableBlock(); parsed.readBytes(new ByteArrayInputStream(encoded));
        check(parsed.getPackageBlockById(0x7f).getOrCreate("", "string", "greeting").getResourceId() == id, "ARSC resource ID retained");
        check(parsed.getPackageBlockById(0x7f).getOrCreate("fr", "string", "greeting").getValueAsString().equals("Bonjour"), "ARSC locale/configuration retained");
        check(parsed.getPackageBlockById(0x7f).getOrCreate("", "bool", "enabled").getValueAsBoolean(), "ARSC typed bool retained");
        check(parsed.getPackageBlockById(0x7f).getOrCreate("", "string", "alias").getValueType() == ValueType.REFERENCE, "ARSC reference remains typed");
        byte[] edited = ApkFormats.compileArsc(json.replace("Before resource", "After resource"));
        parsed = new TableBlock(); parsed.readBytes(new ByteArrayInputStream(edited));
        check(parsed.getPackageBlockById(0x7f).getOrCreate("", "string", "greeting").getValueAsString().equals("After resource"), "ARSC edits change real binary table");
        check(ApkFormats.decodeArsc(original).contains("Before resource"), "original resource table preserved");
        rejects(() -> ApkFormats.compileArsc(ApkFormats.decodeAxml(axml)), "wrong resource format rejected");
        return original;
    }

    private static void zipAndSigning(File dir, File keystore, byte[] dex, byte[] axml, byte[] arsc) throws Exception {
        File input = new File(dir, "input.apk");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(input))) {
            entry(z, "AndroidManifest.xml", axml); entry(z, "resources.arsc", arsc); entry(z, "classes.dex", dex);
            entry(z, "assets/plain.txt", utf8("UNIQUE_PAYLOAD_BEFORE")); entry(z, "lib/arm64-v8a/libexample.so", new byte[]{0x7f, 'E', 'L', 'F'});
            entry(z, "META-INF/OLD.SF", utf8("stale")); entry(z, "META-INF/OLD.RSA", utf8("stale"));
            entry(z, "META-INF/MANIFEST.MF", utf8("stale")); entry(z, "META-INF/LICENSE", utf8("keep this license"));
            entry(z, "stamp-cert-sha256", utf8("old source stamp"));
        }
        byte[] original = get(input); File patch = new File(dir, "patch.txt"); put(patch, utf8("UNIQUE_PAYLOAD_AFTER!"));
        File output = new File(dir, "changed.apk");
        ApkZip.rewrite(input, output, Collections.singletonMap("assets/plain.txt", patch), Collections.singleton("classes.dex"));
        ApkZip.test(output);
        check(Arrays.equals(original, get(input)), "input bytes unchanged after successful rewrite");
        List<String> names = new ArrayList<>(); for (ApkZip.Entry e : ApkZip.list(output)) names.add(e.name);
        check(!names.contains("classes.dex") && !names.contains("META-INF/OLD.RSA") && !names.contains("stamp-cert-sha256"), "deleted entry and stale signing data removed");
        check(names.contains("META-INF/LICENSE"), "unrelated META-INF content retained");
        check(new String(ApkZip.read(output, "assets/plain.txt", 1024), StandardCharsets.UTF_8).contains("AFTER"), "real ZIP entry replacement");
        check(ApkZip.diff(input, output).contains("CHANGED assets/plain.txt"), "ZIP diff checks inflated data");
        check(ApkZip.diff(input, input).isEmpty(), "self ZIP diff empty");
        check(dataOffset(get(output), "resources.arsc") % 4 == 0, "resource table 4-byte alignment");
        check(dataOffset(get(output), "lib/arm64-v8a/libexample.so") % 16384 == 0, "stored native library 16 KiB alignment");
        rejects(() -> ApkZip.rewrite(input, input, Collections.emptyMap(), Collections.emptySet()), "in-place APK mutation refused");
        rejects(() -> ApkZip.rewrite(input, output, Collections.emptyMap(), Collections.emptySet()), "existing destination protected");
        check(Arrays.equals(original, get(input)), "failed writes preserve input");
        File failure = new File(dir, "failed.apk");
        rejects(() -> ApkZip.rewrite(input, failure, Collections.singletonMap("x", new File(dir, "missing")), Collections.emptySet()), "missing replacement rejected");
        check(!failure.exists(), "failed rewrite publishes nothing");
        Thread.currentThread().interrupt();
        rejects(() -> ApkZip.rewrite(input, failure, Collections.emptyMap(), Collections.emptySet()), "cancellation rejected before publishing");
        Thread.interrupted(); check(!failure.exists(), "cancelled output absent");
        File unsafe = new File(dir, "unsafe.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(unsafe))) { entry(z, "../escape", utf8("x")); }
        rejects(() -> ApkZip.list(unsafe), "ZIP traversal rejected");
        File duplicate = new File(dir, "duplicate.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(duplicate))) { entry(z, "aaa", utf8("x")); entry(z, "bbb", utf8("y")); }
        byte[] duplicateBytes = get(duplicate);
        for (int i = 0; i < duplicateBytes.length - 2; i++) if (duplicateBytes[i] == 'b' && duplicateBytes[i+1] == 'b' && duplicateBytes[i+2] == 'b') {
            duplicateBytes[i] = 'a'; duplicateBytes[i+1] = 'a'; duplicateBytes[i+2] = 'a';
        }
        put(duplicate, duplicateBytes); rejects(() -> ApkZip.list(duplicate), "ambiguous duplicate ZIP paths rejected");
        rejects(() -> ApkZip.read(input, "classes.dex", 2), "editor limits reject rather than silently truncate");
        ApkSignatures.Identity identity = ApkSignatures.importKey(keystore, "PKCS12", "test-password".toCharArray(), "test-key", "test-password".toCharArray());
        File signed = new File(dir, "signed.apk"); ApkSignatures.sign(input, signed, identity);
        ApkVerifier.Result verified = ApkSignatures.verify(signed);
        check(verified.isVerified() && verified.isVerifiedUsingV1Scheme() && verified.isVerifiedUsingV2Scheme(), "generated v1+v2 APK verifies cryptographically");
        check(ApkSignatures.report(signed).contains("SHA-256:"), "certificate digest exposed");
        check(dataOffset(get(signed), "lib/arm64-v8a/libexample.so") % 16384 == 0, "signer preserves native-library alignment");
        check(dataOffset(get(signed), "resources.arsc") % 4 == 0, "signer preserves resource alignment");
        File noManifest = new File(dir, "no-manifest.apk");
        ApkZip.rewrite(input, noManifest, Collections.emptyMap(), Collections.singleton("AndroidManifest.xml"));
        byte[] noManifestBefore = get(noManifest); File failedSignature = new File(dir, "failed-signature.apk");
        rejects(() -> ApkSignatures.sign(noManifest, failedSignature, identity), "signing missing manifest fails explicitly");
        check(!failedSignature.exists() && Arrays.equals(noManifestBefore, get(noManifest)), "failed signing preserves original and publishes nothing");
        byte[] signedBytes = get(signed); byte[] payload = utf8("UNIQUE_PAYLOAD_BEFORE"); int payloadOffset = indexOf(signedBytes, payload);
        check(payloadOffset >= 0, "stored signed test payload located"); signedBytes[payloadOffset] ^= 1;
        File tampered = new File(dir, "tampered.apk"); put(tampered, signedBytes);
        check(!ApkSignatures.verify(tampered).isVerified(), "content tampering fails real signature verification");
        rejects(() -> ApkZip.read(tampered, "assets/plain.txt", 1024), "corrupted entry CRC is rejected by editor reads");
        File corruptExtract = new File(dir, "corrupt-extracted.txt");
        rejects(() -> ApkZip.extract(tampered, "assets/plain.txt", corruptExtract), "corrupt extraction rejected");
        check(!corruptExtract.exists(), "corrupt extraction publishes no file");
        File unsigned = new File(dir, "unsigned.apk"); ApkZip.rewrite(signed, unsigned, Collections.singletonMap("assets/plain.txt", patch), Collections.emptySet());
        check(!ApkSignatures.verify(unsigned).isVerified(), "changed signed APK becomes explicitly unsigned");
        for (ApkZip.Entry e : ApkZip.list(unsigned)) check(!ApkZip.staleSignature(e.name), "no stale v1 entry " + e.name);
        File resigned = new File(dir, "resigned.apk"); ApkSignatures.sign(unsigned, resigned, identity);
        check(ApkSignatures.verify(resigned).isVerifiedUsingV2Scheme(), "modified APK can be explicitly re-signed");
        check(Arrays.equals(original, get(input)), "sign and re-sign never alter input");
        File applied = new File(dir, "caller-staged.apk"); put(applied, original);
        ApkZip.apply(output, applied);
        check(Arrays.equals(get(applied), get(output)), "explicit apply updates exact caller-staged path");
        byte[] beforeFailedApply = get(applied);
        rejects(() -> ApkZip.apply(tampered, applied), "invalid candidate cannot be applied");
        check(Arrays.equals(get(applied), beforeFailedApply), "failed apply preserves staged original bytes");
        Thread.currentThread().interrupt();
        rejects(() -> ApkZip.apply(signed, applied), "cancelled apply rejected"); Thread.interrupted();
        check(Arrays.equals(get(applied), beforeFailedApply), "cancelled apply preserves staged original bytes");
        ApkZip.apply(signed, applied);
        check(ApkSignatures.verify(applied).isVerifiedUsingV2Scheme(), "byte-copy apply preserves v2 signing block");
        rejects(() -> ApkSignatures.importKey(keystore, "PKCS12", "wrong".toCharArray(), "test-key", "wrong".toCharArray()), "wrong user password rejected");
        rejects(() -> ApkSignatures.importKey(keystore, "PKCS12", "test-password".toCharArray(), "missing", "test-password".toCharArray()), "missing private key alias rejected");
    }

    private static void advanced(File dir, File keystore, byte[] axml) throws Exception {
        ApkSignatures.Identity identity = ApkSignatures.importKey(keystore, "PKCS12", "test-password".toCharArray(), "test-key", "test-password".toCharArray());
        File input = new File(dir, "input.apk"); byte[] original = get(input);
        File v3 = new File(dir, "v3.apk"); ApkSignatures.sign(input, v3, identity, true, false);
        check(ApkSignatures.verify(v3).isVerifiedUsingV3Scheme(), "real v3 signer verified");
        check(!new File(v3 + ".idsig").exists(), "v3-only request does not manufacture detached v4");
        File v4 = new File(dir, "v4.apk"); ApkSignatures.sign(input, v4, identity, true, true);
        File idsig = new File(v4 + ".idsig");
        ApkVerifier.Result all = ApkSignatures.verify(v4, idsig);
        check(all.isVerified() && all.isVerifiedUsingV1Scheme() && all.isVerifiedUsingV2Scheme()
            && all.isVerifiedUsingV3Scheme() && all.isVerifiedUsingV4Scheme(), "v1+v2+v3 and detached v4 verify cryptographically");
        check(!ApkSignatures.verify(v4).isVerifiedUsingV4Scheme(), "v4 not claimed without detached sidecar");
        check(ApkSignatures.report(v4).contains("v4 sidecar: true"), "report supplies real detached v4 to verifier");
        byte[] corrupt = get(idsig); corrupt[corrupt.length - 1] ^= 1;
        File badIdsig = new File(dir, "bad.idsig"); put(badIdsig, corrupt);
        check(!v4Verified(v4, badIdsig), "tampered v4 sidecar rejected");
        File modified = new File(dir, "resigned.apk");
        check(!v4Verified(modified, idsig), "sidecar for different APK contents rejected");
        File collision = new File(dir, "collision.apk"), collisionIdsig = new File(collision + ".idsig");
        put(collisionIdsig, utf8("USER SIDECAR"));
        rejects(() -> ApkSignatures.sign(input, collision, identity, true, true), "existing sidecar protected before any output");
        check(!collision.exists() && new String(get(collisionIdsig), StandardCharsets.UTF_8).equals("USER SIDECAR"), "sidecar collision preserves user data");
        File failure = new File(dir, "failed-v4.apk");
        rejects(() -> ApkSignatures.sign(new File(dir, "no-manifest.apk"), failure, identity, true, true), "failed v4 signing rejected");
        check(!failure.exists() && !new File(failure + ".idsig").exists(), "failed pair publishes neither output");
        check(Arrays.equals(original, get(input)), "advanced signing preserves original bytes");

        ResXmlDocument modernManifest = ApkSourceXml.read(axml);
        ResXmlAttribute sdk33 = modernManifest.getDocumentElement().getElement("uses-sdk").getOrCreateAndroidAttribute("minSdkVersion", 0x0101020c);
        sdk33.setValueType(ValueType.DEC); sdk33.setData(33); modernManifest.refreshFull();
        File modernPatch = new File(dir, "modern-manifest.bin"); put(modernPatch, modernManifest.getBytes());
        File modern = new File(dir, "modern.apk"), modernSigned = new File(dir, "modern-signed.apk");
        ApkZip.rewrite(input, modern, Collections.singletonMap("AndroidManifest.xml", modernPatch), Collections.emptySet());
        ApkSignatures.sign(modern, modernSigned, identity, true, true);
        check(v4Verified(modernSigned, new File(modernSigned + ".idsig")), "APK with minSdk 33 signs and verifies rather than falsely requiring legacy verifier flags");
        check(new ApkVerifier.Builder(modernSigned).setMinCheckedPlatformVersion(21).setMaxCheckedPlatformVersion(23).build().verify().isVerifiedUsingV1Scheme(), "modern APK also contains independently verified requested v1");
        check(new ApkVerifier.Builder(modernSigned).setMinCheckedPlatformVersion(24).setMaxCheckedPlatformVersion(27).build().verify().isVerifiedUsingV2Scheme(), "modern APK also contains independently verified requested v2");

        sourceXml(dir, input, axml);
        packageSets(dir, identity, keystore, axml);
        File privateDir = new File(dir, "private-drafts");
        ApkDrafts.Draft draft = new ApkDrafts.Draft(input, ApkZip.fingerprint(input), "XML", "AndroidManifest.xml", "", "<manifest>edited π</manifest>");
        ApkDrafts.save(privateDir, input, draft);
        ApkDrafts.Draft recovered = ApkDrafts.load(privateDir, input);
        check(recovered != null && recovered.text.equals(draft.text) && recovered.matches(input), "private draft survives new reader with source fingerprint and UTF-8 text");
        ApkDrafts.save(privateDir, input, new ApkDrafts.Draft(input, draft.fingerprint, "SMALI", "classes.dex", "Lexample/First;", "changed source"));
        recovered = ApkDrafts.load(privateDir, input);
        check(recovered.kind.equals("SMALI") && recovered.descriptor.equals("Lexample/First;"), "atomic draft update preserves compiler mode/descriptor");
        File external = new File(dir, "externally-changed.apk"); put(external, original);
        String fingerprint = ApkZip.fingerprint(external); put(external, get(modified)); byte[] changedBytes = get(external);
        rejects(() -> ApkZip.apply(v3, external, fingerprint), "stale opened fingerprint prevents apply");
        check(Arrays.equals(changedBytes, get(external)), "conflict leaves externally changed original intact");
        String appliedFingerprint = ApkZip.apply(v3, external, ApkZip.fingerprint(external));
        check(appliedFingerprint.equals(ApkZip.fingerprint(external)), "apply returns committed bytes fingerprint for subsequent edits without post-commit reads");
        ApkDrafts.Draft stale = new ApkDrafts.Draft(external, fingerprint, "AXML", "AndroidManifest.xml", "", "draft");
        check(!stale.matches(external), "stale process-death draft cannot match changed APK");
        Thread.currentThread().interrupt();
        rejects(() -> ApkDrafts.save(privateDir, input, draft), "cancelled draft update rejected"); Thread.interrupted();
        check(ApkDrafts.load(privateDir, input).kind.equals("SMALI"), "failed draft save keeps last persisted text");
        ApkDrafts.discard(privateDir, input); check(ApkDrafts.load(privateDir, input) == null, "explicit discard deletes private draft");
    }

    private static boolean v4Verified(File apk, File idsig) {
        try { ApkVerifier.Result r = ApkSignatures.verify(apk, idsig); return r.isVerified() && r.isVerifiedUsingV4Scheme(); }
        catch (Exception e) { return false; }
    }

    private static void sourceXml(File dir, File input, byte[] axml) throws Exception {
        ResXmlDocument manifest = ApkSourceXml.read(axml);
        ResXmlElement app = manifest.getDocumentElement().getElement("application");
        TableBlock table = new TableBlock(); table.readBytes(new ByteArrayInputStream(ApkZip.read(input, "resources.arsc", ApkFormats.EDIT_LIMIT)));
        Entry label = table.pickOne().getOrCreate("", "string", "greeting");
        app.getOrCreateAndroidAttribute("label", 0x01010001).setValueType(ValueType.REFERENCE);
        app.getOrCreateAndroidAttribute("label", 0x01010001).setData(label.getResourceId());
        manifest.refreshFull(); File patch = new File(dir, "reference-manifest.bin"); put(patch, manifest.getBytes());
        File reference = new File(dir, "source-xml.apk");
        ApkZip.rewrite(input, reference, Collections.singletonMap("AndroidManifest.xml", patch), Collections.emptySet());
        String xml = ApkSourceXml.decode(reference, "AndroidManifest.xml");
        check(xml.contains("@string/greeting"), "source XML resolves actual APK resource name");
        byte[] compiled = ApkSourceXml.compile(reference, xml);
        ResXmlElement reparsed = ApkSourceXml.read(compiled).getDocumentElement();
        ResXmlAttribute actual = ApkPackageSet.attribute(reparsed.getElement("application"), "label", true);
        check(actual.getValueType() == ValueType.REFERENCE && actual.getData() == label.getResourceId(), "source XML name recompiles to exact numeric resource ID");
        check(actual.getNameId() == 0x01010001, "source XML compiles real Android attribute ID");
        byte[] edited = ApkSourceXml.compile(reference, xml.replace("@string/greeting", "Source label"));
        check(ApkPackageSet.attribute(ApkSourceXml.read(edited).getDocumentElement().getElement("application"), "label", true).getValueAsString().equals("Source label"), "source XML edit changes binary value");
        rejects(() -> ApkSourceXml.compile(reference, xml.replace("@string/greeting", "@string/missing_symbol")), "unresolved APK resource name rejected");
        rejects(() -> ApkSourceXml.compile(reference, xml.replace("android:label", "android:missingAttribute")), "unknown Android attribute rejected");
        rejects(() -> ApkSourceXml.compile(reference, xml.replace("@string/greeting", "@+id/new_symbol")), "new IDs rejected because the original resource table is not rebuilt");
        rejects(() -> ApkSourceXml.compile(reference, "<manifest>"), "malformed source XML rejected");
        rejects(() -> ApkSourceXml.compile(reference, "<!DOCTYPE manifest [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><manifest>&x;</manifest>"), "XML external entities rejected");
        check(ApkSourceXml.decode(reference, "AndroidManifest.xml").equals(xml), "failed XML compilation preserves APK and resource symbols");
    }

    private static File splitFixture(File dir, String name, byte[] axml, String pkg, int version, String split, String config,
                                     String uses, ApkSignatures.Identity identity) throws Exception {
        ResXmlDocument doc = ApkSourceXml.read(axml); ResXmlElement root = doc.getDocumentElement();
        root.removeAttributesWithName("package"); root.newAttribute("package", 0).setValueAsString(pkg);
        ResXmlAttribute code = root.getOrCreateAndroidAttribute("versionCode", 0x0101021b); code.setValueType(ValueType.DEC); code.setData(version);
        if (!split.isEmpty()) root.newAttribute("split", 0).setValueAsString(split);
        if (!config.isEmpty()) root.newAttribute("configForSplit", 0).setValueAsString(config);
        if (!uses.isEmpty()) root.newElement("uses-split").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(uses);
        doc.refreshFull();
        File unsigned = new File(dir, name + "-unsigned.apk"), signed = new File(dir, name + ".apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(unsigned))) { entry(zip, "AndroidManifest.xml", doc.getBytes()); }
        ApkSignatures.sign(unsigned, signed, identity, true, false); return signed;
    }

    private static void packageSets(File dir, ApkSignatures.Identity identity, File keystore, byte[] axml) throws Exception {
        File base = splitFixture(dir, "set-base", axml, "example.set", 3, "", "", "", identity);
        File callerStaged = new File(dir, "caller-file-without-apk-extension.tmp"); Files.copy(base.toPath(), callerStaged.toPath());
        check(ApkPackageSet.prepare(callerStaged, new File(dir, "prepared-caller-file")).get(0).split.isEmpty(), "caller-staged APK is recognized by contents without changing its filename");
        File feature = splitFixture(dir, "set-feature", axml, "example.set", 3, "feature", "", "", identity);
        File config = splitFixture(dir, "set-config", axml, "example.set", 3, "config.fr", "feature", "", identity);
        File set = new File(dir, "set.apks");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(set))) {
            entry(z, "splits/base-master.apk", get(base)); entry(z, "splits/feature-master.apk", get(feature)); entry(z, "splits/config-fr.apk", get(config));
            entry(z, "toc.pb", new byte[]{1, 2, 3});
        }
        List<ApkPackageSet.Part> parts = ApkPackageSet.prepare(set, new File(dir, "prepared-apks"));
        List<ApkPackageSet.Part> validated = ApkPackageSet.validate(parts, 36, Arrays.asList("arm64-v8a"));
        check(validated.size() == 3 && validated.get(0).split.isEmpty(), "nested APKS actual signed APKs form one validated base/split set");
        check(validated.get(0).version == 3 && validated.get(0).packageName.equals("example.set"), "set identity read from compiled manifest");
        ApkPackageSet.Part b = validated.get(0), f = null, c = null;
        for (ApkPackageSet.Part p : validated) { if (p.split.equals("feature")) f = p; if (p.split.equals("config.fr")) c = p; }
        final ApkPackageSet.Part featurePart = f, configPart = c;
        rejects(() -> ApkPackageSet.validate(Arrays.asList(b, configPart), 36, Collections.emptyList()), "configForSplit dependency required");
        rejects(() -> ApkPackageSet.validate(Arrays.asList(featurePart, configPart), 36, Collections.emptyList()), "split-only selection rejected");
        rejects(() -> ApkPackageSet.validate(Arrays.asList(b, b), 36, Collections.emptyList()), "duplicate base variants rejected");
        rejects(() -> ApkPackageSet.validate(Arrays.asList(b, featurePart, featurePart), 36, Collections.emptyList()), "duplicate split variants rejected");
        rejects(() -> ApkPackageSet.validate(parts, 20, Collections.emptyList()), "minimum SDK checked before session");
        File mixed = splitFixture(dir, "mixed-package", axml, "other.package", 3, "other", "", "", identity);
        ApkPackageSet.Part wrongPackage = ApkPackageSet.prepare(mixed, new File(dir, "prepared-package")).get(0);
        rejects(() -> ApkPackageSet.validate(Arrays.asList(b, wrongPackage), 36, Collections.emptyList()), "mixed package names rejected");
        File version = splitFixture(dir, "mixed-version", axml, "example.set", 4, "other", "", "", identity);
        ApkPackageSet.Part wrongVersion = ApkPackageSet.prepare(version, new File(dir, "prepared-version")).get(0);
        rejects(() -> ApkPackageSet.validate(Arrays.asList(b, wrongVersion), 36, Collections.emptyList()), "mixed version codes rejected");
        ApkSignatures.Identity otherIdentity = ApkSignatures.importKey(keystore, "PKCS12", "test-password".toCharArray(), "test-other", "test-password".toCharArray());
        File otherSigner = splitFixture(dir, "other-signer", axml, "example.set", 3, "other", "", "", otherIdentity);
        ApkPackageSet.Part wrongSigner = ApkPackageSet.prepare(otherSigner, new File(dir, "prepared-signer")).get(0);
        rejects(() -> ApkPackageSet.validate(Arrays.asList(b, wrongSigner), 36, Collections.emptyList()), "cryptographically valid APK with different signer rejected");
        File dependency = splitFixture(dir, "missing-feature", axml, "example.set", 3, "other", "", "missing.feature", identity);
        ApkPackageSet.Part missing = ApkPackageSet.prepare(dependency, new File(dir, "prepared-dependency")).get(0);
        rejects(() -> ApkPackageSet.validate(Arrays.asList(b, missing), 36, Collections.emptyList()), "uses-split dependency required");
        File cycle = splitFixture(dir, "cyclic-feature", axml, "example.set", 3, "self", "", "self", identity);
        ApkPackageSet.Part cyclic = ApkPackageSet.prepare(cycle, new File(dir, "prepared-cycle")).get(0);
        rejects(() -> ApkPackageSet.validate(Arrays.asList(b, cyclic), 36, Collections.emptyList()), "cyclic split graph rejected");
        ApkPackageSet.Part nativeBase = ApkPackageSet.prepare(new File(dir, "signed.apk"), new File(dir, "prepared-native")).get(0);
        rejects(() -> ApkPackageSet.validate(Arrays.asList(nativeBase), 36, Arrays.asList("x86_64")), "native ABI mismatch rejected");
        check(ApkPackageSet.validate(Arrays.asList(nativeBase), 36, Arrays.asList("arm64-v8a")).size() == 1, "matching native ABI accepted");
        File xapk = new File(dir, "set.xapk"); Files.copy(set.toPath(), xapk.toPath());
        check(ApkPackageSet.prepare(xapk, new File(dir, "prepared-xapk")).size() == 3, "APK-only XAPK decoded as real package set");
        File directory = new File(dir, "apk-directory"); directory.mkdir();
        Files.copy(base.toPath(), new File(directory, "arbitrary-base-name.apk").toPath());
        Files.copy(feature.toPath(), new File(directory, "arbitrary-split-name.apk").toPath());
        check(ApkPackageSet.validate(ApkPackageSet.prepare(directory, new File(dir, "prepared-directory")), 36, Collections.emptyList()).size() == 2, "directory identities do not depend on filenames");
        File corruptSet = new File(dir, "corrupt-set.xapk");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(corruptSet))) { entry(z, "base.apk", get(new File(dir, "tampered.apk"))); }
        File failedDir = new File(dir, "failed-set");
        rejects(() -> ApkPackageSet.prepare(corruptSet, failedDir), "tampered nested APK signature rejected");
        check(!failedDir.exists(), "failed set preparation removes all private extracted copies");
        File obb = new File(dir, "obb.xapk");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(obb))) { entry(z, "base.apk", get(base)); entry(z, "Android/obb/main.3.example.set.obb", new byte[]{1}); }
        rejects(() -> ApkPackageSet.prepare(obb, new File(dir, "prepared-obb")), "unsupported XAPK OBB fails explicitly");
        ResXmlDocument requiredDoc = ApkSourceXml.read(axml); ResXmlElement requiredRoot = requiredDoc.getDocumentElement();
        requiredRoot.getOrCreateAndroidAttribute("requiredSplitTypes", 16844366).setValueAsString("language,abi");
        requiredDoc.refreshFull();
        File requiredBase = splitFixture(dir, "required-types", requiredDoc.getBytes(), "example.set", 3, "", "", "", identity);
        ApkPackageSet.Part required = ApkPackageSet.prepare(requiredBase, new File(dir, "prepared-required")).get(0);
        rejects(() -> ApkPackageSet.validate(Arrays.asList(required, featurePart), 36, Collections.emptyList()), "missing manifest required split types rejected");
        ResXmlDocument offeredDoc = ApkSourceXml.read(axml);
        offeredDoc.getDocumentElement().getOrCreateAndroidAttribute("splitTypes", 16844367).setValueAsString("language,abi"); offeredDoc.refreshFull();
        File offeredSplit = splitFixture(dir, "offered-types", offeredDoc.getBytes(), "example.set", 3, "config.types", "", "", identity);
        ApkPackageSet.Part offered = ApkPackageSet.prepare(offeredSplit, new File(dir, "prepared-offered")).get(0);
        check(ApkPackageSet.validate(Arrays.asList(required, offered), 36, Collections.emptyList()).size() == 2, "declared required split types satisfied by selected split");
        ResXmlDocument majorDoc = ApkSourceXml.read(axml);
        ResXmlAttribute major = majorDoc.getDocumentElement().getOrCreateAndroidAttribute("versionCodeMajor", 16844150);
        major.setValueType(ValueType.DEC); major.setData(1); majorDoc.refreshFull();
        File majorSplit = splitFixture(dir, "major-version", majorDoc.getBytes(), "example.set", 3, "config.major", "", "", identity);
        ApkPackageSet.Part majorPart = ApkPackageSet.prepare(majorSplit, new File(dir, "prepared-major")).get(0);
        rejects(() -> ApkPackageSet.validate(Arrays.asList(b, majorPart), 36, Collections.emptyList()), "versionCodeMajor participates in same-version validation");
        File snapshotted = b.file; byte[] savedSnapshot = get(snapshotted); put(snapshotted, get(otherSigner));
        rejects(() -> ApkPackageSet.validate(parts, 36, Collections.emptyList()), "private snapshot changed since verification is rejected");
        put(snapshotted, savedSnapshot);
        check(set.isFile() && ApkSignatures.verify(base).isVerifiedUsingV3Scheme(), "set reads preserve signed originals");
    }

    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name); e.setTime(315532800000L);
        if (name.equals("assets/plain.txt")) {
            CRC32 crc = new CRC32(); crc.update(data); e.setMethod(ZipEntry.STORED); e.setSize(data.length); e.setCrc(crc.getValue());
        }
        zip.putNextEntry(e); zip.write(data); zip.closeEntry();
    }
    private static int dataOffset(byte[] zip, String name) {
        ByteBuffer b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < zip.length - 46; i++) {
            if (b.getInt(i) != 0x02014b50) continue;
            int len = b.getShort(i + 28) & 65535;
            if (!name.equals(new String(zip, i + 46, len, StandardCharsets.UTF_8))) continue;
            int local = b.getInt(i + 42);
            return local + 30 + (b.getShort(local + 26) & 65535) + (b.getShort(local + 28) & 65535);
        }
        throw new AssertionError("Missing ZIP entry " + name);
    }
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i+j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
