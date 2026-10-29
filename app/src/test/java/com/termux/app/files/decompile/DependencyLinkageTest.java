package com.termux.app.files.decompile;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Check every Guava/SLF4J member symbol referenced by JADX and its relocated Smali runtime. */
public final class DependencyLinkageTest {
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        for (String line : lines) {
            String[] parts = line.split("\t");
            Class<?> owner = Class.forName(parts[0].replace('/', '.'), false, DependencyLinkageTest.class.getClassLoader());
            if (!find(owner, parts[1], parts[2], parts[3], new HashSet<>())) throw new AssertionError("Missing selected-version member: " + line);
        }
        System.out.println("PASS: " + lines.size() + " Guava/SLF4J member symbols resolve with Guava 27.1-android and SLF4J 2.0.17");
    }
    private static boolean find(Class<?> owner, String name, String descriptor, String kind, Set<Class<?>> seen) {
        if (owner == null || !seen.add(owner)) return false;
        if (kind.equals("field")) {
            for (Field field : owner.getDeclaredFields()) if (field.getName().equals(name) && type(field.getType()).equals(descriptor)) return true;
        } else if (name.equals("<init>")) {
            for (Constructor<?> constructor : owner.getDeclaredConstructors()) if (signature(constructor.getParameterTypes(), void.class).equals(descriptor)) return true;
        } else {
            for (Method method : owner.getDeclaredMethods()) if (method.getName().equals(name) && signature(method.getParameterTypes(), method.getReturnType()).equals(descriptor)) return true;
        }
        if (find(owner.getSuperclass(), name, descriptor, kind, seen)) return true;
        for (Class<?> parent : owner.getInterfaces()) if (find(parent, name, descriptor, kind, seen)) return true;
        return false;
    }
    private static String signature(Class<?>[] args, Class<?> result) {
        StringBuilder out = new StringBuilder("("); for (Class<?> arg : args) out.append(type(arg)); return out.append(')').append(type(result)).toString();
    }
    private static String type(Class<?> cls) {
        if (cls.isArray()) return cls.getName().replace('.', '/');
        if (!cls.isPrimitive()) return "L" + cls.getName().replace('.', '/') + ";";
        if (cls == void.class) return "V"; if (cls == boolean.class) return "Z"; if (cls == byte.class) return "B";
        if (cls == char.class) return "C"; if (cls == short.class) return "S"; if (cls == int.class) return "I";
        if (cls == long.class) return "J"; if (cls == float.class) return "F"; return "D";
    }
}
