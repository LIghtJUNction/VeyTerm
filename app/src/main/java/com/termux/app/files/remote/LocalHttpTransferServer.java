package com.termux.app.files.remote;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicitly started, authenticated HTTP list/download/upload server. No constructor side effects. */
public final class LocalHttpTransferServer implements Closeable {
    public interface Root extends Closeable {
        List<RemoteFiles.Entry> list(String path) throws IOException;
        Download read(String path) throws IOException;
        /** Must stage privately, publish only on commit, and never replace an existing file. */
        Upload upload(String path) throws IOException;
    }
    public static final class Download extends FilterInputStream {
        public final long size;
        public Download(InputStream in,long size) { super(in); this.size=size; }
    }
    public abstract static class Upload extends OutputStream { public abstract void commit() throws IOException; }
    private final Root root;
    private final long maxUploadBytes;
    private final Set<Socket> clients=Collections.synchronizedSet(new HashSet<>());
    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private Thread acceptor;
    private volatile boolean running;
    private String token;
    public LocalHttpTransferServer(Root root,long maxUploadBytes) {
        if (root==null || maxUploadBytes<1) throw new IllegalArgumentException("Invalid transfer root or upload limit");
        this.root=root; this.maxUploadBytes=maxUploadBytes;
    }
    /** false binds 127.0.0.1. true explicitly opts into all IPv4 interfaces, over unencrypted HTTP. */
    public synchronized int start(boolean allowLan,int port) throws IOException {
        if (listener!=null) throw new IOException("Create a new transfer server after stopping");
        if (port<0 || port>65535) throw new IllegalArgumentException("Invalid port");
        byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret); StringBuilder s=new StringBuilder();
        for (byte b:secret) s.append(String.format(Locale.ROOT,"%02x",b&255)); token=s.toString(); Arrays.fill(secret,(byte)0);
        ServerSocket socket=new ServerSocket(); socket.setReuseAddress(true);
        try { socket.bind(new InetSocketAddress(InetAddress.getByName(allowLan ? "0.0.0.0" : "127.0.0.1"),port),8); }
        catch (IOException e) { socket.close(); throw new IOException("Cannot start transfer listener"); }
        listener=socket;
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r -> { Thread t=new Thread(r,"remote-http-request"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
        running=true;
        acceptor=new Thread(() -> {
            while (running) {
                Socket client=null;
                try {
                    client=socket.accept(); client.setSoTimeout(15000); clients.add(client);
                    final Socket accepted=client; workers.execute(() -> serve(accepted));
                } catch (IOException | RejectedExecutionException e) {
                    if (client!=null) { clients.remove(client); try { client.close(); } catch (IOException ignored) { } }
                }
            }
        },"remote-http-listener"); acceptor.setDaemon(true); acceptor.start(); return socket.getLocalPort();
    }
    public synchronized String token() { if (!running) throw new IllegalStateException("Transfer server stopped"); return token; }
    public boolean isRunning() { return running; }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int c;
        while ((c=in.read())!=-1) {
            if (c=='\n') { byte[] data=b.toByteArray(); if (data.length==0 || data[data.length-1]!='\r') throw new IOException(); return new String(data,0,data.length-1,StandardCharsets.US_ASCII); }
            if (b.size()>=8192 || c>127 || (c<32 && c!='\r')) throw new IOException(); b.write(c);
        }
        throw new EOFException();
    }
    private void serve(Socket client) {
        try (Socket socket=client) {
            InputStream input=new BufferedInputStream(socket.getInputStream()); OutputStream output=socket.getOutputStream();
            String[] request=line(input).split(" ",-1); if (request.length!=3 || !request[2].equals("HTTP/1.1")) { reply(output,400,"text/plain",new byte[0]); return; }
            Map<String,String> headers=new HashMap<>(); int headerBytes=0;
            for (int count=0;;count++) {
                String h=line(input); headerBytes+=h.length(); if (headerBytes>32768 || count>64) throw new IOException(); if (h.isEmpty()) break;
                int colon=h.indexOf(':'); if (colon<1) throw new IOException(); String key=h.substring(0,colon).toLowerCase(Locale.ROOT);
                if (headers.put(key,h.substring(colon+1).trim())!=null) throw new IOException();
            }
            String provided=headers.get("authorization");
            if (provided==null || !MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.US_ASCII),provided.getBytes(StandardCharsets.US_ASCII))) { reply(output,401,"text/plain",new byte[0]); return; }
            if (headers.containsKey("transfer-encoding") || headers.containsKey("expect")) { reply(output,400,"text/plain",new byte[0]); return; }
            URI target=new URI(request[1]);
            if (target.isAbsolute() || target.getRawAuthority()!=null || target.getRawFragment()!=null) throw new IOException();
            String query=target.getRawQuery();
            if (query==null || !query.startsWith("path=") || query.contains("&")) throw new IOException();
            String path=RemoteFiles.path(URLDecoder.decode(query.substring(5),"UTF-8"));
            boolean started=false;
            try {
                if (request[0].equals("GET") && target.getRawPath().equals("/list")) {
                    StringBuilder json=new StringBuilder("["); boolean first=true;
                    for (RemoteFiles.Entry e:root.list(path)) {
                        if (!first) json.append(','); first=false;
                        json.append("{\"name\":").append(quote(e.name)).append(",\"directory\":").append(e.directory).append(",\"size\":").append(e.size).append(",\"modified\":").append(e.modified).append('}');
                        if (json.length()>2*1024*1024) throw new IOException();
                    }
                    reply(output,200,"application/json; charset=utf-8",json.append(']').toString().getBytes(StandardCharsets.UTF_8));
                } else if (request[0].equals("GET") && target.getRawPath().equals("/download")) {
                    try (Download file=root.read(path)) {
                        started=true;
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: "+file.size+"\r\nContent-Type: application/octet-stream\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        byte[] b=new byte[64*1024]; long left=file.size;
                        while (left>0) { if (!running) throw new IOException(); int n=file.read(b,0,(int)Math.min(left,b.length)); if (n<0) throw new EOFException(); output.write(b,0,n); left-=n; }
                    }
                } else if (request[0].equals("PUT") && target.getRawPath().equals("/upload")) {
                    long length=Long.parseLong(headers.getOrDefault("content-length","-1"));
                    if (length<0 || length>maxUploadBytes) { reply(output,413,"text/plain",new byte[0]); return; }
                    try (Upload upload=root.upload(RemoteFiles.mutable(path))) {
                        byte[] b=new byte[64*1024]; long left=length;
                        while (left>0) { if (!running) throw new IOException(); int n=input.read(b,0,(int)Math.min(left,b.length)); if (n<0) throw new EOFException(); upload.write(b,0,n); left-=n; }
                        if (!running) throw new IOException(); upload.commit();
                    }
                    reply(output,201,"text/plain",new byte[0]);
                } else reply(output,405,"text/plain",new byte[0]);
            } catch (IOException e) { if (!started) reply(output,403,"text/plain","Transfer denied or failed".getBytes(StandardCharsets.US_ASCII)); }
        } catch (Exception ignored) { /* Never log request targets, tokens or filesystem exceptions. */ }
        finally { clients.remove(client); }
    }
    private static String quote(String s) {
        StringBuilder out=new StringBuilder("\""); for (int i=0;i<s.length();i++) {
            char c=s.charAt(i); if (c=='"' || c=='\\') out.append('\\').append(c);
            else if (c<32) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else out.append(c);
        } return out.append('"').toString();
    }
    private static void reply(OutputStream out,int code,String type,byte[] body) throws IOException {
        out.write(("HTTP/1.1 "+code+" Response\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(body);
    }
    @Override public synchronized void close() throws IOException {
        running=false;
        if (listener!=null) listener.close();
        Socket[] snapshot; synchronized (clients) { snapshot=clients.toArray(new Socket[0]); }
        for (Socket c:snapshot) try { c.close(); } catch (IOException ignored) { }
        if (workers!=null) { workers.shutdownNow(); try { workers.awaitTermination(2,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        root.close(); token=null;
    }
}
