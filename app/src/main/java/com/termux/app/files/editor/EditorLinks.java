package com.termux.app.files.editor;

import java.io.*;
import java.lang.reflect.*;

/** readlink without an Android dependency in the JVM engine or a java.nio.file API-26 requirement. */
final class EditorLinks {
    private EditorLinks() {}
    static String target(File file) throws IOException {
        try {
            try {
                // Public Android API since 21, including dangling and cyclic links.
                Class<?> os = Class.forName("android.system.Os");
                return (String) os.getMethod("readlink", String.class).invoke(null, file.getAbsolutePath());
            } catch (ClassNotFoundException notAndroid) {
                // Java 7+ JVM tests; looked up reflectively because Android 21 lacks these classes.
                Class<?> path = Class.forName("java.nio.file.Path");
                Object value = File.class.getMethod("toPath").invoke(file);
                return Class.forName("java.nio.file.Files").getMethod("readSymbolicLink", path).invoke(null, value).toString();
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause(); String name = cause.getClass().getName();
            if (name.equals("java.nio.file.NotLinkException") || name.equals("java.nio.file.NoSuchFileException")) return null;
            if (name.equals("android.system.ErrnoException")) {
                try {
                    int errno = cause.getClass().getField("errno").getInt(cause);
                    if (errno == 22 || errno == 2) return null; // EINVAL (not a link), ENOENT (missing).
                } catch (ReflectiveOperationException reflection) { throw new IOException("Cannot inspect readlink error", reflection); }
            }
            throw new IOException("Cannot inspect symbolic link: " + file, cause);
        } catch (ReflectiveOperationException e) { throw new IOException("No supported readlink API", e); }
    }
}
