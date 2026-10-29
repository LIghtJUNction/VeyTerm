package com.termux.app.files.decompile;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/** Execute under a loader that fails if production decompilation asks for desktop/compiler APIs. */
public final class NoDesktopRunner {
    public static void main(String[] args) throws Exception {
        String[] paths = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) urls[i] = new File(paths[i]).toURI().toURL();
        try (URLClassLoader loader = new URLClassLoader(urls, null) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("java.awt.") || name.startsWith("javax.swing.") || name.startsWith("javax.tools.")
                    || name.startsWith("com.sun.tools.")) throw new AssertionError("Forbidden desktop/compiler dependency: " + name);
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> test = loader.loadClass("com.termux.app.files.decompile.DecompilerRoundTripTest");
            test.getMethod("main", String[].class).invoke(null, (Object) args);
        }
        System.out.println("PASS: no AWT/Swing/compiler API requested during real decompilation and export");
    }
}
