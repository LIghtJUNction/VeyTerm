package com.termux.app.files;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Base64;

import com.termux.app.files.fs.FileEntry;
import com.termux.app.files.fs.FileLocation;
import com.termux.app.files.fs.RemoteFileAccess;
import com.termux.app.files.remote.ConnectionStore;
import com.termux.app.files.remote.RemoteFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;
import javax.net.ssl.X509TrustManager;

/** Standalone runner: target APK contains production code; no JUnit/AndroidX dependency. */
public final class RemoteWorkspaceSmoke extends Instrumentation {
    private final AtomicBoolean finished = new AtomicBoolean();
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<String> errors = new ArrayList<>();
    private final List<String> records = new ArrayList<>();
    private volatile int assertions;
    private volatile String stage = "config";
    private Bundle arguments;
    private ConnectionStore store;
    private Context context;
    private String prefix;
    private File configFile;
    private static volatile TrustManager[] fixtureManagers;
    private static final String TRUST_ALGORITHM = "VeyTermRemoteSmokePin";

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? new Bundle() : arguments;
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        int timeout = 240000;
        try { timeout = Integer.parseInt(arguments.getString("timeout_ms", "240000")); }
        catch (NumberFormatException ignored) { }
        final int deadline = Math.max(30000, Math.min(600000, timeout));
        Thread watchdog = new Thread(() -> {
            try {
                if (!done.await(deadline, TimeUnit.MILLISECONDS)) complete(false, "TIMEOUT");
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, "remote-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        TimeZone previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            context = getTargetContext();
            String path = arguments.getString("config_path");
            if (path == null) throw new IOException();
            configFile = new File(path).getCanonicalFile();
            File cache = context.getCacheDir().getCanonicalFile();
            if (!configFile.getPath().startsWith(cache.getPath() + File.separator)
                    || !configFile.getName().equals("config.json")) throw new IOException();
            JSONObject config = new JSONObject(new String(readBounded(new FileInputStream(configFile), 131072), StandardCharsets.UTF_8));
            String task = config.getString("task");
            if (!task.matches("[a-f0-9]{32}")) throw new IOException();
            prefix = "rsm_" + task + "_";
            store = new ConnectionStore(context);
            JSONArray endpoints = config.getJSONArray("connections");
            check(endpoints.length() == 6, "six-endpoints");
            String[] names = {"SFTP_RSA", "SFTP_ED25519", "FTP", "FTPS", "SMB", "WEBDAV_HTTP"};
            for (int i = 0; i < names.length; i++) {
                final JSONObject endpoint = endpoints.getJSONObject(i);
                final String name = names[i];
                check(name.equals(endpoint.getString("name")), "endpoint-order");
                stage = name;
                int before = assertions;
                try {
                    RemoteFiles.Connection connection = connection(endpoint, prefix + i, null, null);
                    stage = name + "/store";
                    encryptedStore(connection, endpoint);
                    if (connection.protocol == RemoteFiles.Protocol.FTPS) {
                        stage = name + "/untrusted";
                        rejected(() -> connectAndStat(connection), "default-trust-rejects");
                        stage = name + "/pin-setup";
                        try (FixtureTrust trust = new FixtureTrust(config)) {
                            stage = name + "/pin-mismatch";
                            trust.wrongPin();
                            try { rejected(() -> connectAndStat(connection), "wrong-pin-rejects"); }
                            finally { trust.restorePin(); }
                            exercise(connection, endpoint, name);
                        }
                    } else {
                        if (connection.protocol == RemoteFiles.Protocol.SFTP) {
                            stage = name + "/host-key";
                            RemoteFiles.Connection wrong = connection(endpoint, prefix + "wrong", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", null);
                            rejected(() -> connectAndStat(wrong), "wrong-host-key-rejects");
                        }
                        if (name.equals("WEBDAV_HTTP")) {
                            stage = name + "/http-opt-in";
                            RemoteFiles.Connection secure = connection(endpoint, prefix + "secure", null, false);
                            rejected(() -> connectAndStat(secure), "no-silent-http");
                        }
                        exercise(connection, endpoint, name);
                    }
                    status(name, assertions - before, "PASS");
                } catch (Throwable failure) {
                    // Bounded class/frame chains locate failures without reading exception messages.
                    String safe = stage + ":" + symbol(failure);
                    errors.add(safe);
                    status(name, assertions - before, safe);
                }
            }
        } catch (Throwable failure) { errors.add(stage + ":" + symbol(failure)); }
        finally {
            stage = "cleanup";
            if (store != null) for (String id : records) {
                try { store.delete(id); }
                catch (Throwable failure) { errors.add("cleanup:" + symbol(failure)); }
            }
            if (configFile != null && configFile.getPath().contains("/remote-smoke-")) configFile.delete();
            TimeZone.setDefault(previousZone);
            done.countDown();
        }
        complete(errors.isEmpty(), "");
    }

    private RemoteFiles.Connection connection(JSONObject e, String id, String hostKey, Boolean insecure) throws Exception {
        return new RemoteFiles.Connection(id, "runtime-fixture", RemoteFiles.Protocol.valueOf(e.getString("protocol")),
                "127.0.0.1", e.getInt("port"), e.getString("root"), e.getString("username"),
                e.getString("password"), e.getString("domain"), hostKey == null ? e.getString("hostKey") : hostKey,
                8000, insecure == null ? e.getBoolean("insecure") : insecure);
    }

    private void encryptedStore(RemoteFiles.Connection c, JSONObject endpoint) throws Exception {
        records.add(c.id);
        store.save(c);
        File record = new File(context.getNoBackupFilesDir(), "remote-connections/" + c.id + ".gcm");
        byte[] encrypted = readBounded(new FileInputStream(record), 65536);
        check(encrypted.length > 30 && encrypted[0] == 1 && encrypted[1] == 12, "gcm-envelope");
        String disk = new String(encrypted, StandardCharsets.ISO_8859_1);
        for (String field : new String[]{"username", "password"}) {
            String expected = endpoint.getString(field);
            check(!expected.isEmpty() && !disk.contains(expected), "no-plaintext-credentials");
        }
        RemoteFiles.Connection loaded = new ConnectionStore(context).load(c.id);
        for (String name : new String[]{"username", "password", "domain"}) {
            Field f = RemoteFiles.Connection.class.getDeclaredField(name);
            f.setAccessible(true);
            check(endpoint.getString(name).equals(f.get(loaded)), "credential-roundtrip");
        }
        check(c.protocol == loaded.protocol && c.port == loaded.port && c.root.equals(loaded.root)
                && c.host.equals(loaded.host) && c.hostKeySha256.equals(loaded.hostKeySha256)
                && c.allowInsecure == loaded.allowInsecure && c.timeoutMillis == loaded.timeoutMillis,
                "connection-roundtrip");
        check(store.ids().contains(c.id), "record-listed");
        // Alter the authentication tag, then copy intact ciphertext under another ID to test AAD binding.
        byte[] damaged = encrypted.clone(); damaged[damaged.length - 1] ^= 1;
        write(record, damaged);
        try { rejected(() -> store.load(c.id), "tag-tamper-rejected"); }
        finally { write(record, encrypted); }
        String other = c.id + "_aad";
        records.add(other);
        write(new File(record.getParentFile(), other + ".gcm"), encrypted);
        rejected(() -> store.load(other), "record-id-tamper-rejected");
        store.delete(other);
        check(!store.ids().contains(other), "record-delete");
    }

    private void exercise(RemoteFiles.Connection c, JSONObject endpoint, String name) throws Exception {
        byte[] data = new byte[160003]; new Random(72).nextBytes(data);
        String dir = "/" + prefix + name;
        String original = dir + "/你好 space #%.bin", renamed = dir + "/移动 #%.bin";
        RemoteFiles.Cancellation cancel = new RemoteFiles.Cancellation();
        stage = name + "/engine-connect";
        // Use the decrypted configuration for actual authenticated traffic.
        try (RemoteFiles remote = RemoteFiles.connect(store.load(c.id), cancel)) {
            stage = name + "/engine-root-stat";
            check(remote.stat("/").directory, "root-stat");
            stage = name + "/engine-mkdir-list";
            remote.mkdir(dir);
            check(has(remote.list("/"), dir), "mkdir-list");
            long[] progress = {0};
            stage = name + "/engine-upload";
            check(remote.upload(original, new ByteArrayInputStream(data), false, (n, total) -> progress[0] = n) == data.length, "upload-count");
            stage = name + "/engine-upload-stat";
            check(progress[0] == data.length && remote.stat(original).size == data.length, "upload-progress-stat");
            stage = name + "/engine-unicode-list";
            check(has(remote.list(dir), original), "unicode-list");
            stage = name + "/engine-read";
            try (InputStream in = remote.read(original)) { check(Arrays.equals(data, readBounded(in, 200000)), "binary-identical"); }
            stage = name + "/engine-no-clobber";
            rejected(() -> { try (OutputStream out = remote.write(original, false)) { out.write(1); } }, "no-clobber");
            stage = name + "/metadata";
            if (endpoint.getBoolean("chmod")) {
                remote.chmod(original, 0640);
                check(true, "chmod-completed");
                if (c.protocol == RemoteFiles.Protocol.SFTP) check(remote.permissions(original).startsWith("0640"), "chmod-readback");
            }
            if (endpoint.getBoolean("mtime")) {
                long epoch = 1577836800000L;
                remote.setModified(original, epoch);
                check(Math.abs(remote.stat(original).modified - epoch) < 120000, "mtime-readback");
            }
            if (c.protocol == RemoteFiles.Protocol.SFTP) {
                String link = dir + "/链接";
                remote.symlink("你好 space #%.bin", link);
                check(remote.stat(link).symbolicLink, "lstat-link");
                check(remote.readLink(link).equals("你好 space #%.bin"), "link-text");
                remote.delete(link);
                check(!has(remote.list(dir), link), "link-delete");
            }
            stage = name + "/engine";
            remote.rename(original, renamed);
            check(!has(remote.list(dir), original) && remote.stat(renamed).size == data.length, "rename");
            try (OutputStream out = remote.write(renamed, true)) { out.write(new byte[]{0, -1, 3}); }
            check(remote.stat(renamed).size == 3, "replace-truncates");
            String zero = dir + "/空文件";
            try (OutputStream ignored = remote.write(zero, false)) { }
            check(remote.stat(zero).size == 0, "zero-stat");
            try (InputStream in = remote.read(zero)) { check(in.read() == -1, "zero-eof"); }
            remote.delete(zero); remote.delete(renamed);
            check(remote.list(dir).isEmpty(), "file-delete");
            remote.delete(dir);
            check(!has(remote.list("/"), dir), "directory-delete");
            stage = name + "/cancel";
            cancel.cancel();
            rejected(() -> remote.list("/"), "cancel-connected");
        }
        RemoteFiles.Cancellation preCancelled = new RemoteFiles.Cancellation();
        preCancelled.cancel();
        rejected(() -> { try (RemoteFiles ignored = RemoteFiles.connect(c, preCancelled)) { } }, "cancel-before-connect");
        stage = name + "/adapter";
        RemoteFileAccess access = new RemoteFileAccess(context);
        FileLocation root = RemoteFileAccess.location(c.id, "/");
        FileLocation folder = access.child(root, prefix + "adapter_" + name);
        access.mkdir(folder);
        check(access.stat(folder).directory && access.contains(root, folder), "adapter-mkdir-stat");
        FileLocation file = access.child(folder, "适配器 #%.bin");
        try (OutputStream out = access.write(file, false)) { out.write(data); }
        check(access.stat(file).size == data.length && access.exists(file), "adapter-write-stat");
        boolean listed = false;
        for (FileEntry entry : access.list(folder)) if (entry.location.equals(file)) listed = true;
        check(listed, "adapter-unicode-list");
        try (InputStream in = access.read(file)) { check(Arrays.equals(data, readBounded(in, 200000)), "adapter-read"); }
        FileLocation moved = access.rename(file, "已移动.bin");
        check(!access.exists(file) && access.exists(moved), "adapter-rename");
        FileLocation empty = access.createFile(folder, "零");
        check(access.stat(empty).size == 0, "adapter-zero-create");
        try (InputStream in = access.read(empty)) { check(in.read() == -1, "adapter-zero-read"); }
        access.delete(empty); access.delete(moved); access.delete(folder);
        check(!access.exists(folder), "adapter-delete");
    }

    private static boolean has(List<RemoteFiles.Entry> entries, String path) {
        for (RemoteFiles.Entry e : entries) if (path.equals(e.path)) return true;
        return false;
    }
    private void connectAndStat(RemoteFiles.Connection c) throws Exception {
        try (RemoteFiles remote = RemoteFiles.connect(c, new RemoteFiles.Cancellation())) { remote.stat("/"); }
    }
    private interface Task { void run() throws Exception; }
    private void rejected(Task task, String name) throws Exception {
        try { task.run(); } catch (IOException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }
    private void check(boolean ok, String name) {
        assertions++;
        if (!ok) throw new AssertionError(name);
    }
    private static byte[] readBounded(InputStream source, int limit) throws IOException {
        try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new IOException();
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
    private static void write(File file, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) { out.write(data); }
    }
    private static String symbol(Throwable failure) {
        return RemoteFiles.safeDiagnostic(failure);
    }

    private void status(String name, int count, String error) {
        Bundle result = new Bundle();
        result.putString("name", name); result.putInt("assertions", count); result.putString("error", error);
        sendStatus(0, result);
    }
    private void complete(boolean success, String error) {
        if (!finished.compareAndSet(false, true)) return;
        Bundle result = new Bundle();
        result.putString("name", "remote-workspace-smoke"); result.putInt("assertions", assertions);
        result.putString("errors", error.isEmpty() ? errors.toString() : stage + ":" + error);
        finish(success ? -1 : 1, result);
    }

    // This provider changes only the instrumentation process, never Android's CA store or BC order.
    public static final class PinFactory extends TrustManagerFactorySpi {
        @Override protected void engineInit(KeyStore ignored) { }
        @Override protected void engineInit(ManagerFactoryParameters ignored) { }
        @Override protected TrustManager[] engineGetTrustManagers() { return fixtureManagers.clone(); }
    }
    private static final class FixtureTrust implements AutoCloseable {
        private final String previous;
        private final Provider provider;
        private final byte[] correctPin;
        private volatile byte[] currentPin;
        FixtureTrust(JSONObject config) throws Exception {
            byte[] der = Base64.decode(config.getString("tlsCertificate"), Base64.DEFAULT);
            byte[] pin = Base64.decode(config.getString("tlsSha256"), Base64.DEFAULT);
            if (!MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(der))) throw new CertificateException();
            correctPin = pin;
            currentPin = pin;
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
            trust.setCertificateEntry("fixture-only", cert);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            X509TrustManager found = null;
            for (TrustManager m : factory.getTrustManagers()) if (m instanceof X509TrustManager) found = (X509TrustManager) m;
            if (found == null) throw new CertificateException();
            final X509TrustManager delegate = found;
            fixtureManagers = new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException(); }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    try {
                        if (chain.length == 0 || !MessageDigest.isEqual(currentPin, MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()))) throw new CertificateException();
                        chain[0].checkValidity();
                        delegate.checkServerTrusted(chain, auth);
                    } catch (CertificateException e) { throw e; }
                    catch (Exception e) { throw new CertificateException(); }
                }
            }};
            previous = Security.getProperty("ssl.TrustManagerFactory.algorithm");
            provider = new Provider("VeyTermRemoteSmoke", 1.0, "Fixture-only TLS pin") { };
            provider.put("TrustManagerFactory." + TRUST_ALGORITHM, PinFactory.class.getName());
            if (Security.addProvider(provider) == -1) throw new CertificateException();
            Security.setProperty("ssl.TrustManagerFactory.algorithm", TRUST_ALGORITHM);
        }
        void wrongPin() {
            byte[] wrong = correctPin.clone(); wrong[0] ^= 1;
            currentPin = wrong;
        }
        void restorePin() { currentPin = correctPin; }
        @Override public void close() {
            Security.setProperty("ssl.TrustManagerFactory.algorithm", previous);
            Security.removeProvider(provider.getName());
            fixtureManagers = null;
        }
    }
}
