package com.termux.app.files.remote;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.security.KeyStore;
import java.util.*;

/** AES-GCM encrypted records in noBackupFilesDir; no plaintext preferences or credential-bearing URIs. API 23+. */
public final class ConnectionStore {
    private static final String ALIAS="veyterm.remote.connections.v1";
    private final File directory;
    public ConnectionStore(Context context) throws IOException {
        if (android.os.Build.VERSION.SDK_INT<23) throw new IOException("Saved remote connections require Android 6 or newer");
        directory=new File(context.getNoBackupFilesDir(),"remote-connections");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create connection store");
    }
    private File file(String id) {
        if (id==null || !id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid connection identifier");
        return new File(directory,id+".gcm");
    }
    private static synchronized SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return gen.generateKey();
    }
    public void save(RemoteFiles.Connection c) throws IOException { synchronized (ConnectionStore.class) { saveLocked(c); } }
    private void saveLocked(RemoteFiles.Connection c) throws IOException {
        AtomicFile target=new AtomicFile(file(c.id)); FileOutputStream out=null; byte[] plain=null;
        try {
            JSONObject o=new JSONObject(); o.put("id",c.id).put("label",c.label).put("protocol",c.protocol.name()).put("host",c.host).put("port",c.port).put("root",c.root)
                .put("username",c.username).put("password",c.password).put("domain",c.domain).put("hostKey",c.hostKeySha256).put("timeout",c.timeoutMillis).put("insecure",c.allowInsecure);
            plain=o.toString().getBytes("UTF-8");
            if (plain.length>65000) throw new IOException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key()); cipher.updateAAD(c.id.getBytes("UTF-8"));
            out=target.startWrite(); out.write(1); out.write(cipher.getIV().length); out.write(cipher.getIV()); out.write(cipher.doFinal(plain)); target.finishWrite(out);
        } catch (Exception e) { if (out!=null) target.failWrite(out); throw new IOException("Cannot encrypt remote connection"); }
        finally { if (plain!=null) Arrays.fill(plain,(byte)0); }
    }
    public RemoteFiles.Connection load(String id) throws IOException { synchronized (ConnectionStore.class) { return loadLocked(id); } }
    private RemoteFiles.Connection loadLocked(String id) throws IOException {
        byte[] plain=null;
        try (FileInputStream in=new AtomicFile(file(id)).openRead()) {
            if (in.getChannel().size()>65536 || in.read()!=1 || in.read()!=12) throw new IOException();
            byte[] iv=new byte[12]; new DataInputStream(in).readFully(iv);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] b=new byte[2048]; int n; while ((n=in.read(b))!=-1) bytes.write(b,0,n);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv)); cipher.updateAAD(id.getBytes("UTF-8"));
            plain=cipher.doFinal(bytes.toByteArray()); JSONObject o=new JSONObject(new String(plain,"UTF-8"));
            if (!id.equals(o.getString("id"))) throw new IOException();
            return new RemoteFiles.Connection(id,o.getString("label"),RemoteFiles.Protocol.valueOf(o.getString("protocol")),o.getString("host"),o.getInt("port"),o.getString("root"),o.getString("username"),o.getString("password"),o.getString("domain"),o.getString("hostKey"),o.getInt("timeout"),o.getBoolean("insecure"));
        } catch (Exception e) { throw new IOException("Cannot decrypt remote connection; re-enter credentials if the device key changed"); }
        finally { if (plain!=null) Arrays.fill(plain,(byte)0); }
    }
    public List<String> ids() throws IOException { synchronized (ConnectionStore.class) { return idsLocked(); } }
    private List<String> idsLocked() throws IOException {
        TreeSet<String> ids=new TreeSet<>(); String[] files=directory.list(); if (files==null) throw new IOException("Cannot read connection store");
        for (String name:files) if (name.endsWith(".gcm") || name.endsWith(".gcm.bak")) {
            String id=name.substring(0,name.indexOf(".gcm")); if (id.matches("[a-zA-Z0-9_-]{1,80}")) ids.add(id);
        }
        return new ArrayList<>(ids);
    }
    public List<RemoteFiles.Connection> list() throws IOException { synchronized (ConnectionStore.class) { return listLocked(); } }
    private List<RemoteFiles.Connection> listLocked() throws IOException {
        List<RemoteFiles.Connection> records=new ArrayList<>(); for (String id:ids()) records.add(load(id)); return records;
    }
    public void delete(String id) { synchronized (ConnectionStore.class) { deleteLocked(id); } }
    private void deleteLocked(String id) { new AtomicFile(file(id)).delete(); }
}
