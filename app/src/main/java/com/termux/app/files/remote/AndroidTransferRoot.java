package com.termux.app.files.remote;

import android.os.ParcelFileDescriptor;
import android.system.*;
import java.io.*;
import java.util.*;

/** API 21+ descriptor-relative root. Every path segment is opened O_NOFOLLOW; no canonical-path TOCTOU check. */
public final class AndroidTransferRoot implements LocalHttpTransferServer.Root {
    private final ParcelFileDescriptor root;
    private boolean closed;
    public AndroidTransferRoot(File directory) throws IOException {
        try { root=openDirectory(directory.getAbsolutePath()); }
        catch (Exception e) { throw new IOException("Cannot open transfer directory"); }
    }
    private static ParcelFileDescriptor openDirectory(String path) throws Exception {
        ParcelFileDescriptor fd=owned(Os.open(path,OsConstants.O_RDONLY|OsConstants.O_NONBLOCK|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0));
        try { if (!OsConstants.S_ISDIR(Os.fstat(fd.getFileDescriptor()).st_mode)) throw new IOException(); return fd; }
        catch (Exception e) { fd.close(); throw e; }
    }
    private static ParcelFileDescriptor owned(FileDescriptor fd) throws IOException {
        try { return ParcelFileDescriptor.dup(fd); } finally { try { Os.close(fd); } catch (ErrnoException ignored) { } }
    }
    private static String at(ParcelFileDescriptor parent,String name) { return "/proc/self/fd/"+parent.getFd()+"/"+name; }
    private synchronized ParcelFileDescriptor directory(String path) throws IOException {
        if (closed) throw new IOException("Transfer root closed");
        ParcelFileDescriptor current=ParcelFileDescriptor.dup(root.getFileDescriptor());
        try {
            for (String segment:RemoteFiles.path(path).substring(1).split("/")) {
                if (segment.isEmpty()) continue;
                ParcelFileDescriptor next=openDirectory(at(current,segment));
                current.close(); current=next;
            }
            return current;
        } catch (Exception e) { current.close(); throw new IOException("Transfer path denied"); }
    }
    @Override public List<RemoteFiles.Entry> list(String path) throws IOException {
        List<RemoteFiles.Entry> result=new ArrayList<>();
        try (ParcelFileDescriptor dir=directory(path)) {
            String[] names=new File("/proc/self/fd/"+dir.getFd()).list(); if (names==null) throw new IOException();
            for (String name:names) {
                if (name.startsWith(".veyterm-upload-")) continue;
                StructStat st=Os.lstat(at(dir,name));
                // Do not expose links, devices, FIFOs or sockets through HTTP.
                if (!OsConstants.S_ISREG(st.st_mode) && !OsConstants.S_ISDIR(st.st_mode)) continue;
                result.add(new RemoteFiles.Entry(RemoteFiles.child(path,name),OsConstants.S_ISDIR(st.st_mode),false,st.st_size,st.st_mtime*1000));
            }
            return result;
        } catch (Exception e) { throw new IOException("Transfer listing denied"); }
    }
    @Override public LocalHttpTransferServer.Download read(String path) throws IOException {
        path=RemoteFiles.mutable(path);
        try (ParcelFileDescriptor parent=directory(RemoteFiles.parent(path))) {
            ParcelFileDescriptor fd=owned(Os.open(at(parent,RemoteFiles.name(path)),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK|OsConstants.O_CLOEXEC,0));
            try { StructStat st=Os.fstat(fd.getFileDescriptor()); if (!OsConstants.S_ISREG(st.st_mode)) throw new IOException(); return new LocalHttpTransferServer.Download(new ParcelFileDescriptor.AutoCloseInputStream(fd),st.st_size); }
            catch (Exception e) { fd.close(); throw e; }
        } catch (Exception e) { throw new IOException("Transfer download denied"); }
    }
    @Override public LocalHttpTransferServer.Upload upload(String path) throws IOException {
        path=RemoteFiles.mutable(path); String name=RemoteFiles.name(path);
        if (name.startsWith(".veyterm-upload-")) throw new IOException("Reserved transfer name");
        ParcelFileDescriptor parent=directory(RemoteFiles.parent(path));
        String temp=at(parent,".veyterm-upload-"+UUID.randomUUID()), destination=at(parent,name);
        try {
            ParcelFileDescriptor fd=owned(Os.open(temp,OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0600));
            FileOutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(fd);
            return new LocalHttpTransferServer.Upload() {
                boolean committed,done;
                @Override public void write(int b) throws IOException { output.write(b); }
                @Override public void write(byte[] b,int o,int n) throws IOException { output.write(b,o,n); }
                @Override public void commit() throws IOException {
                    if (done || committed) throw new IOException("Upload already closed");
                    try {
                        output.getFD().sync();
                        // Atomic no-clobber publication, also refuses a destination symlink.
                        Os.link(temp,destination); committed=true;
                    } catch (Exception e) { throw new IOException("Upload publish denied; destination may exist"); }
                }
                @Override public void close() throws IOException {
                    if (done) return; done=true;
                    try { output.close(); } finally { try { Os.remove(temp); } catch (ErrnoException ignored) { } parent.close(); }
                }
            };
        } catch (Exception e) { parent.close(); throw new IOException("Transfer upload denied"); }
    }
    @Override public synchronized void close() throws IOException { closed=true; root.close(); }
}
