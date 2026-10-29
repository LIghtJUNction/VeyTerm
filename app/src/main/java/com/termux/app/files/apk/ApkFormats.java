package com.termux.app.files.apk;

import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.ResXmlDocument;
import com.reandroid.json.JSONObject;
import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.formatter.BaksmaliWriter;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual format adapters; structured resource JSON retains typed values and numeric resource IDs. */
public final class ApkFormats {
    public static final int EDIT_LIMIT = 64 * 1024 * 1024;
    private ApkFormats() {}

    public static DexBackedDexFile dex(byte[] bytes) throws IOException {
        ApkZip.checkCancelled();
        if (bytes.length > EDIT_LIMIT) throw new IOException("DEX exceeds 64 MiB editor limit");
        if (bytes.length < 8 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' || bytes[3] != '\n')
            throw new IOException("Expected standard DEX (compact/optimized DEX is unsupported)");
        // Null selects opcodes from the file header, rather than silently using an older API.
        return new DexBackedDexFile(null, bytes);
    }

    public static List<String> classes(byte[] bytes) throws IOException {
        List<String> result = new ArrayList<>();
        for (ClassDef c : dex(bytes).getClasses()) { ApkZip.checkCancelled(); result.add(c.getType()); }
        Collections.sort(result); return result;
    }

    public static List<String> strings(byte[] bytes) throws IOException {
        List<String> result = new ArrayList<>();
        for (StringReference s : dex(bytes).getStringReferences()) { ApkZip.checkCancelled(); result.add(s.getString()); }
        return result;
    }

    private static ClassDef find(DexBackedDexFile dex, String type) throws IOException {
        for (ClassDef c : dex.getClasses()) { ApkZip.checkCancelled(); if (c.getType().equals(type)) return c; }
        throw new IOException("Class not found: " + type);
    }

    public static List<String> members(byte[] bytes, String type) throws IOException {
        ClassDef c = find(dex(bytes), type); List<String> result = new ArrayList<>();
        for (Field f : c.getFields()) result.add("FIELD " + f);
        for (Method m : c.getMethods()) result.add("METHOD " + m);
        return result;
    }

    public static String smali(byte[] bytes, String type) throws IOException {
        StringWriter text = new StringWriter();
        ClassDefinition definition = new ClassDefinition(new BaksmaliOptions(), find(dex(bytes), type));
        try (BaksmaliWriter writer = new BaksmaliWriter(text)) { definition.writeTo(writer); }
        if (definition.hadValidationErrors()) throw new IOException("Disassembly contains validation errors");
        return text.toString();
    }

    /** Assemble edited source and rebuild the entire original DEX, preserving all other classes. */
    public static byte[] replaceClass(byte[] original, String type, String source, File scratch) throws IOException {
        DexBackedDexFile old = dex(original); find(old, type);
        File smali = File.createTempFile("apk-class-", ".smali", scratch);
        File assembled = File.createTempFile("apk-class-", ".dex", scratch);
        try {
            try (Writer out = new OutputStreamWriter(new FileOutputStream(smali), StandardCharsets.UTF_8)) { out.write(source); }
            SmaliOptions options = new SmaliOptions(); options.apiLevel = old.getOpcodes().api;
            options.jobs = 1; options.outputDexFile = assembled.getAbsolutePath(); options.verboseErrors = true;
            if (!Smali.assemble(options, smali.getAbsolutePath()))
                throw new IOException("Smali assembly rejected the source. Check syntax, registers and instruction operands.");
            ApkZip.checkCancelled();
            DexBackedDexFile changed;
            try (InputStream in = new BufferedInputStream(new FileInputStream(assembled))) {
                changed = DexBackedDexFile.fromInputStream(old.getOpcodes(), in);
            }
            if (changed.getClasses().size() != 1) throw new IOException("Editor requires exactly one class");
            ClassDef replacement = find(changed, type); // Renaming needs reference rewriting, so reject it.
            DexPool pool = new DexPool(old.getOpcodes());
            for (ClassDef c : old.getClasses()) { ApkZip.checkCancelled(); pool.internClass(c.getType().equals(type) ? replacement : c); }
            MemoryDataStore out = new MemoryDataStore();
            try {
                pool.writeTo(out); byte[] result = out.getData(); dex(result); ApkZip.checkCancelled(); return result;
            } finally { out.close(); }
        } finally { smali.delete(); assembled.delete(); }
    }

    public static void disassemble(byte[] bytes, File directory) throws IOException {
        File temp = ApkZip.temporary(directory);
        try {
            if (!temp.delete() || !temp.mkdir()) throw new IOException("Cannot create Smali workspace");
            if (!Baksmali.disassembleDexFile(dex(bytes), temp, 1, new BaksmaliOptions()))
                throw new IOException("Baksmali failed; no workspace published");
            ApkZip.publish(temp, directory);
        } finally { deleteScratch(temp); }
    }

    public static void assemble(File smaliDirectory, File dexOutput, int api) throws IOException {
        if (!smaliDirectory.isDirectory()) throw new IOException("Not a Smali directory");
        validateWorkspace(smaliDirectory, smaliDirectory.getCanonicalPath() + File.separator, new int[]{0}, 0);
        File temp = ApkZip.temporary(dexOutput);
        try {
            SmaliOptions options = new SmaliOptions(); options.jobs = 1; options.apiLevel = api;
            options.outputDexFile = temp.getAbsolutePath(); options.verboseErrors = true;
            if (!Smali.assemble(options, smaliDirectory.getAbsolutePath())) throw new IOException("Smali assembly failed");
            try (InputStream in = new BufferedInputStream(new FileInputStream(temp))) {
                DexBackedDexFile.fromInputStream(null, in).getClasses().size();
            }
            ApkZip.publish(temp, dexOutput);
        } finally { temp.delete(); }
    }

    private static void validateWorkspace(File dir, String root, int[] count, int depth) throws IOException {
        ApkZip.checkCancelled();
        if (depth > 128) throw new IOException("Smali workspace too deep");
        File[] files = dir.listFiles(); if (files == null) throw new IOException("Unreadable workspace: " + dir);
        for (File f : files) {
            if (++count[0] > ApkZip.MAX_ENTRIES) throw new IOException("Too many workspace files");
            if (!f.getCanonicalPath().startsWith(root) || !f.getCanonicalFile().equals(f.getAbsoluteFile()))
                throw new IOException("Workspace contains a symlink or escaping path");
            if (f.isDirectory()) validateWorkspace(f, root, count, depth + 1);
            else if (f.length() > EDIT_LIMIT) throw new IOException("Smali source exceeds editor limit");
        }
    }

    public static String decodeAxml(byte[] binary) throws IOException {
        ResXmlDocument doc = xml(binary);
        return envelope("AXML", doc.toJson());
    }

    public static String xmlPreview(byte[] binary) throws IOException {
        StringWriter out = new StringWriter();
        org.xmlpull.v1.XmlSerializer serializer = com.reandroid.xml.XMLFactory.newSerializer(out);
        // Raw typed values preserve numeric references without requiring a downloaded Android framework.
        xml(binary).serialize(serializer, false); serializer.flush(); return out.toString();
    }

    public static byte[] compileAxml(String json) throws IOException {
        try {
            ResXmlDocument doc = new ResXmlDocument(); doc.fromJson(document(json, "AXML")); doc.refreshFull();
            if (doc.getDocumentElement() == null) throw new IOException("AXML has no root element");
            byte[] result = doc.getBytes(); xml(result); return result;
        } catch (RuntimeException e) { throw new IOException("Invalid AXML document: " + e.getMessage(), e); }
    }

    private static ResXmlDocument xml(byte[] binary) throws IOException {
        ApkZip.checkCancelled();
        if (binary.length > EDIT_LIMIT || !ResXmlDocument.isResXmlBlock(binary)) throw new IOException("Expected binary Android XML");
        ResXmlDocument doc = new ResXmlDocument(); doc.readBytes(new ByteArrayInputStream(binary));
        if (doc.getDocumentElement() == null) throw new IOException("AXML has no root element");
        return doc;
    }

    public static String decodeArsc(byte[] binary) throws IOException {
        return envelope("ARSC", table(binary).toJson());
    }

    public static byte[] compileArsc(String json) throws IOException {
        try {
            TableBlock table = new TableBlock(); table.fromJson(document(json, "ARSC")); table.refreshFull();
            if (table.size() == 0) throw new IOException("Resource table has no packages");
            byte[] result = table.getBytes(); table(result); return result;
        } catch (RuntimeException e) { throw new IOException("Invalid ARSC document: " + e.getMessage(), e); }
    }

    private static TableBlock table(byte[] binary) throws IOException {
        ApkZip.checkCancelled();
        if (binary.length > EDIT_LIMIT || binary.length < 8 || binary[0] != 2 || binary[1] != 0)
            throw new IOException("Expected resources.arsc binary table");
        TableBlock table = new TableBlock(); table.readBytes(new ByteArrayInputStream(binary));
        if (table.size() == 0) throw new IOException("Resource table has no packages");
        return table;
    }

    private static String envelope(String format, JSONObject doc) {
        return new JSONObject().put("format", format).put("schema", 1).put("document", doc).toString(2);
    }
    private static JSONObject document(String text, String format) throws IOException {
        ApkZip.checkCancelled();
        if (text.length() > EDIT_LIMIT) throw new IOException("Resource JSON exceeds 64 MiB limit");
        JSONObject json = new JSONObject(text);
        if (!format.equals(json.getString("format")) || json.getInt("schema") != 1)
            throw new IOException("Wrong resource document format/schema");
        return json.getJSONObject("document");
    }

    static void deleteScratch(File file) {
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File f : children) deleteScratch(f); }
        file.delete();
    }
}
