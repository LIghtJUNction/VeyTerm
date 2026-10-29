package com.termux.app.files.apk;

import com.android.apksig.ApkVerifier;
import com.reandroid.arsc.chunk.xml.*;
import com.reandroid.arsc.value.ValueType;
import java.io.*;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.*;

/** Platform-independent validation; archive filenames/metadata JSON are never trusted as identities. */
public final class ApkPackageSet {
    public static final int MAX_APKS = 256;
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private ApkPackageSet() {}

    public static final class Part {
        public final File file;
        public final String label, packageName, split, configForSplit, fingerprint;
        public final long version;
        public final int minSdk;
        public final boolean splitRequired;
        public final Set<String> dependencies, signers, abis, requiredTypes, splitTypes;
        private Part(File file, String label) throws Exception {
            this.file = file; this.label = label;
            fingerprint = ApkZip.fingerprint(file);
            ApkZip.test(file);
            ResXmlElement root = ApkSourceXml.read(ApkZip.read(file, "AndroidManifest.xml", ApkFormats.EDIT_LIMIT)).getDocumentElement();
            if (!"manifest".equals(root.getName())) throw new IOException("Not an APK manifest: " + label);
            packageName = string(root, "package", false, "");
            if (packageName.isEmpty()) throw new IOException("Missing package: " + label);
            split = string(root, "split", false, "");
            configForSplit = string(root, "configForSplit", false, "");
            requiredTypes = types(string(root, "requiredSplitTypes", true, ""));
            splitTypes = types(string(root, "splitTypes", true, ""));
            version = ((number(root, "versionCodeMajor", 0) & 0xffffffffL) << 32)
                | (number(root, "versionCode", 0) & 0xffffffffL);
            ResXmlElement sdk = root.getElement("uses-sdk");
            minSdk = sdk == null ? 1 : number(sdk, "minSdkVersion", 1);
            ResXmlElement application = root.getElement("application");
            splitRequired = application != null && number(application, "isSplitRequired", 0) != 0;
            Set<String> deps = new TreeSet<>();
            Iterator<ResXmlElement> uses = root.getElements("uses-split");
            while (uses.hasNext()) {
                String name = string(uses.next(), "name", true, "");
                if (name.isEmpty() || !deps.add(name)) throw new IOException("Invalid/duplicate uses-split in " + label);
            }
            if (!configForSplit.isEmpty()) deps.add(configForSplit);
            dependencies = Collections.unmodifiableSet(deps);
            ApkVerifier.Result verified = ApkSignatures.verify(file);
            if (!verified.isVerified()) throw new IOException("APK signature does not verify: " + label + ": " + verified.getErrors());
            Set<String> certificates = new TreeSet<>();
            for (X509Certificate certificate : verified.getSignerCertificates())
                certificates.add(ApkZip.hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
            if (certificates.isEmpty()) throw new IOException("No verified signer: " + label);
            signers = Collections.unmodifiableSet(certificates);
            Set<String> nativeAbis = new TreeSet<>();
            for (ApkZip.Entry entry : ApkZip.list(file)) {
                String[] path = entry.name.split("/");
                if (path.length == 3 && path[0].equals("lib") && path[2].endsWith(".so")) nativeAbis.add(path[1]);
            }
            abis = Collections.unmodifiableSet(nativeAbis);
            ApkZip.requireFingerprint(file, fingerprint);
        }
        @Override public String toString() {
            return (split.isEmpty() ? "BASE" : split) + " — " + packageName + " / " + Long.toString(version)
                + (abis.isEmpty() ? "" : " " + abis) + "\n" + label;
        }
    }

    /** Scratch must be private. Copy every input before inspection so commit reads the validated bytes. */
    public static List<Part> prepare(File input, File newDirectory) throws Exception {
        if (newDirectory.exists() || !newDirectory.mkdir()) throw new IOException("Use a new private package-set directory");
        List<Part> result = new ArrayList<>(); boolean success = false;
        try {
            String sourceFingerprint = input.isFile() ? ApkZip.fingerprint(input) : null;
            if (input.isDirectory()) {
                File[] files = input.listFiles(); if (files == null) throw new IOException("Cannot read package-set directory");
                Arrays.sort(files);
                for (File file : files) {
                    String name = file.getName().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".obb") || name.endsWith(".apex")) throw new IOException("OBB/APEX installation is unsupported");
                    if (!file.isFile() || !name.endsWith(".apk")) continue;
                    if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IOException("Symlink in package set");
                    addCopy(file, file.getName(), newDirectory, result);
                }
            } else if (input.getName().toLowerCase(Locale.ROOT).endsWith(".apk") || hasManifest(input)) {
                addCopy(input, input.getName(), newDirectory, result);
            } else {
                for (ApkZip.Entry entry : ApkZip.list(input)) {
                    String name = entry.name.toLowerCase(Locale.ROOT);
                    if (name.endsWith(".obb") || name.endsWith(".apex"))
                        throw new IOException("OBB/APEX payload installation is unsupported; select an APK-only set");
                    if (!entry.directory && name.endsWith(".apk")) {
                        limit(result);
                        File target = new File(newDirectory, String.format(Locale.ROOT, "%03d.apk", result.size()));
                        ApkZip.extract(input, entry.name, target);
                        result.add(new Part(target, entry.name));
                    }
                }
            }
            if (result.isEmpty()) throw new IOException("No APKs found (select a directory, APK, APKS or XAPK)");
            long size = 0;
            for (Part part : result) { size += part.file.length(); if (size > ApkZip.MAX_TOTAL) throw new IOException("Package set exceeds 4 GiB"); }
            if (sourceFingerprint != null) ApkZip.requireFingerprint(input, sourceFingerprint);
            success = true; return Collections.unmodifiableList(result);
        } finally { if (!success) ApkFormats.deleteScratch(newDirectory); }
    }

    private static void limit(List<Part> parts) throws IOException {
        ApkZip.checkCancelled(); if (parts.size() >= MAX_APKS) throw new IOException("Package set exceeds 256 APKs");
    }
    private static boolean hasManifest(File input) throws IOException {
        for (ApkZip.Entry entry : ApkZip.list(input)) if (entry.name.equals("AndroidManifest.xml") && !entry.directory) return true;
        return false;
    }
    private static void addCopy(File input, String label, File directory, List<Part> parts) throws Exception {
        limit(parts); String fingerprint = ApkZip.fingerprint(input);
        long total = input.length(); for (Part part : parts) total += part.file.length();
        if (total > ApkZip.MAX_TOTAL) throw new IOException("Package set exceeds 4 GiB");
        File target = new File(directory, String.format(Locale.ROOT, "%03d.apk", parts.size()));
        try (InputStream in = new FileInputStream(input); FileOutputStream out = new FileOutputStream(target)) {
            ApkZip.copy(in, out, ApkZip.MAX_TOTAL); out.getFD().sync();
        }
        if (!fingerprint.equals(ApkZip.fingerprint(input)) || !fingerprint.equals(ApkZip.fingerprint(target)))
            throw new IOException("Package-set input changed while copying");
        parts.add(new Part(target, label));
    }

    /** Selection is explicit: APKS may contain mutually exclusive device variants or standalone APKs. */
    public static List<Part> validate(List<Part> selected, int sdk, Collection<String> supportedAbis) throws Exception {
        if (selected.isEmpty() || selected.size() > MAX_APKS) throw new IOException("Select one base and its matching splits");
        Part base = null; Map<String, Part> names = new TreeMap<>();
        Set<String> offeredTypes = new HashSet<>();
        Set<String> commonAbis = new HashSet<>(supportedAbis);
        boolean nativeCode = false;
        for (Part part : selected) offeredTypes.addAll(part.splitTypes);
        for (Part part : selected) {
            if (names.put(part.split, part) != null) throw new IOException("Duplicate base/split: " + part.split + "; select one device variant");
            if (part.split.isEmpty()) base = part;
        }
        if (base == null) throw new IOException("Missing base APK");
        if (!base.configForSplit.isEmpty()) throw new IOException("Base cannot be a config split");
        if (base.splitRequired && selected.size() == 1) throw new IOException("Base requires splits");
        for (Part part : selected) {
            ApkZip.checkCancelled();
            ApkZip.requireFingerprint(part.file, part.fingerprint);
            if (!part.packageName.equals(base.packageName)) throw new IOException("Mixed package names");
            if (part.version != base.version) throw new IOException("Mixed package versions");
            if (!part.signers.equals(base.signers)) throw new IOException("Mixed signing certificates");
            if (part.minSdk > sdk) throw new IOException("APK requires Android API " + part.minSdk + ": " + part.label);
            if (!part.abis.isEmpty() && Collections.disjoint(part.abis, supportedAbis))
                throw new IOException("Unsupported native ABI: " + part.abis + ": " + part.label);
            if (!part.abis.isEmpty()) { nativeCode = true; commonAbis.retainAll(part.abis); }
            if (!offeredTypes.containsAll(part.requiredTypes)) throw new IOException("Missing required split types: " + part.requiredTypes);
            for (String dep : part.dependencies) if (!names.containsKey(dep)) throw new IOException("Missing split dependency: " + dep);
        }
        if (nativeCode && commonAbis.isEmpty()) throw new IOException("Selected native splits have no common device ABI");
        for (Part part : selected) visit(part, names, new HashSet<>(), new HashSet<>());
        List<Part> ordered = new ArrayList<>(selected); ordered.remove(base); ordered.add(0, base);
        return Collections.unmodifiableList(ordered);
    }
    private static void visit(Part part, Map<String, Part> names, Set<String> active, Set<String> done) throws IOException {
        if (done.contains(part.split)) return;
        if (!active.add(part.split)) throw new IOException("Cyclic split dependencies");
        for (String dep : part.dependencies) visit(names.get(dep), names, active, done);
        active.remove(part.split); done.add(part.split);
    }

    static ResXmlAttribute attribute(ResXmlElement element, String name, boolean android) throws IOException {
        ResXmlAttribute found = null;
        Iterator<ResXmlAttribute> attrs = element.getAttributes();
        while (attrs.hasNext()) {
            ResXmlAttribute attr = attrs.next(); String uri = attr.getUri();
            if (name.equals(attr.getName()) && (android ? ANDROID.equals(uri) : uri == null || uri.isEmpty())) {
                if (found != null) throw new IOException("Duplicate manifest attribute: " + name);
                found = attr;
            }
        }
        return found;
    }
    static String string(ResXmlElement element, String name, boolean android, String fallback) throws IOException {
        ResXmlAttribute attr = attribute(element, name, android);
        if (attr == null) return fallback;
        if (attr.getValueType() != ValueType.STRING) throw new IOException("Manifest " + name + " must be a literal string");
        return attr.getValueAsString();
    }
    private static int number(ResXmlElement element, String name, int fallback) throws IOException {
        ResXmlAttribute attr = attribute(element, name, true);
        if (attr == null) return fallback;
        if (attr.getValueType() != ValueType.DEC && attr.getValueType() != ValueType.HEX && attr.getValueType() != ValueType.BOOLEAN)
            throw new IOException("Manifest " + name + " must be numeric (preview SDK codenames unsupported)");
        return attr.getData();
    }

    private static Set<String> types(String value) throws IOException {
        Set<String> types = new TreeSet<>();
        if (!value.isEmpty()) for (String type : value.split(",", -1)) {
            type = type.trim();
            if (type.isEmpty()) throw new IOException("Empty manifest split type");
            types.add(type);
        }
        return Collections.unmodifiableSet(types);
    }
}
