package com.termux.app.files.remote;

import org.apache.commons.net.ftp.*;
import javax.net.ssl.*;
import java.io.*;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.*;

final class FtpBackend implements RemoteFiles.Backend {
    private final RemoteFiles.Connection config;
    private final FTPClient ftp;
    private volatile boolean closed;
    FtpBackend(RemoteFiles.Connection c) { config=c; ftp=c.protocol==RemoteFiles.Protocol.FTPS ? new FTPSClient(false) : new FTPClient(); }
    @Override public void connect() throws Exception {
        ftp.setConnectTimeout(config.timeoutMillis); ftp.setDefaultTimeout(config.timeoutMillis);
        ftp.setDataTimeout(config.timeoutMillis); ftp.setControlEncoding("UTF-8");
        if (ftp instanceof FTPSClient) {
            FTPSClient tls=(FTPSClient)ftp;
            TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init((KeyStore)null);
            for (TrustManager m : trust.getTrustManagers()) if (m instanceof X509TrustManager) tls.setTrustManager(m);
            tls.setEndpointCheckingEnabled(true);
        }
        if (closed) throw new IOException();
        ftp.connect(config.host,config.port);
        if (closed || !FTPReply.isPositiveCompletion(ftp.getReplyCode()) || !ftp.login(config.username,config.password)) throw new IOException();
        ftp.setSoTimeout(config.timeoutMillis); ftp.enterLocalPassiveMode();
        if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) throw new IOException();
        if (ftp instanceof FTPSClient) { ((FTPSClient)ftp).execPBSZ(0); ((FTPSClient)ftp).execPROT("P"); }
        if (!ftp.changeWorkingDirectory(config.root)) throw new IOException();
    }
    private String p(String path) { return RemoteFiles.actual(config,RemoteFiles.path(path)); }
    private void require(boolean ok) throws IOException { if (!ok) throw new IOException(); }
    private void requireMetadata(boolean ok) throws IOException {
        if (!ok && (ftp.getReplyCode()==500 || ftp.getReplyCode()==502 || ftp.getReplyCode()==504))
            throw new UnsupportedOperationException();
        require(ok);
    }
    @Override public void chmod(String path, int mode) throws Exception {
        if (stat(path).symbolicLink) throw new IOException();
        requireMetadata(ftp.sendSiteCommand("CHMOD " + String.format(Locale.ROOT,"%04o",mode) + " " + p(path)));
    }
    @Override public void setModified(String path, long epochMillis) throws Exception {
        // MFMT requires a four-digit UTC year, never a locale-specific or overflowing date.
        if (epochMillis > 253402300799999L || stat(path).symbolicLink) throw new IOException();
        SimpleDateFormat utc=new SimpleDateFormat("yyyyMMddHHmmss",Locale.ROOT);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        requireMetadata(ftp.setModificationTime(p(path),utc.format(new Date(epochMillis))));
    }
    private RemoteFiles.Entry entry(String path, FTPFile f) { return new RemoteFiles.Entry(path,f.isDirectory(),f.isSymbolicLink(),f.getSize(),f.getTimestamp()==null ? 0 : f.getTimestamp().getTimeInMillis()); }
    @Override public List<RemoteFiles.Entry> list(String path) throws Exception {
        require(ftp.changeWorkingDirectory(p(path)));
        List<RemoteFiles.Entry> result=new ArrayList<>();
        FTPFile[] files=ftp.listFiles(p(path)); require(FTPReply.isPositiveCompletion(ftp.getReplyCode()));
        for (FTPFile f : files) if (!f.getName().equals(".") && !f.getName().equals("..")) result.add(entry(RemoteFiles.child(path,f.getName()),f));
        return result;
    }
    private RemoteFiles.Entry find(String path) throws Exception {
        for (RemoteFiles.Entry e : list(RemoteFiles.parent(path))) if (e.path.equals(path)) return e;
        return null;
    }
    @Override public RemoteFiles.Entry stat(String path) throws Exception {
        if (path.equals("/")) return new RemoteFiles.Entry("/",true,false,0,0);
        RemoteFiles.Entry e=find(path); if (e==null) throw new FileNotFoundException(); return e;
    }
    @Override public InputStream read(String path) throws Exception {
        InputStream in=ftp.retrieveFileStream(p(path)); if (in==null) throw new IOException();
        return new FilterInputStream(in) { @Override public void close() throws IOException { super.close(); require(ftp.completePendingCommand()); } };
    }
    @Override public OutputStream write(String path, boolean replace) throws Exception {
        if (!replace && find(path)!=null) throw new IOException();
        OutputStream out=ftp.storeFileStream(p(path)); if (out==null) throw new IOException();
        return new FilterOutputStream(out) {
            @Override public void write(byte[] b,int off,int len) throws IOException { out.write(b,off,len); }
            @Override public void close() throws IOException { super.close(); require(ftp.completePendingCommand()); }
        };
    }
    @Override public void mkdir(String path) throws Exception { require(ftp.makeDirectory(p(path))); }
    @Override public void rename(String from,String to) throws Exception { if (find(to)!=null) throw new IOException(); require(ftp.rename(p(from),p(to))); }
    @Override public void delete(String path) throws Exception { require(stat(path).directory ? ftp.removeDirectory(p(path)) : ftp.deleteFile(p(path))); }
    @Override public void close() throws IOException { closed=true; if (ftp.isConnected()) ftp.disconnect(); }
}
