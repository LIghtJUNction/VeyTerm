package com.termux.app.files.decompile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.*;

import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

/** Focused real-format tests. Fixtures and outputs are generated in the runner's private workspace. */
public final class DecompilerRoundTripTest {
    private static int assertions;
    private static final DecompilerSession.Cancellation CHECK = DecompilerSession.THREAD_CANCEL;
    private static File root, cache;

    public static void main(String[] args) throws Exception {
        root = new File(args[0]).getAbsoluteFile(); check(root.mkdirs(), "fixture directory");
        cache = new File(root, "cache"); check(cache.mkdir(), "cache directory");
        File dex = assemble("main", "Demo", DEMO);
        File second = assemble("second", "Other", OTHER);
        byte[] original = Files.readAllBytes(dex.toPath());
        File apk = new File(root, "multidex.apk");
        zip(apk, new String[]{"classes.dex", "classes2.dex", "res/not-code.txt"},
            new byte[][]{original, Files.readAllBytes(second.toPath()), "ignored resource".getBytes(StandardCharsets.UTF_8)});

        try (DecompilerSession session = DecompilerSession.open(dex, cache, CHECK)) {
            check(session.dexCount() == 1 && session.classCount() == 1, "DEX index");
            DecompilerSession.Results all = session.search(DecompilerSession.Search.CLASSES, "", 0, CHECK);
            check(all.hits.size() == 1 && !all.hasMore, "class list");
            check(all.hits.get(0).className.equals("fixture.Demo"), "real class name");
            check(session.search(DecompilerSession.Search.CLASSES, "Lfixture/DEMO;", 0, CHECK).hits.size() == 1, "descriptor case folding");
            check(session.search(DecompilerSession.Search.STRINGS, "你好", 0, CHECK).hits.size() == 1, "const-string index");
            check(session.search(DecompilerSession.Search.STRINGS, "field marker", 0, CHECK).hits.size() == 1, "static string field index");
            check(session.search(DecompilerSession.Search.REFERENCES, "Ljava/lang/String;->length()I", 0, CHECK).hits.size() == 1, "method call reference");
            check(session.search(DecompilerSession.Search.REFERENCES, "Ljava/lang/Object;", 0, CHECK).hits.size() >= 1, "supertype reference");
            check(session.search(DecompilerSession.Search.REFERENCES, "Lfixture/Demo;->FLAG:I", 0, CHECK).hits.size() == 1, "field instruction reference");
            DecompilerSession.Source source = session.decompile("fixture.Demo", CHECK);
            String java = read(source.file);
            check(java.contains("class Demo") && java.contains("int sum(int") && java.contains("return"), "real Java declarations");
            check(java.contains("你好 VeyTerm") && !java.contains(".registers"), "Java string, not smali");
            check(!java.contains("Method not decompiled") && source.errors == 0, "actual structured decompilation");
            File single = new File(root, "Demo.java"); session.exportSource(source, single, CHECK);
            check(Arrays.equals(Files.readAllBytes(source.file.toPath()), Files.readAllBytes(single.toPath())), "whole-class byte exact export");
            File selected = new File(root, "selection.txt"); session.exportSelection("你好\nselected", selected, CHECK);
            check(read(selected).equals("你好\nselected"), "selection UTF-8 export");
            fails(() -> session.exportSource(source, dex, CHECK), "input overwrite refusal");
            fails(() -> session.exportSource(source, single, CHECK), "existing output refusal");
            fails(() -> session.exportSource(source, new File("relative.java"), CHECK), "relative output refusal");
            fails(() -> session.exportSource(source, new File(root, "missing/out.java"), CHECK), "missing parent refusal");
            fails(() -> session.exportSelection("", new File(root, "empty.txt"), CHECK), "empty selection refusal");
            fails(() -> session.exportSource(source, new File(source.file.getParentFile(), "user.java"), CHECK), "scratch output refusal");
            fails(() -> session.search(DecompilerSession.Search.STRINGS, "", 0, CHECK), "empty global search refusal");
            fails(() -> session.search(DecompilerSession.Search.CLASSES, repeat("x", 1025), 0, CHECK), "query bound");
            File cancelled = new File(root, "cancelled.java");
            int[] ticks = {0};
            fails(() -> session.exportSource(source, cancelled, () -> { if (++ticks[0] == 3) throw new CancellationException(); }), "cancel streaming export");
            check(!cancelled.exists(), "cancel removes incomplete output");
            check(session.decompile("fixture.Demo", CHECK).file.length() > 0, "repeated decompile after unload");
            fails(() -> session.exportSource(source, new File(root, "stale.java"), CHECK), "stale source export refusal");
            File sources = new File(root, "sources.zip");
            check(session.exportAll(sources, CHECK, (done, total) -> check(done <= total, "export progress")) == 1, "complete ZIP export count");
            try (ZipFile zip = new ZipFile(sources)) {
                check(zip.size() == 1 && zip.getEntry("fixture/Demo.java") != null, "safe source ZIP entry");
                File dir = new File(root, "java/fixture"); check(dir.mkdirs(), "Java source directory");
                try (InputStream in = zip.getInputStream(zip.getEntry("fixture/Demo.java"))) {
                    Files.copy(in, new File(dir, "Demo.java").toPath());
                }
            }
            File cancelledZip = new File(root, "cancelled.zip");
            fails(() -> session.exportAll(cancelledZip, CHECK, (done, total) -> { throw new CancellationException(); }), "cancel all export");
            check(!cancelledZip.exists(), "cancel removes incomplete ZIP");
            int[] codegenChecks = {0};
            fails(() -> session.decompile("fixture.Demo", () -> { if (++codegenChecks[0] == 2) throw new CancellationException(); }), "cancel after actual class code generation");
            check(session.decompile("fixture.Demo", CHECK).file.length() > 0, "class unload after cancelled code generation");
            fails(() -> session.decompile("missing.Class", CHECK), "missing class failure");
        }
        check(Arrays.equals(original, Files.readAllBytes(dex.toPath())), "original DEX remains exact");
        check(cache.list().length == 0, "session cache cleaned");

        try (DecompilerSession session = DecompilerSession.open(apk, cache, CHECK)) {
            check(session.dexCount() == 2 && session.classCount() == 2, "multidex APK classes loaded");
            check(session.search(DecompilerSession.Search.STRINGS, "second dex", 0, CHECK).hits.get(0).className.equals("fixture.Other"), "global second DEX string");
            String java = read(session.decompile("fixture.Other", CHECK).file);
            check(java.contains("second dex"), "second DEX decompiled");
            File output = new File(root, "multi-sources.zip");
            check(session.exportAll(output, CHECK, (d, t) -> {}) == 2, "all multidex sources exported");
            try (ZipFile zip = new ZipFile(output)) { check(zip.size() == 2, "multidex ZIP entries"); }
        }
        File duplicate = new File(root, "duplicate.apk");
        zip(duplicate, new String[]{"classes.dex", "classes2.dex"}, new byte[][]{original, original});
        fails(() -> DecompilerSession.open(duplicate, cache, CHECK), "duplicate class rejection");
        File bad = new File(root, "bad.dex"); Files.write(bad.toPath(), "not a dex file".getBytes(StandardCharsets.UTF_8));
        fails(() -> DecompilerSession.open(bad, cache, CHECK), "invalid magic rejection");
        byte[] corrupted = original.clone(); corrupted[corrupted.length - 1] ^= 1;
        Files.write(bad.toPath(), corrupted);
        fails(() -> DecompilerSession.open(bad, cache, CHECK), "DEX checksum rejection");
        byte[] newer = original.clone(); newer[6] = '1'; Files.write(bad.toPath(), newer);
        fails(() -> DecompilerSession.open(bad, cache, CHECK), "DEX 041 explicit rejection");
        File noDex = new File(root, "no-dex.apk"); zip(noDex, new String[]{"assets/hidden.dex"}, new byte[][]{original});
        fails(() -> DecompilerSession.open(noDex, cache, CHECK), "no root DEX rejection");
        File bomb = new File(root, "oversize.apk"); zip(bomb, new String[]{"classes.dex"}, new byte[][]{original});
        byte[] oversizedZip = Files.readAllBytes(bomb.toPath());
        for (int i = 0; i < oversizedZip.length - 24; i++) {
            if (oversizedZip[i] == 0x50 && oversizedZip[i + 1] == 0x4b && oversizedZip[i + 2] == 1 && oversizedZip[i + 3] == 2) {
                oversizedZip[i + 27] = 0x7f; // central-directory uncompressed size >= 0x7f000000
            }
        }
        Files.write(bomb.toPath(), oversizedZip);
        fails(() -> DecompilerSession.open(bomb, cache, CHECK), "declared oversized DEX rejected before expansion");
        File duplicateEntry = new File(root, "duplicate-entry.apk");
        zip(duplicateEntry, new String[]{"classes2.dex", "classes3.dex"}, new byte[][]{original, Files.readAllBytes(second.toPath())});
        byte[] duplicateBytes = Files.readAllBytes(duplicateEntry.toPath());
        byte[] oldName = "classes3.dex".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i <= duplicateBytes.length - oldName.length; i++) {
            boolean match = true; for (int j = 0; j < oldName.length; j++) if (duplicateBytes[i + j] != oldName[j]) match = false;
            if (match) duplicateBytes[i + 7] = '2';
        }
        Files.write(duplicateEntry.toPath(), duplicateBytes);
        fails(() -> DecompilerSession.open(duplicateEntry, cache, CHECK), "duplicate APK DEX entry rejection");
        fails(() -> DecompilerSession.open(dex, cache, () -> { throw new CancellationException(); }), "cancelled open");
        int[] openChecks = {0};
        fails(() -> DecompilerSession.open(dex, cache, () -> { if (++openChecks[0] == 3) throw new CancellationException(); }), "cancel allocated session open");
        DecompilerSession closed = DecompilerSession.open(dex, cache, CHECK); closed.close(); closed.close();
        fails(() -> closed.decompile("fixture.Demo", CHECK), "closed session rejection");
        check(cache.list().length == 0, "all failed sessions clean their scratch directories");

        pagingTests();
        resultPagingTests();
        innerClassTests();
        smaliCoexistence(original);
        check(cache.list().length == 0, "final session cache empty");
        System.out.println("PASS: " + assertions + " real DEX/Java, search, export, bounds and cleanup assertions");
    }

    private static void innerClassTests() throws Exception {
        File outer = new File(root, "Outer.smali"), inner = new File(root, "Inner.smali");
        Files.write(outer.toPath(), (".class public Lfixture/Outer;\n.super Ljava/lang/Object;\n" +
            ".method public static answer()I\n.registers 1\ninvoke-static {}, Lfixture/Outer$Inner;->answer()I\nmove-result v0\nreturn v0\n.end method\n").getBytes(StandardCharsets.UTF_8));
        Files.write(inner.toPath(), (".class public Lfixture/Outer$Inner;\n.super Ljava/lang/Object;\n" +
            ".annotation system Ldalvik/annotation/EnclosingClass;\nvalue = Lfixture/Outer;\n.end annotation\n" +
            ".annotation system Ldalvik/annotation/InnerClass;\naccessFlags = 0x9\nname = \"Inner\"\n.end annotation\n" +
            ".method public static answer()I\n.registers 1\nconst/16 v0, 0x63\nreturn v0\n.end method\n").getBytes(StandardCharsets.UTF_8));
        File dex = new File(root, "inner.dex"); SmaliOptions options = new SmaliOptions(); options.outputDexFile = dex.getPath(); options.jobs = 1;
        check(Smali.assemble(options, Arrays.asList(outer.getPath(), inner.getPath())), "assemble nested-class DEX");
        try (DecompilerSession session = DecompilerSession.open(dex, cache, CHECK)) {
            check(session.classCount() == 2, "nested classes both indexed");
            check(session.search(DecompilerSession.Search.CLASSES, "Outer$Inner", 0, CHECK).hits.size() == 1, "nested class searchable by original name");
            DecompilerSession.Source source = session.decompile("fixture.Outer$Inner", CHECK);
            check(source.className.equals("fixture.Outer") && read(source.file).contains("class Inner"), "nested selection opens complete owning Java unit: " + source.className + "\n" + read(source.file));
            File output = new File(root, "inner-sources.zip");
            check(session.exportAll(output, CHECK, (done, total) -> {}) == 1, "nested sources exported once with parent");
            try (ZipFile zip = new ZipFile(output)) {
                check(zip.size() == 1 && zip.getEntry("fixture/Outer.java") != null, "nested export path");
                try (InputStream in = zip.getInputStream(zip.getEntry("fixture/Outer.java"))) {
                    Files.copy(in, new File(root, "java/fixture/Outer.java").toPath());
                }
            }
        }
    }

    private static void pagingTests() throws Exception {
        for (int prefix : new int[]{SourcePages.PAGE_BYTES - 3, SourcePages.PAGE_BYTES - 2, SourcePages.PAGE_BYTES - 1, SourcePages.PAGE_BYTES}) {
            String text = repeat("a", prefix) + "汉😀é\r\n" + repeat("尾巴😀\n", 20000);
            File file = new File(root, "utf8-pages.java"); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            long offset = 0; StringBuilder reconstructed = new StringBuilder();
            do {
                SourcePages.Page page = SourcePages.read(file, offset);
                check(page.end > offset && page.end - offset <= SourcePages.PAGE_BYTES, "bounded page progresses");
                check(!page.text.contains("\ufffd"), "UTF-8 code point retained at page boundary");
                reconstructed.append(page.text); offset = page.end;
            } while (offset < file.length());
            check(text.equals(reconstructed.toString()), "all pages reconstruct exact Unicode source");
            check(SourcePages.read(file, offset).text.isEmpty(), "EOF empty page");
            final long inside = prefix + 1;
            fails(() -> SourcePages.read(file, inside), "misaligned offset refusal");
            fails(() -> SourcePages.read(file, -1), "negative page refusal");
        }
    }

    private static void resultPagingTests() throws Exception {
        File directory = new File(root, "many-smali"); check(directory.mkdir(), "many-class fixture directory");
        List<String> files = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            File smali = new File(directory, "C" + i + ".smali");
            StringBuilder text = new StringBuilder(".class public Lmany/C" + i + ";\n.super Ljava/lang/Object;\n");
            if (i == 0) for (int n = 0; n < 205; n++) text.append(".field public static final V").append(n).append(":Ljava/lang/String; = \"needle ").append(n).append("\"\n");
            Files.write(smali.toPath(), text.toString().getBytes(StandardCharsets.UTF_8)); files.add(smali.getPath());
        }
        File dex = new File(root, "many.dex"); SmaliOptions options = new SmaliOptions(); options.outputDexFile = dex.getPath(); options.jobs = 1;
        check(Smali.assemble(options, files), "many-class DEX assembly");
        try (DecompilerSession session = DecompilerSession.open(dex, cache, CHECK)) {
            DecompilerSession.Results first = session.search(DecompilerSession.Search.CLASSES, "", 0, CHECK);
            check(first.hits.size() == 200 && first.hasMore, "explicit bounded first result page");
            DecompilerSession.Results last = session.search(DecompilerSession.Search.CLASSES, "", 200, CHECK);
            check(last.hits.size() == 5 && !last.hasMore, "remaining results reachable");
            check(!first.hits.get(199).className.equals(last.hits.get(0).className), "no result overlap");
            DecompilerSession.Results strings = session.search(DecompilerSession.Search.STRINGS, "needle", 0, CHECK);
            check(strings.hits.size() == 200 && strings.hasMore, "string results bounded");
            check(session.search(DecompilerSession.Search.STRINGS, "needle", 200, CHECK).hits.size() == 5, "remaining string matches reachable");
            check(session.search(DecompilerSession.Search.REFERENCES, "Ljava/lang/String;", 0, CHECK).hasMore, "reference results bounded");
            fails(() -> session.search(DecompilerSession.Search.CLASSES, "", 0, () -> { throw new CancellationException(); }), "cancel search");
        }
    }

    private static void smaliCoexistence(byte[] dex) throws Exception {
        // The Google fork is relocated. Exercise actual disassembly with the parent's Guava 27.1-android.
        com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile modern = new com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile(null, dex);
        StringWriter modernText = new StringWriter();
        new com.android.tools.smali.baksmali.Adaptors.ClassDefinition(new com.android.tools.smali.baksmali.BaksmaliOptions(), modern.getClasses().iterator().next())
            .writeTo(new com.android.tools.smali.baksmali.formatter.BaksmaliWriter(modernText));
        check(modernText.toString().contains(".class public Lfixture/Demo;"), "relocated Smali fork works with Guava 27.1-android");
        org.jf.dexlib2.dexbacked.DexBackedDexFile legacy = new org.jf.dexlib2.dexbacked.DexBackedDexFile(null, dex);
        StringWriter legacyText = new StringWriter();
        new org.jf.baksmali.Adaptors.ClassDefinition(new org.jf.baksmali.BaksmaliOptions(), legacy.getClasses().iterator().next())
            .writeTo(new org.jf.baksmali.formatter.BaksmaliWriter(legacyText));
        check(legacyText.toString().contains(".class public Lfixture/Demo;"), "legacy Smali still disassembles");
    }

    private static File assemble(String prefix, String cls, String text) throws Exception {
        File source = new File(root, prefix + "-" + cls + ".smali");
        Files.write(source.toPath(), text.getBytes(StandardCharsets.UTF_8));
        File dex = new File(root, prefix + ".dex"); SmaliOptions options = new SmaliOptions();
        options.outputDexFile = dex.getPath(); options.apiLevel = 28; options.jobs = 1;
        check(Smali.assemble(options, Collections.singletonList(source.getPath())), "assemble real " + cls + " DEX"); return dex;
    }
    private static void zip(File output, String[] names, byte[][] bytes) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            for (int i = 0; i < names.length; i++) { zip.putNextEntry(new ZipEntry(names[i])); zip.write(bytes[i]); zip.closeEntry(); }
        }
    }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static String repeat(String text, int count) { StringBuilder out = new StringBuilder(); for (int i = 0; i < count; i++) out.append(text); return out.toString(); }
    private interface Action { void run() throws Exception; }
    private static void fails(Action action, String label) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException | CancellationException e) { check(true, label); return; }
        throw new AssertionError("Expected failure: " + label);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); assertions++; }

    private static final String DEMO =
        ".class public Lfixture/Demo;\n.super Ljava/lang/Object;\n" +
        ".field public static FLAG:I = 0x7\n.field public static final MESSAGE:Ljava/lang/String; = \"field marker\"\n" +
        ".method public static sum(I)I\n.registers 3\nconst/4 v0, 0x0\nconst/4 v1, 0x0\n:loop\nif-ge v1, p0, :done\nadd-int/2addr v0, v1\nadd-int/lit8 v1, v1, 0x1\ngoto :loop\n:done\nreturn v0\n.end method\n" +
        ".method public static abs(I)I\n.registers 1\nif-gez p0, :positive\nneg-int p0, p0\n:positive\nreturn p0\n.end method\n" +
        ".method public static greet()Ljava/lang/String;\n.registers 1\nconst-string v0, \"你好 VeyTerm\"\nreturn-object v0\n.end method\n" +
        ".method public static length(Ljava/lang/String;)I\n.registers 2\ninvoke-virtual {p0}, Ljava/lang/String;->length()I\nmove-result v0\nreturn v0\n.end method\n" +
        ".method public static flag()I\n.registers 1\nsget v0, Lfixture/Demo;->FLAG:I\nreturn v0\n.end method\n" +
        ".method public static safeDivide(II)I\n.registers 3\n:try_start\ndiv-int v0, p0, p1\n:try_end\nreturn v0\n" +
        ".catch Ljava/lang/ArithmeticException; {:try_start .. :try_end} :fail\n:fail\nmove-exception v0\nconst/4 v0, -0x1\nreturn v0\n.end method\n";
    private static final String OTHER = ".class public Lfixture/Other;\n.super Ljava/lang/Object;\n" +
        ".method public static message()Ljava/lang/String;\n.registers 1\nconst-string v0, \"second dex\"\nreturn-object v0\n.end method\n";
}
