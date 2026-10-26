package com.termux.app.core;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class BundledExecutableTest {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test public void upgradesKnownLegacyHelperWithoutState() {
        byte[] original = bytes("legacy helper");
        assertTrue(BundledExecutable.canReplace(original, bytes("new helper"), null,
            BundledExecutable.digest(original)));
    }

    @Test public void upgradesPreviouslyRecordedHelper() {
        byte[] original = bytes("previous version");
        assertTrue(BundledExecutable.canReplace(original, bytes("next version"),
            BundledExecutable.digest(original), null));
    }

    @Test public void preservesEditsEvenWithPreviousState() {
        assertFalse(BundledExecutable.canReplace(bytes("user customization"), bytes("new helper"),
            BundledExecutable.digest(bytes("previous version")), BundledExecutable.digest(bytes("legacy helper"))));
    }

    @Test public void adoptsCurrentHelperWithoutState() {
        assertTrue(BundledExecutable.canReplace(bytes("current"), bytes("current"), null, null));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BundledExecutable.digest(bytes("abc")));
    }
}
