package com.termux.app.files.remote;

import java.io.*;
import java.net.URI;
import java.util.*;

/** Blocking remote I/O. One connection belongs to one worker; cancel/close may run on any thread. */
public final class RemoteFiles implements Closeable {
    public enum Protocol { SFTP, FTP, FTPS, WEBDAV, SMB }
    public interface Progress { void transferred(long bytes, long total); }
    public static final class Cancellation {
        private boolean cancelled;
        private final Set<Closeable> resources = new HashSet<>();
        public void cancel() {
            List<Closeable> copy;
            synchronized (this) { cancelled = true; copy = new ArrayList<>(resources); resources.clear(); }
            for (Closeable c : copy) try { c.close(); } catch (Exception ignored) { }
        }
        synchronized void attach(Closeable c) throws IOException {
            if (cancelled) { c.close(); check(); }
            resources.add(c);
        }
        synchronized void detach(Closeable c) { resources.remove(c); }
        public synchronized void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Remote operation cancelled");
        }
    }
    /** Credentials never form part of the address. The entire saved record is encrypted by ConnectionStore. */
    public static final class Connection {
        public final String id, label, host, root, hostKeySha256;
        public final Protocol protocol;
        public final int port, timeoutMillis;
        public final boolean allowInsecure;
        final String username, password, domain;
        public Connection(String id, String label, Protocol protocol, String host, int port,
                          String root, String username, String password, String domain,
                          String hostKeySha256, int timeoutMillis, boolean allowInsecure) {
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}") || protocol == null)
                throw new IllegalArgumentException("Invalid connection identifier");
            if (host == null || !host.matches("[a-zA-Z0-9._:-]+") || host.contains("@"))
                throw new IllegalArgumentException("Enter a hostname, not a URI");
            if (port < 1 || port > 65535 || timeoutMillis < 1000 || timeoutMillis > 120000)
                throw new IllegalArgumentException("Invalid port or timeout");
            this.id = id; this.label = label == null ? protocol.name() : label;
            this.protocol = protocol; this.host = host; this.port = port;
            this.root = path(root); this.username = clean(username); this.password = clean(password);
            this.domain = clean(domain); this.hostKeySha256 = clean(hostKeySha256);
            this.timeoutMillis = timeoutMillis; this.allowInsecure = allowInsecure;
            if ((protocol == Protocol.FTP || (protocol == Protocol.WEBDAV && port == 80)) && !allowInsecure)
                throw new IllegalArgumentException("Cleartext transport requires explicit consent");
            if (protocol == Protocol.SFTP && !this.hostKeySha256.matches("SHA256:[A-Za-z0-9+/]{43}=?"))
                throw new IllegalArgumentException("SFTP requires the server SHA256 host-key fingerprint");
            if (protocol == Protocol.SMB && this.root.equals("/"))
                throw new IllegalArgumentException("SMB root must include a share");
        }
        private static String clean(String s) {
            if (s == null) return "";
            if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid connection field");
            return s;
        }
        public String location(String path) {
            try { return new URI("remote", id, RemoteFiles.path(path), null).toASCIIString(); }
            catch (Exception e) { throw new IllegalArgumentException("Invalid remote path"); }
        }
        @Override public String toString() { return "RemoteConnection[" + id + "]"; }
    }
    public static final class Entry {
        public final String path, name;
        public final boolean directory, symbolicLink;
        public final long size, modified;
        public Entry(String path, boolean directory, boolean symbolicLink, long size, long modified) {
            this.path = RemoteFiles.path(path); this.name = name(this.path);
            this.directory = directory; this.symbolicLink = symbolicLink;
            this.size = size; this.modified = modified;
        }
    }
    interface Backend extends Closeable {
        void connect() throws Exception;
        List<Entry> list(String path) throws Exception;
        Entry stat(String path) throws Exception;
        InputStream read(String path) throws Exception;
        OutputStream write(String path, boolean replace) throws Exception;
        void mkdir(String path) throws Exception;
        void rename(String from, String to) throws Exception;
        void delete(String path) throws Exception;
    }
    private final Backend backend;
    private final Cancellation cancellation;
    private volatile boolean closed;
    private boolean streamOpen;
    private final Set<Closeable> streams = Collections.synchronizedSet(new HashSet<>());
    private RemoteFiles(Backend backend, Cancellation cancellation) { this.backend = backend; this.cancellation = cancellation; }
    public static RemoteFiles connect(Connection config, Cancellation cancellation) throws IOException {
        if (cancellation == null) cancellation = new Cancellation();
        Backend b;
        switch (config.protocol) {
            case SFTP: b = new SftpBackend(config); break;
            case FTP: case FTPS: b = new FtpBackend(config); break;
            case SMB: b = new SmbBackend(config); break;
            default: b = new WebDavBackend(config);
        }
        RemoteFiles files = new RemoteFiles(b, cancellation);
        cancellation.attach(files);
        try { cancellation.check(); b.connect(); cancellation.check(); return files; }
        catch (Exception e) { files.close(); throw files.failure("connect"); }
    }
    private interface Op<T> { T run() throws Exception; }
    private <T> T invoke(String operation, Op<T> action) throws IOException {
        cancellation.check();
        if (closed) throw new IOException("Remote connection closed");
        if (streamOpen) throw new IOException("Close the current remote stream first");
        try { T result = action.run(); cancellation.check(); return result; }
        catch (Exception e) { throw failure(operation); }
    }
    private IOException failure(String operation) {
        try { cancellation.check(); } catch (IOException e) { return e; }
        // Never attach third-party causes: URLs, server replies and credentials may be in their messages.
        return new IOException("Remote " + operation + " failed; check access, server availability and trust settings");
    }
    public List<Entry> list(String path) throws IOException { String p = path(path); return invoke("list", () -> backend.list(p)); }
    public Entry stat(String path) throws IOException { String p = path(path); return invoke("stat", () -> backend.stat(p)); }
    public void mkdir(String path) throws IOException { String p = mutable(path); invoke("mkdir", () -> { backend.mkdir(p); return null; }); }
    public void rename(String from, String to) throws IOException {
        String a = mutable(from), b = mutable(to);
        invoke("rename", () -> { backend.rename(a, b); return null; });
    }
    /** Nonrecursive. The parent operation engine owns traversal, conflicts and symlink policy. */
    public void delete(String path) throws IOException { String p = mutable(path); invoke("delete", () -> { backend.delete(p); return null; }); }
    public InputStream read(String path) throws IOException { return read(path, null); }
    public InputStream read(String path, Progress progress) throws IOException {
        String p = path(path);
        InputStream raw = invoke("read", () -> backend.read(p));
        streamOpen = true;
        InputStream stream = new FilterInputStream(raw) {
            long count; boolean done;
            @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one,0,1) < 0 ? -1 : one[0] & 255; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                try { cancellation.check(); int n = in.read(b,off,len); cancellation.check();
                    if (n > 0 && progress != null) progress.transferred(count += n, -1); return n;
                } catch (Exception e) { throw failure("read"); }
            }
            @Override public long skip(long n) throws IOException {
                byte[] buffer=new byte[(int)Math.min(Math.max(n,0),8192)]; long count=0;
                while (count<n) { int got=read(buffer,0,(int)Math.min(n-count,buffer.length)); if (got<0) break; count+=got; } return count;
            }
            @Override public int available() throws IOException { try { cancellation.check(); return in.available(); } catch (Exception e) { throw failure("read"); } }
            @Override public synchronized void reset() throws IOException { throw new IOException("Remote streams do not support reset"); }
            @Override public boolean markSupported() { return false; }
            @Override public synchronized void mark(int limit) { }
            @Override public void close() throws IOException {
                if (done) return; done = true;
                try { in.close(); } catch (Exception e) { throw failure("read completion"); }
                finally { streams.remove(this); streamOpen = false; }
            }
        };
        streams.add(stream); if (closed) { stream.close(); throw failure("read"); } return stream;
    }
    public OutputStream write(String path, boolean replace) throws IOException { return write(path, replace, null); }
    public OutputStream write(String path, boolean replace, Progress progress) throws IOException {
        String p = mutable(path);
        OutputStream raw = invoke("write", () -> backend.write(p, replace));
        streamOpen = true;
        OutputStream stream = new FilterOutputStream(raw) {
            long count; boolean done;
            @Override public void write(int n) throws IOException { write(new byte[]{(byte)n},0,1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                try { cancellation.check(); out.write(b,off,len); cancellation.check();
                    if (progress != null) progress.transferred(count += len, -1);
                } catch (Exception e) { throw failure("write"); }
            }
            @Override public void flush() throws IOException { try { cancellation.check(); out.flush(); } catch (Exception e) { throw failure("flush"); } }
            @Override public void close() throws IOException {
                if (done) return; done = true;
                try { out.close(); cancellation.check(); } catch (Exception e) { throw failure("write completion"); }
                finally { streams.remove(this); streamOpen = false; }
            }
        };
        streams.add(stream); if (closed) { stream.close(); throw failure("write"); } return stream;
    }
    /** Caller owns local streams. A successful return includes protocol completion (FTP 226 / HTTP 2xx). */
    public long download(String path, OutputStream target, Progress progress) throws IOException {
        try (InputStream in = read(path)) { return copy(in,target,progress); }
    }
    public long upload(String path, InputStream source, boolean replace, Progress progress) throws IOException {
        try (OutputStream out = write(path,replace)) { return copy(source,out,progress); }
    }
    private long copy(InputStream in, OutputStream out, Progress progress) throws IOException {
        byte[] buffer = new byte[64*1024]; long count = 0; int n;
        while ((n = in.read(buffer)) != -1) { cancellation.check(); out.write(buffer,0,n); count += n; if (progress != null) progress.transferred(count,-1); }
        cancellation.check(); return count;
    }
    @Override public void close() {
        closed = true; cancellation.detach(this);
        try { backend.close(); } catch (Exception ignored) { }
        Closeable[] copy; synchronized (streams) { copy = streams.toArray(new Closeable[0]); }
        for (Closeable stream : copy) try { stream.close(); } catch (Exception ignored) { }
    }
    static String path(String value) {
        if (value == null || !value.startsWith("/") || value.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Remote paths must be absolute");
        StringBuilder p = new StringBuilder();
        for (String part : value.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed");
            for (int i=0;i<part.length();i++) if (Character.isISOControl(part.charAt(i))) throw new IllegalArgumentException("Invalid path character");
            p.append('/').append(part);
        }
        return p.length() == 0 ? "/" : p.toString();
    }
    static String mutable(String p) { p = path(p); if (p.equals("/")) throw new IllegalArgumentException("Cannot modify connection root"); return p; }
    public static String parent(String p) { p=path(p); int i=p.lastIndexOf('/'); return i<=0 ? "/" : p.substring(0,i); }
    public static String child(String p, String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) throw new IllegalArgumentException("Invalid file name");
        return path((path(p).equals("/") ? "" : path(p)) + "/" + name);
    }
    static String name(String p) { return p.equals("/") ? "/" : p.substring(p.lastIndexOf('/')+1); }
    static String actual(Connection c, String p) { return c.root.equals("/") ? p : c.root + (p.equals("/") ? "/" : p); }
}
