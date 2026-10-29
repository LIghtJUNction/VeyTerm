package com.termux.app.files.remote;

import com.jcraft.jsch.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

final class SftpBackend implements RemoteFiles.Backend {
    private final RemoteFiles.Connection config;
    private volatile Session session;
    private volatile ChannelSftp channel;
    private volatile boolean closed;
    SftpBackend(RemoteFiles.Connection config) { this.config = config; }
    @Override public void connect() throws Exception {
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(new HostKeyRepository() {
            public int check(String host, byte[] key) {
                try {
                    String hash = "SHA256:" + base64(MessageDigest.getInstance("SHA-256").digest(key));
                    return MessageDigest.isEqual(hash.getBytes("US-ASCII"), config.hostKeySha256.replace("=", "").getBytes("US-ASCII")) ? OK : CHANGED;
                } catch (Exception e) { return NOT_INCLUDED; }
            }
            public void add(HostKey key, UserInfo info) { }
            public void remove(String host, String type) { }
            public void remove(String host, String type, byte[] key) { }
            public String getKnownHostsRepositoryID() { return "VeyTerm pinned host key"; }
            public HostKey[] getHostKey() { return new HostKey[0]; }
            public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
        });
        Session s = jsch.getSession(config.username, config.host, config.port); session = s;
        s.setPassword(config.password);
        s.setConfig("StrictHostKeyChecking", "yes");
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        s.setTimeout(config.timeoutMillis); s.setServerAliveInterval(config.timeoutMillis);
        if (closed) throw new IOException();
        s.connect(config.timeoutMillis);
        ChannelSftp ch = (ChannelSftp)s.openChannel("sftp"); channel = ch;
        if (closed) throw new IOException(); ch.connect(config.timeoutMillis);
        if (!ch.stat(p("/")).isDir()) throw new IOException();
    }
    // JSch treats SFTP strings as glob patterns, even when they are exact file names.
    private String p(String path) { return RemoteFiles.actual(config,path).replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\["); }
    private RemoteFiles.Entry entry(String p, SftpATTRS a) { return new RemoteFiles.Entry(p,a.isDir(),a.isLink(),a.getSize(),((long)a.getMTime())*1000); }
    @Override public List<RemoteFiles.Entry> list(String path) throws Exception {
        if (!channel.stat(p(path)).isDir()) throw new IOException();
        List<RemoteFiles.Entry> result = new ArrayList<>();
        for (Object item : channel.ls(p(path))) {
            ChannelSftp.LsEntry e = (ChannelSftp.LsEntry)item;
            if (!e.getFilename().equals(".") && !e.getFilename().equals("..")) result.add(entry(RemoteFiles.child(path,e.getFilename()),e.getAttrs()));
        }
        return result;
    }
    @Override public RemoteFiles.Entry stat(String path) throws Exception { return entry(path,channel.lstat(p(path))); }
    @Override public InputStream read(String path) throws Exception { return channel.get(p(path)); }
    @Override public OutputStream write(String path, boolean replace) throws Exception {
        if (!replace) {
            try { channel.lstat(p(path)); throw new IOException(); }
            catch (SftpException e) { if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e; }
        }
        return channel.put(p(path),ChannelSftp.OVERWRITE);
    }
    @Override public void mkdir(String path) throws Exception { channel.mkdir(p(path)); }
    @Override public void rename(String from, String to) throws Exception {
        try { channel.lstat(p(to)); throw new IOException(); }
        catch (SftpException e) { if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e; }
        channel.rename(p(from),p(to));
    }
    @Override public void delete(String path) throws Exception { if (stat(path).directory) channel.rmdir(p(path)); else channel.rm(p(path)); }
    private static String base64(byte[] data) {
        String chars="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder out=new StringBuilder(); int bits=0,value=0;
        for (byte b:data) { value=(value<<8)|(b&255); bits+=8; while (bits>=6) { bits-=6; out.append(chars.charAt((value>>bits)&63)); } }
        if (bits>0) out.append(chars.charAt((value<<(6-bits))&63)); return out.toString();
    }
    @Override public void close() { closed=true; if (channel != null) channel.disconnect(); if (session != null) session.disconnect(); }
}
