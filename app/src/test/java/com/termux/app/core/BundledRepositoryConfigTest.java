package com.termux.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BundledRepositoryConfigTest {
    private static final String PREVIOUS = "https://example.com/previous";
    private static final String CURRENT = "https://example.com/current";

    @Test public void advancesBundledRepositoryAndRetainsItsPackageMirror() {
        String original = "SigLevel = Required DatabaseRequired\n[veyterm-x86_64]\nServer = " + PREVIOUS + "\n";
        String expected = "SigLevel = Required DatabaseRequired\n[veyterm-x86_64]\nServer = " + CURRENT + "\nServer = " + PREVIOUS + "\n";
        assertEquals(expected, BundledRepositoryConfig.upgrade(original, PREVIOUS, CURRENT));
        assertEquals(expected, BundledRepositoryConfig.upgrade(expected, PREVIOUS, CURRENT));
    }

    @Test public void preservesUserServersAndComments() {
        String custom = "# Server = " + PREVIOUS + "\nServer = https://mirror.example.com/packages\n";
        assertEquals(custom, BundledRepositoryConfig.upgrade(custom, PREVIOUS, CURRENT));
    }

    @Test public void preservesWindowsLineEndings() {
        assertEquals("Server = " + CURRENT + "\r\nServer = " + PREVIOUS + "\r\n",
            BundledRepositoryConfig.upgrade("Server = " + PREVIOUS + "\r\n", PREVIOUS, CURRENT));
    }
}
