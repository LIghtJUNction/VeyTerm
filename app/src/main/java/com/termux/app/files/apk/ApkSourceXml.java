package com.termux.app.files.apk;

import com.reandroid.apk.AndroidFrameworks;
import com.reandroid.apk.FrameworkApk;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.xml.*;
import com.reandroid.xml.XMLFactory;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.util.*;

/** ARSCLib source XML codec. The APK table and bundled framework supply real resource symbols. */
public final class ApkSourceXml {
    private ApkSourceXml() {}

    private static PackageBlock context(File apk) throws IOException {
        ResXmlDocument manifest = read(ApkZip.read(apk, "AndroidManifest.xml", ApkFormats.EDIT_LIMIT));
        String name = ApkPackageSet.string(manifest.getDocumentElement(), "package", false, "");
        if (name == null || name.isEmpty()) throw new IOException("Manifest package missing");
        TableBlock table = null;
        for (ApkZip.Entry entry : ApkZip.list(apk)) if (entry.name.equals("resources.arsc")) {
            table = TableBlock.load(new ByteArrayInputStream(ApkZip.read(apk, entry.name, ApkFormats.EDIT_LIMIT))); break;
        }
        PackageBlock pkg = null;
        if (table == null) { table = new TableBlock(); pkg = table.newPackage(0x7f, name); }
        else {
            Iterator<PackageBlock> packages = table.getPackages(name);
            if (packages.hasNext()) pkg = packages.next();
            if (pkg == null && table.size() == 1) pkg = table.pickOne();
            if (pkg == null) throw new IOException("Cannot select resource package for " + name);
        }
        // Use bundled symbols only; no framework downloads or external framework installation.
        FrameworkApk framework = AndroidFrameworks.getLatest();
        if (framework == null) throw new IOException("Bundled ARSCLib Android framework is missing");
        table.addFramework(framework.getTableBlock());
        table.setCurrentPackage(pkg);
        return pkg;
    }

    public static String decode(File apk, String entry) throws IOException {
        ResXmlDocument doc = read(ApkZip.read(apk, entry, ApkFormats.EDIT_LIMIT));
        doc.setPackageBlock(context(apk));
        StringWriter out = new StringWriter();
        org.xmlpull.v1.XmlSerializer serializer = XMLFactory.newSerializer(out);
        doc.serialize(serializer, true); serializer.flush(); ApkZip.checkCancelled();
        return out.toString();
    }

    public static byte[] compile(File apk, String source) throws IOException {
        ApkZip.checkCancelled();
        if (source.length() > 2 * 1024 * 1024) throw new IOException("Source XML exceeds editor limit");
        if (source.contains("<!DOCTYPE") || source.contains("<!ENTITY")) throw new IOException("DTD/entities are not supported");
        try {
            PackageBlock pkg = context(apk);
            // Validate with strict upstream attribute encoding before parse(), whose default is lenient.
            XmlPullParser check = XMLFactory.newPullParser(source);
            ResXmlDocument probe = new ResXmlDocument(); probe.setPackageBlock(pkg);
            ResXmlElement node = probe.newElement("probe");
            int roots = 0;
            while (check.getEventType() != XmlPullParser.END_DOCUMENT) {
                ApkZip.checkCancelled();
                if (check.getEventType() == XmlPullParser.START_TAG) {
                    if (check.getDepth() == 1) roots++;
                    for (int i = 0; i < check.getAttributeCount(); i++) {
                        if (check.getAttributeValue(i).startsWith("@+"))
                            throw new IOException("Creating resource IDs requires a resource-table rebuild; use an existing @id/name");
                        ResXmlAttribute attr = node.newAttribute("probe", 0);
                        attr.encode(true, check.getAttributeNamespace(i), check.getAttributePrefix(i),
                            check.getAttributeName(i), check.getAttributeValue(i));
                        attr.removeSelf();
                    }
                }
                check.nextToken();
            }
            if (roots != 1) throw new IOException("XML must have one root element");
            ResXmlDocument doc = new ResXmlDocument(); doc.setPackageBlock(pkg);
            doc.parse(XMLFactory.newPullParser(source)); doc.refreshFull();
            byte[] result = doc.getBytes(); read(result); ApkZip.checkCancelled(); return result;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Source XML compilation failed: " + e.getMessage(), e);
        }
    }

    static ResXmlDocument read(byte[] bytes) throws IOException {
        if (bytes.length > ApkFormats.EDIT_LIMIT || !ResXmlDocument.isResXmlBlock(bytes))
            throw new IOException("Expected compiled Android XML");
        ResXmlDocument doc = new ResXmlDocument(); doc.readBytes(new ByteArrayInputStream(bytes));
        if (doc.getDocumentElement() == null) throw new IOException("XML has no root");
        return doc;
    }
}
