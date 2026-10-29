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
        System.out.println("PASS: " + assertions + " assertions: DEX/Smali, AXML, ARSC, ZIP mutation/diff, v1/v2 signing/tamper/failure safety");
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
