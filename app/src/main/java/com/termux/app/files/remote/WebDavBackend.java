package com.termux.app.files.remote;

import okhttp3.*;
import okio.BufferedSink;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

final class WebDavBackend implements RemoteFiles.Backend {
    private final RemoteFiles.Connection config;
    private final OkHttpClient http;
    private volatile Call active;
    private volatile boolean closed;
    private volatile PipedInputStream uploadInput;
    private volatile PipedOutputStream uploadOutput;
    private final ExecutorService uploader=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"remote-dav-upload"); t.setDaemon(true); return t; });
    WebDavBackend(RemoteFiles.Connection c) {
        config=c;
        http=new OkHttpClient.Builder().connectTimeout(c.timeoutMillis,TimeUnit.MILLISECONDS)
            .readTimeout(c.timeoutMillis,TimeUnit.MILLISECONDS).writeTimeout(c.timeoutMillis,TimeUnit.MILLISECONDS)
            .callTimeout(c.timeoutMillis,TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }
    private HttpUrl url(String path) {
        return new HttpUrl.Builder().scheme(config.allowInsecure ? "http" : "https").host(config.host).port(config.port)
            .encodedPath("/").addPathSegments(RemoteFiles.actual(config,path).substring(1)).build();
    }
    private Request.Builder request(String path) { return new Request.Builder().url(url(path)).header("Authorization",Credentials.basic(config.username,config.password,java.nio.charset.StandardCharsets.UTF_8)); }
    private Response execute(Request req) throws IOException {
        Call call=http.newCall(req); active=call;
        if (closed) { call.cancel(); throw new IOException(); }
        Response response=call.execute();
        if (closed) { response.close(); throw new IOException(); }
        return response;
    }
    private void success(Response r) throws IOException { if (!r.isSuccessful()) throw new IOException(); }
    @Override public void connect() throws Exception { if (!stat("/").directory) throw new IOException(); }
    private List<RemoteFiles.Entry> properties(String path,boolean children) throws Exception {
        String xml="<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>";
        Request req=request(path).header("Depth",children ? "1" : "0").method("PROPFIND",RequestBody.create(MediaType.get("application/xml; charset=utf-8"),xml)).build();
        Response initial=execute(req);
        // Some DAV servers canonicalize collection URLs. Accept only this exact same-origin slash change.
        if (initial.code()==301 || initial.code()==302 || initial.code()==307 || initial.code()==308) {
            HttpUrl canonical=initial.request().url().resolve(initial.header("Location",""));
            HttpUrl expected=url(path).newBuilder().addPathSegment("").build();
            initial.close();
            if (canonical==null || !canonical.equals(expected)) throw new IOException();
            initial=execute(req.newBuilder().url(expected).build());
        }
        try (Response response=initial) {
            if (response.code()!=207 || response.body()==null) throw new IOException();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] block=new byte[8192]; int n;
            try (InputStream in=response.body().byteStream()) { while ((n=in.read(block))!=-1) { if (bytes.size()+n>2*1024*1024) throw new IOException(); bytes.write(block,0,n); } }
            byte[] data=bytes.toByteArray(); String declarationScan=new String(data,java.nio.charset.StandardCharsets.ISO_8859_1);
            // Reject DTDs before parsing, including encodings that could hide their markup from this check.
            String encoding=response.header("Content-Type","");
            if (encoding.toLowerCase(Locale.ROOT).contains("utf-16") || declarationScan.indexOf('\0')>=0 || declarationScan.contains("<!DOCTYPE") || declarationScan.contains("<!ENTITY")) throw new IOException();
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setExpandEntityReferences(false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            javax.xml.parsers.DocumentBuilder parser=factory.newDocumentBuilder();
            parser.setEntityResolver((publicId,systemId)-> { throw new org.xml.sax.SAXException("External entities disabled"); });
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() { @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; } });
            Document doc=parser.parse(new ByteArrayInputStream(data));
            NodeList responses=doc.getElementsByTagNameNS("DAV:","response"); List<RemoteFiles.Entry> result=new ArrayList<>();
            if (responses.getLength()>20000) throw new IOException();
            for (int i=0;i<responses.getLength();i++) {
                Element item=(Element)responses.item(i); HttpUrl href=response.request().url().resolve(text(item,"href"));
                if (href==null || !href.host().equalsIgnoreCase(config.host) || href.port()!=config.port || !href.scheme().equals(url(path).scheme()) || !href.username().isEmpty() || !href.password().isEmpty()) throw new IOException();
                String full=RemoteFiles.path(new java.net.URI(href.toString()).getPath());
                String virtual;
                if (config.root.equals("/")) virtual=full;
                else if (full.equals(config.root)) virtual="/";
                else if (full.startsWith(config.root+"/")) virtual=full.substring(config.root.length());
                else throw new IOException();
                if (!virtual.equals(path) && (!children || !RemoteFiles.parent(virtual).equals(path))) continue;
                Element prop=null; NodeList sets=item.getElementsByTagNameNS("DAV:","propstat");
                for (int j=0;j<sets.getLength();j++) { Element set=(Element)sets.item(j); if (text(set,"status").matches("HTTP/[^ ]+ 2[0-9][0-9].*")) { prop=(Element)set.getElementsByTagNameNS("DAV:","prop").item(0); if (prop!=null) break; } }
                if (prop==null) continue;
                boolean dir=prop.getElementsByTagNameNS("DAV:","collection").getLength()>0;
                long size=0,modified=0;
                try { size=Long.parseLong(text(prop,"getcontentlength")); } catch (NumberFormatException ignored) { }
                try { modified=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US).parse(text(prop,"getlastmodified")).getTime(); } catch (Exception ignored) { }
                result.add(new RemoteFiles.Entry(virtual,dir,false,size,modified));
            }
            return result;
        }
    }
    private static String text(Element e,String local) { NodeList n=e.getElementsByTagNameNS("DAV:",local); return n.getLength()==0 ? "" : n.item(0).getTextContent().trim(); }
    @Override public List<RemoteFiles.Entry> list(String path) throws Exception {
        List<RemoteFiles.Entry> entries=properties(path,true); boolean found=false;
        Iterator<RemoteFiles.Entry> it=entries.iterator(); while (it.hasNext()) { RemoteFiles.Entry e=it.next(); if (e.path.equals(path)) { if (!e.directory) throw new IOException(); found=true; it.remove(); } }
        if (!found) throw new IOException(); return entries;
    }
    @Override public RemoteFiles.Entry stat(String path) throws Exception { for (RemoteFiles.Entry e:properties(path,false)) if (e.path.equals(path)) return e; throw new FileNotFoundException(); }
    @Override public InputStream read(String path) throws Exception {
        Response r=execute(request(path).get().build());
        try { success(r); if (r.body()==null) throw new IOException();
            return new FilterInputStream(r.body().byteStream()) { @Override public void close() { r.close(); } };
        } catch (Exception e) { r.close(); throw e; }
    }
    @Override public OutputStream write(String path,boolean replace) throws Exception {
        PipedInputStream in=new PipedInputStream(64*1024); PipedOutputStream out=new PipedOutputStream(in); uploadInput=in; uploadOutput=out;
        RequestBody body=new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get("application/octet-stream"); }
            @Override public void writeTo(BufferedSink sink) throws IOException { try (InputStream src=in) { byte[] b=new byte[64*1024]; int n; while ((n=src.read(b))!=-1) sink.write(b,0,n); } }
        };
        Request.Builder builder=request(path).put(body); if (!replace) builder.header("If-None-Match","*");
        Future<Void> pending=uploader.submit(() -> { try (Response response=execute(builder.build())) { success(response); return null; } finally { in.close(); } });
        return new FilterOutputStream(out) {
            @Override public void write(byte[] b,int off,int len) throws IOException { out.write(b,off,len); }
            @Override public void close() throws IOException {
                try { out.close(); pending.get(config.timeoutMillis,TimeUnit.MILLISECONDS); }
                catch (Exception e) { pending.cancel(true); if (active!=null) active.cancel(); throw new IOException(); }
                finally { in.close(); uploadInput=null; uploadOutput=null; }
            }
        };
    }
    @Override public void mkdir(String path) throws Exception { try (Response r=execute(request(path).method("MKCOL",RequestBody.create(null,new byte[0])).build())) { success(r); } }
    @Override public void rename(String from,String to) throws Exception { try (Response r=execute(request(from).header("Destination",url(to).toString()).header("Overwrite","F").method("MOVE",null).build())) { success(r); } }
    @Override public void delete(String path) throws Exception {
        if (stat(path).directory && !list(path).isEmpty()) throw new IOException();
        try (Response r=execute(request(path).delete().build())) { success(r); }
    }
    @Override public void close() throws IOException {
        closed=true; if (active!=null) active.cancel();
        if (uploadInput!=null) uploadInput.close(); if (uploadOutput!=null) uploadOutput.close();
        uploader.shutdownNow(); http.dispatcher().cancelAll(); http.connectionPool().evictAll(); http.dispatcher().executorService().shutdownNow();
    }
}
