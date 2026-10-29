package com.termux.app.files.apk;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;

/** Platform signing verification (not merely certificate enumeration). No embedded signing identity. */
public final class ApkSignatures {
    private ApkSignatures() {}
    public static final class Identity {
        final PrivateKey key;
        final List<X509Certificate> chain;
        public Identity(PrivateKey key, List<X509Certificate> chain) throws GeneralSecurityException {
            if (key == null || chain == null || chain.isEmpty()) throw new KeyStoreException("Missing private key/certificate chain");
            this.key = key; this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
            // Ensure imported certificate matches the actual private key before writing any APK.
            byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
            String algorithm = "RSA".equalsIgnoreCase(key.getAlgorithm()) ? "SHA256withRSA"
                : "EC".equalsIgnoreCase(key.getAlgorithm()) ? "SHA256withECDSA" : null;
            if (algorithm == null) throw new KeyStoreException("Only RSA and EC signing keys are supported");
            Signature signature = Signature.getInstance(algorithm); signature.initSign(key); signature.update(challenge);
            byte[] proof = signature.sign(); signature.initVerify(chain.get(0)); signature.update(challenge);
            if (!signature.verify(proof)) throw new KeyStoreException("Private key and certificate do not match");
        }
    }

    public static Identity importKey(File store, String type, char[] storePassword,
                                     String alias, char[] keyPassword) throws Exception {
        if (!"PKCS12".equalsIgnoreCase(type) && !"BKS".equalsIgnoreCase(type) && !"JKS".equalsIgnoreCase(type))
            throw new KeyStoreException("Use PKCS12, BKS, or JKS (only if a JKS provider is installed)");
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream in = new FileInputStream(store)) { ks.load(in, storePassword); }
        Key key = ks.getKey(alias, keyPassword);
        if (!(key instanceof PrivateKey)) throw new KeyStoreException("Alias is not a private key");
        Certificate[] certificates = ks.getCertificateChain(alias);
        if (certificates == null) throw new KeyStoreException("Alias has no certificate chain");
        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate certificate : certificates) {
            if (!(certificate instanceof X509Certificate)) throw new KeyStoreException("Expected X.509 certificate");
            chain.add((X509Certificate) certificate);
        }
        return new Identity((PrivateKey) key, chain);
    }

    public static ApkVerifier.Result verify(File apk) throws Exception {
        ApkZip.checkCancelled();
        ApkVerifier.Result result = new ApkVerifier.Builder(apk).build().verify();
        ApkZip.checkCancelled(); return result;
    }

    public static String report(File apk) throws Exception {
        ApkVerifier.Result r = verify(apk);
        StringBuilder text = new StringBuilder("Verified: ").append(r.isVerified())
            .append("\nv1: ").append(r.isVerifiedUsingV1Scheme()).append("\nv2: ").append(r.isVerifiedUsingV2Scheme())
            .append("\nv3: ").append(r.isVerifiedUsingV3Scheme()).append("\n");
        for (X509Certificate c : r.getSignerCertificates()) text.append(certificate(c));
        text.append("Errors: ").append(r.getErrors()).append("\nWarnings: ").append(r.getWarnings());
        for (ApkVerifier.Result.V1SchemeSignerInfo signer : r.getV1SchemeSigners())
            text.append("\nv1 signer errors: ").append(signer.getErrors()).append("; warnings: ").append(signer.getWarnings());
        for (ApkVerifier.Result.V2SchemeSignerInfo signer : r.getV2SchemeSigners())
            text.append("\nv2 signer errors: ").append(signer.getErrors()).append("; warnings: ").append(signer.getWarnings());
        return text.toString();
    }

    public static String certificate(X509Certificate c) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
        StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02X", b & 255));
        return "\nSubject: " + c.getSubjectX500Principal() + "\nIssuer: " + c.getIssuerX500Principal()
            + "\nSerial: " + c.getSerialNumber().toString(16) + "\nValid: " + c.getNotBefore() + " — " + c.getNotAfter()
            + "\nAlgorithm: " + c.getSigAlgName() + "\nSHA-256: " + hex + "\n";
    }

    /** Explicit signing only. Verify the staged signed result before publishing it. */
    public static void sign(File input, File output, Identity identity) throws Exception {
        if (input.getCanonicalFile().equals(output.getCanonicalFile())) throw new IOException("Use a separate signed output APK");
        File staged = ApkZip.temporary(output); File unsigned = File.createTempFile(".apk-unsigned-", ".apk", staged.getParentFile());
        try {
            if (!unsigned.delete()) throw new IOException("Cannot prepare unsigned staging path");
            ApkZip.rewrite(input, unsigned, Collections.emptyMap(), Collections.emptySet());
            ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("VEYTERM", identity.key, identity.chain).build();
            new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(unsigned).setOutputApk(staged)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false)
                .setV4SigningEnabled(false).setOtherSignersSignaturesPreserved(false).build().sign();
            ApkVerifier.Result verification = verify(staged);
            if (!verification.isVerified() || !verification.isVerifiedUsingV1Scheme() || !verification.isVerifiedUsingV2Scheme())
                throw new IOException("Signed result failed v1/v2 verification: " + verification.getErrors());
            ApkZip.publish(staged, output);
        } finally { staged.delete(); unsigned.delete(); }
    }
}
