package com.termux.app.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Updates bundled helpers while preserving locally edited executables. */
public final class BundledExecutable {
    private BundledExecutable() {}

    public static String digest(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder();
            for (byte value : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static boolean canReplace(byte[] installed, byte[] bundled, String recordedDigest, String legacyDigest) {
        if (Arrays.equals(installed, bundled)) return true;
        String installedDigest = digest(installed);
        return installedDigest.equals(recordedDigest) || installedDigest.equals(legacyDigest);
    }
}
