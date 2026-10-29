package com.termux.app.files.remote;

import jcifs.CIFSContext;
import jcifs.SmbConstants;
import jcifs.context.BaseContext;
import jcifs.config.PropertyConfiguration;
import jcifs.smb.*;
import java.io.*;
import java.net.URL;
import java.util.*;

final class SmbBackend implements RemoteFiles.Backend {
    private final RemoteFiles.Connection config;
    private volatile CIFSContext context;
    private volatile boolean closed;
    SmbBackend(RemoteFiles.Connection c) { config=c; }
    @Override public void connect() throws Exception {
        Properties props=new Properties();
        props.setProperty("jcifs.smb.client.minVersion","SMB202");
        props.setProperty("jcifs.smb.client.maxVersion","SMB311");
        props.setProperty("jcifs.smb.client.disableSMB1","true");
        props.setProperty("jcifs.smb.client.signingEnforced","true");
        props.setProperty("jcifs.smb.client.connTimeout",String.valueOf(config.timeoutMillis));
        props.setProperty("jcifs.smb.client.soTimeout",String.valueOf(config.timeoutMillis));
        props.setProperty("jcifs.smb.client.responseTimeout",String.valueOf(config.timeoutMillis));
        props.setProperty("jcifs.smb.client.dfs.disabled","true");
        context=new BaseContext(new PropertyConfiguration(props)).withCredentials(new NtlmPasswordAuthenticator(config.domain,config.username,config.password));
        if (closed) throw new IOException();
        try (SmbFile root=file("/",true)) { if (!root.exists() || !root.isDirectory()) throw new IOException(); }
    }
    private SmbFile file(String path,boolean dir) throws Exception {
        String actual=RemoteFiles.actual(config,path); if (dir && !actual.endsWith("/")) actual+="/";
        final String rawPath=actual;
        // jcifs consumes URL.getPath literally; percent-encoding here changes the actual SMB filename.
        URL url=new URL(null,"smb://placeholder/",new Handler() {
            @Override protected void parseURL(URL target,String spec,int start,int limit) {
                setURL(target,"smb",config.host,config.port,config.host+":"+config.port,null,rawPath,null,null);
            }
        });
        return new SmbFile(url,context);
    }
    private RemoteFiles.Entry entry(String p,SmbFile f) throws Exception { return new RemoteFiles.Entry(p,f.isDirectory(),(f.getAttributes() & 0x400)!=0,f.length(),f.lastModified()); }
    @Override public List<RemoteFiles.Entry> list(String path) throws Exception {
        List<RemoteFiles.Entry> result=new ArrayList<>();
        try (SmbFile dir=file(path,true)) {
            for (SmbFile f : dir.listFiles()) try (SmbFile item=f) {
                String name=f.getName(); if (name.endsWith("/")) name=name.substring(0,name.length()-1);
                result.add(entry(RemoteFiles.child(path,name),item));
            }
        }
        return result;
    }
    @Override public RemoteFiles.Entry stat(String path) throws Exception { try (SmbFile f=file(path,false)) { if (!f.exists()) throw new FileNotFoundException(); return entry(path,f); } }
    @Override public InputStream read(String path) throws Exception {
        SmbFile f=file(path,false);
        try { return new FilterInputStream(new SmbFileInputStream(f)) { @Override public void close() throws IOException { try { super.close(); } finally { f.close(); } } }; }
        catch (Exception e) { f.close(); throw e; }
    }
    @Override public OutputStream write(String path,boolean replace) throws Exception {
        SmbFile f=file(path,false);
        try {
            int flags=SmbConstants.O_WRONLY|SmbConstants.O_CREAT|(replace ? SmbConstants.O_TRUNC : SmbConstants.O_EXCL);
            return new FilterOutputStream(f.openOutputStream(false,flags,SmbConstants.FILE_WRITE_DATA,SmbConstants.FILE_SHARE_READ)) {
                @Override public void write(byte[] b,int o,int n) throws IOException { out.write(b,o,n); }
                @Override public void close() throws IOException { try { super.close(); } finally { f.close(); } }
            };
        } catch (Exception e) { f.close(); throw e; }
    }
    @Override public void mkdir(String path) throws Exception { try (SmbFile f=file(path,true)) { f.mkdir(); } }
    @Override public void rename(String from,String to) throws Exception {
        boolean dir=stat(from).directory;
        try (SmbFile a=file(from,dir); SmbFile b=file(to,dir)) { if (b.exists()) throw new IOException(); a.renameTo(b,false); }
    }
    @Override public void delete(String path) throws Exception {
        RemoteFiles.Entry entry=stat(path); boolean dir=entry.directory;
        if (entry.symbolicLink) throw new IOException();
        // jcifs delete recursively traverses directories; refuse observed nonempty directories.
        try (SmbFile f=file(path,dir)) { if (dir && f.list().length!=0) throw new IOException(); f.delete(); }
    }
    @Override public void close() throws IOException { closed=true; if (context!=null) context.close(); }
}
