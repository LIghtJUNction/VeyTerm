package com.termux.app.files.apk;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Private source drafts only, never signing keys/passwords. Stable path key plus full APK fingerprint. */
public final class ApkDrafts {
    private static final int LIMIT = 8 * 1024 * 1024;
    private ApkDrafts() {}

    public static final class Draft {
        public final String path, fingerprint, kind, entry, descriptor, text;
        public Draft(File apk, String fingerprint, String kind, String entry, String descriptor, String text) throws IOException {
            this(apk.getCanonicalPath(), fingerprint, kind, entry, descriptor == null ? "" : descriptor, text);
        }
        private Draft(String path, String fingerprint, String kind, String entry, String descriptor, String text) {
            this.path = path; this.fingerprint = fingerprint; this.kind = kind;
            this.entry = entry; this.descriptor = descriptor; this.text = text;
        }
        public boolean matches(File apk) throws IOException {
            return path.equals(apk.getCanonicalPath()) && fingerprint.equals(ApkZip.fingerprint(apk));
        }
    }

    private static File file(File privateDirectory, File apk) throws Exception {
        return new File(privateDirectory, ApkZip.hex(MessageDigest.getInstance("SHA-256")
            .digest(apk.getCanonicalPath().getBytes(StandardCharsets.UTF_8))) + ".draft");
    }
    public static void save(File privateDirectory, File apk, Draft draft) throws Exception {
        if (!draft.path.equals(apk.getCanonicalPath())) throw new IOException("Draft belongs to a different APK");
        ApkZip.safeName(draft.entry);
        if (!privateDirectory.isDirectory() && !privateDirectory.mkdirs()) throw new IOException("Cannot create private draft directory");
        File target = file(privateDirectory, apk), temp = File.createTempFile("apk-draft-", ".tmp", privateDirectory);
        try {
            try (FileOutputStream stream = new FileOutputStream(temp); DataOutputStream out = new DataOutputStream(stream)) {
                out.writeInt(0x41504b32); out.writeUTF(draft.path); out.writeUTF(draft.fingerprint);
                out.writeUTF(draft.kind); out.writeUTF(draft.entry); out.writeUTF(draft.descriptor);
                byte[] text = draft.text.getBytes(StandardCharsets.UTF_8);
                if (text.length > LIMIT) throw new IOException("Draft exceeds 8 MiB UTF-8");
                out.writeInt(text.length); out.write(text); out.flush(); stream.getFD().sync();
            }
            ApkZip.checkCancelled();
            if (!temp.renameTo(target)) throw new IOException("Cannot atomically persist editor draft");
        } finally { temp.delete(); }
    }
    public static Draft load(File privateDirectory, File apk) throws Exception {
        File target = file(privateDirectory, apk); if (!target.isFile()) return null;
        if (target.length() > LIMIT + 400000) throw new IOException("Invalid draft size");
        try (DataInputStream in = new DataInputStream(new FileInputStream(target))) {
            if (in.readInt() != 0x41504b32) throw new IOException("Unknown draft schema");
            String path = in.readUTF(), fingerprint = in.readUTF(), kind = in.readUTF(), entry = in.readUTF(), descriptor = in.readUTF();
            int length = in.readInt(); if (length < 0 || length > LIMIT) throw new IOException("Invalid draft text size");
            byte[] bytes = new byte[length]; in.readFully(bytes);
            if (in.read() != -1) throw new IOException("Trailing draft data");
            ApkZip.safeName(entry);
            if (!path.equals(apk.getCanonicalPath())) throw new IOException("Draft belongs to a different APK");
            return new Draft(path, fingerprint, kind, entry, descriptor, new String(bytes, StandardCharsets.UTF_8));
        }
    }
    public static void discard(File privateDirectory, File apk) throws Exception {
        File target = file(privateDirectory, apk);
        if (target.exists() && !target.delete()) throw new IOException("Cannot discard saved draft");
    }
}
