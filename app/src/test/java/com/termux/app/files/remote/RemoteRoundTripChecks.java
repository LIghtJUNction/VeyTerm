package com.termux.app.files.remote;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Plain JVM integration harness, no Gradle/bootstrap/device. Assertions use real loopback sockets. */
public final class RemoteRoundTripChecks {
    private static int checks;
    private static void check(boolean ok,String description) { checks++; if (!ok) throw new AssertionError(description); }
    private interface Task { void run() throws Exception; }
    private static void denied(Task action,String description) throws Exception {
        try { action.run(); throw new AssertionError(description); }
        catch (IOException | IllegalArgumentException expected) { checks++; check(!expected.toString().contains("local-fixture-only"),"redacted error"); check(expected.getCause()==null,"no credential-bearing cause"); }
    }
    private static RemoteFiles.Connection connection(RemoteFiles.Protocol protocol,int port,String hostKey) {
        return new RemoteFiles.Connection("fixture-id", "fixture", protocol,"127.0.0.1",port,protocol==RemoteFiles.Protocol.SMB ? "/fixture" : "/",protocol==RemoteFiles.Protocol.SMB ? System.getProperty("user.name") : "network-fixture","local-fixture-only","",hostKey,5000,protocol==RemoteFiles.Protocol.FTP || protocol==RemoteFiles.Protocol.WEBDAV);
    }
    private static void roundtrip(RemoteFiles.Connection config) throws Exception {
        RemoteFiles.Cancellation token=new RemoteFiles.Cancellation();
        byte[] bytes=new byte[160003]; new Random(42).nextBytes(bytes); long[] progress={0};
        String dir="/"+config.protocol.name().toLowerCase(Locale.ROOT);
        try (RemoteFiles remote=RemoteFiles.connect(config,token)) {
            check(remote.stat("/").directory,"root directory"); remote.mkdir(dir);
            check(remote.list("/").stream().anyMatch(e->e.path.equals(dir)),"directory listed");
            String original=dir+"/你好 space #%.bin", renamed=dir+"/renamed.bin";
            check(remote.upload(original,new ByteArrayInputStream(bytes),false,(n,total)->progress[0]=n)==bytes.length,"upload byte count");
            check(progress[0]==bytes.length,"upload progress");
            check(remote.stat(original).size==bytes.length,"stat size");
            denied(()->remote.list(original),"listing a regular file fails");
            RemoteFiles.Connection nested=new RemoteFiles.Connection("nested","nested",config.protocol,config.host,config.port,
                config.protocol==RemoteFiles.Protocol.SMB ? "/fixture"+dir : dir,config.username,config.password,config.domain,config.hostKeySha256,config.timeoutMillis,config.allowInsecure);
            try (RemoteFiles inside=RemoteFiles.connect(nested,new RemoteFiles.Cancellation())) {
                check(inside.list("/").stream().anyMatch(e->e.path.equals("/你好 space #%.bin")),"connection-root-relative listing");
                check(inside.stat("/你好 space #%.bin").size==bytes.length,"connection-root-relative stat");
            }
            denied(()->{ try (OutputStream out=remote.write(original,false)) { out.write(0); } },"no clobber");
            denied(()->remote.delete(dir),"nonempty directory preserved");
            ByteArrayOutputStream downloaded=new ByteArrayOutputStream();
            check(remote.download(original,downloaded,(n,total)->progress[0]=n)==bytes.length,"download byte count");
            check(Arrays.equals(bytes,downloaded.toByteArray()),"binary data identical");
            remote.rename(original,renamed); check(remote.stat(renamed).size==bytes.length,"rename");
            try (OutputStream out=remote.write(renamed,true)) { out.write(new byte[]{1,2,3}); }
            check(remote.stat(renamed).size==3,"replace truncates");
            denied(()->remote.stat(dir+"/missing"),"missing stat fails");
            remote.delete(renamed); check(remote.list(dir).isEmpty(),"file delete");
            remote.delete(dir); check(remote.list("/").stream().noneMatch(e->e.path.equals(dir)),"directory delete");
            if (config.protocol==RemoteFiles.Protocol.SFTP) {
                String glob="/literal*[?].txt"; remote.upload(glob,new ByteArrayInputStream(bytes),false,null);
                check(remote.stat(glob).size==bytes.length,"literal wildcard filename"); remote.delete(glob);
            }
            token.cancel(); denied(()->remote.list("/"),"cancel prevents further I/O");
        }
        check(!config.location("/a b").contains("network-fixture"),"opaque URI excludes user");
        System.out.println("PASS "+config.protocol+" list/stat/read/write/mkdir/rename/delete/progress/cancellation");
    }
    private static HttpServer dav(Path root) throws IOException {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            try {
                String expected="Basic "+Base64.getEncoder().encodeToString("network-fixture:local-fixture-only".getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401,-1); return; }
                Path file=root.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
                if (!file.startsWith(root)) { exchange.sendResponseHeaders(403,-1); return; }
                String method=exchange.getRequestMethod();
                if (method.equals("PROPFIND")) {
                    if (!Files.exists(file)) { exchange.sendResponseHeaders(404,-1); return; }
                    List<Path> paths=new ArrayList<>(); paths.add(file);
                    if ("1".equals(exchange.getRequestHeaders().getFirst("Depth")) && Files.isDirectory(file)) try (java.util.stream.Stream<Path> children=Files.list(file)) { children.forEach(paths::add); }
                    StringBuilder xml=new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
                    for (Path p:paths) {
                        String href=new URI(null,null,"/"+root.relativize(p).toString(),null).toASCIIString().replace("&","&amp;");
                        xml.append("<d:response><d:href>").append(href).append("</d:href><d:propstat><d:prop><d:resourcetype>").append(Files.isDirectory(p) ? "<d:collection/>" : "").append("</d:resourcetype><d:getcontentlength>").append(Files.size(p)).append("</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>");
                    }
                    byte[] b=xml.append("</d:multistatus>").toString().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(207,b.length); exchange.getResponseBody().write(b);
                } else if (method.equals("MKCOL")) { Files.createDirectory(file); exchange.sendResponseHeaders(201,-1); }
                else if (method.equals("PUT")) {
                    if ("*".equals(exchange.getRequestHeaders().getFirst("If-None-Match")) && Files.exists(file)) { exchange.sendResponseHeaders(412,-1); return; }
                    try (OutputStream out=Files.newOutputStream(file)) { byte[] b=new byte[8192]; int n; while ((n=exchange.getRequestBody().read(b))!=-1) out.write(b,0,n); } exchange.sendResponseHeaders(201,-1);
                } else if (method.equals("GET")) { byte[] b=Files.readAllBytes(file); exchange.sendResponseHeaders(200,b.length); exchange.getResponseBody().write(b); }
                else if (method.equals("MOVE")) { Path target=root.resolve(new URI(exchange.getRequestHeaders().getFirst("Destination")).getPath().substring(1)); Files.move(file,target); exchange.sendResponseHeaders(201,-1); }
                else if (method.equals("DELETE")) { Files.delete(file); exchange.sendResponseHeaders(204,-1); }
                else exchange.sendResponseHeaders(405,-1);
            } catch (Exception e) { exchange.sendResponseHeaders(500,-1); }
            finally { exchange.close(); }
        }); server.start(); return server;
    }
    private static final class TestRoot implements LocalHttpTransferServer.Root {
        final Path root;
        TestRoot(Path p) { root=p; }
        Path safe(String path) throws IOException {
            Path p=root.resolve(RemoteFiles.path(path).substring(1));
            Path current=root; for (Path piece:root.relativize(p)) { current=current.resolve(piece); if (Files.isSymbolicLink(current)) throw new IOException(); }
            if (!p.normalize().startsWith(root)) throw new IOException(); return p;
        }
        public List<RemoteFiles.Entry> list(String p) throws IOException {
            List<RemoteFiles.Entry> result=new ArrayList<>(); try (java.util.stream.Stream<Path> all=Files.list(safe(p))) { for (Path f:(Iterable<Path>)all::iterator) if (!Files.isSymbolicLink(f)) result.add(new RemoteFiles.Entry(RemoteFiles.child(p,f.getFileName().toString()),Files.isDirectory(f),false,Files.size(f),0)); } return result;
        }
        public LocalHttpTransferServer.Download read(String p) throws IOException { Path f=safe(p); return new LocalHttpTransferServer.Download(Files.newInputStream(f),Files.size(f)); }
        public LocalHttpTransferServer.Upload upload(String p) throws IOException {
            Path dest=safe(p), temp=Files.createTempFile(root,".upload",""); OutputStream out=Files.newOutputStream(temp);
            return new LocalHttpTransferServer.Upload() {
                public void write(int b) throws IOException { out.write(b); }
                public void write(byte[] b,int o,int n) throws IOException { out.write(b,o,n); }
                public void commit() throws IOException { out.close(); Files.move(temp,dest); }
                public void close() throws IOException { out.close(); Files.deleteIfExists(temp); }
            };
        }
        public void close() { }
    }
    private static HttpURLConnection request(int port,String token,String route,String method,byte[] body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+route).openConnection(); c.setConnectTimeout(2000); c.setReadTimeout(2000); c.setRequestMethod(method);
        if (token!=null) c.setRequestProperty("Authorization","Bearer "+token);
        if (body!=null) { c.setDoOutput(true); c.setFixedLengthStreamingMode(body.length); try (OutputStream out=c.getOutputStream()) { out.write(body); } }
        return c;
    }
    private static void transfer(Path root) throws Exception {
        LocalHttpTransferServer server=new LocalHttpTransferServer(new TestRoot(root),1024);
        check(!server.isRunning(),"server starts stopped"); int port=server.start(false,0); String token=server.token();
        check(request(port,null,"/list?path=%2F","GET",null).getResponseCode()==401,"auth required");
        check(request(port,"wrong","/list?path=%2F","GET",null).getResponseCode()==401,"wrong auth rejected");
        check(request(port,token,"/upload?path=%2Ftest","PUT",new byte[]{4,5,6}).getResponseCode()==201,"HTTP upload");
        HttpURLConnection download=request(port,token,"/download?path=%2Ftest","GET",null);
        try (InputStream in=download.getInputStream()) { check(Arrays.equals(in.readAllBytes(),new byte[]{4,5,6}),"HTTP download identical"); }
        HttpURLConnection listing=request(port,token,"/list?path=%2F","GET",null);
        try (InputStream in=listing.getInputStream()) { check(new String(in.readAllBytes(),StandardCharsets.UTF_8).contains("\"name\":\"test\""),"HTTP list"); }
        check(request(port,token,"/upload?path=%2Ftest","PUT",new byte[]{0}).getResponseCode()==403,"HTTP no overwrite");
        check(request(port,token,"/upload?path=%2Flarge","PUT",new byte[1025]).getResponseCode()==413,"upload limit");
        try { check(request(port,token,"/download?path=%2F..%2Foutside","GET",null).getResponseCode()!=200,"traversal denied"); } catch (IOException e) { checks++; }
        Files.createSymbolicLink(root.resolve("link"),root.resolve("test"));
        check(request(port,token,"/download?path=%2Flink","GET",null).getResponseCode()==403,"link denied"); Files.delete(root.resolve("link"));
        // An interrupted body must not publish a destination file.
        try (Socket s=new Socket("127.0.0.1",port)) { s.getOutputStream().write(("PUT /upload?path=%2Fpartial HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token+"\r\nContent-Length: 100\r\n\r\nx").getBytes(StandardCharsets.US_ASCII)); }
        Thread.sleep(100); check(!Files.exists(root.resolve("partial")),"partial upload unpublished");
        server.close(); check(!server.isRunning(),"stopped");
        denied(()->{ try (Socket socket=new Socket("127.0.0.1",port)) { socket.getOutputStream().write(0); } },"listener closed");
        System.out.println("PASS HTTP opt-in/auth/list/upload/download/no-clobber/limits/traversal/symlinks/partial/stop");
    }
    private static void cancellationAndTimeout() throws Exception {
        try (ServerSocket stalled=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            List<Socket> accepted=Collections.synchronizedList(new ArrayList<>());
            Thread acceptor=new Thread(()-> { try { while (!stalled.isClosed()) accepted.add(stalled.accept()); } catch (IOException ignored) { } }); acceptor.setDaemon(true); acceptor.start();
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                RemoteFiles.Connection slow=new RemoteFiles.Connection("slow","slow",RemoteFiles.Protocol.SFTP,"127.0.0.1",stalled.getLocalPort(),"/","fixture","never-in-errors","","SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",1000,false);
                long start=System.nanoTime(); denied(()->RemoteFiles.connect(slow,new RemoteFiles.Cancellation()),"stalled SSH timeout");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<4000,"connect timeout bounded");
                RemoteFiles.Cancellation token=new RemoteFiles.Cancellation();
                Future<?> connecting=executor.submit(()-> { try { RemoteFiles.connect(slow,token); throw new AssertionError("cancelled connect succeeded"); } catch (IOException e) { check(e.getMessage().contains("cancelled"),"active connect cancelled"); } });
                Thread.sleep(100); token.cancel(); connecting.get(3,TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); synchronized (accepted) { for (Socket c:accepted) c.close(); } }
        }
        HttpServer stalledDav=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        stalledDav.createContext("/",e -> { try { e.sendResponseHeaders(207,1000); e.getResponseBody().write('<'); e.getResponseBody().flush(); Thread.sleep(3000); } catch (Exception ignored) { } finally { e.close(); } }); stalledDav.start();
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            RemoteFiles.Cancellation token=new RemoteFiles.Cancellation();
            Future<?> pending=executor.submit(()-> { try { RemoteFiles.connect(connection(RemoteFiles.Protocol.WEBDAV,stalledDav.getAddress().getPort(),""),token); throw new AssertionError("cancelled DAV succeeded"); } catch (IOException e) { check(e.getMessage().contains("cancelled"),"active HTTP cancelled"); } });
            Thread.sleep(150); long start=System.nanoTime(); token.cancel(); pending.get(2,TimeUnit.SECONDS);
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000,"HTTP cancellation unblocks body read");
        } finally { executor.shutdownNow(); stalledDav.stop(0); }
        System.out.println("PASS active SSH/HTTP cancellation and stalled SSH timeout");
    }
    public static void main(String[] args) throws Exception {
        if (args.length==2 && args[0].equals("reject-ftps")) {
            denied(()->RemoteFiles.connect(connection(RemoteFiles.Protocol.FTPS,Integer.parseInt(args[1]),""),new RemoteFiles.Cancellation()),"untrusted certificate rejected");
            System.out.println("PASS FTPS rejects untrusted certificate"); return;
        }
        denied(()->RemoteFiles.path("/../escape"),"remote traversal");
        denied(()->RemoteFiles.child("/","../escape"),"child traversal");
        denied(()->RemoteFiles.path("/a\r\nDELE /"),"FTP command injection");
        Path workspace=Paths.get(args[0]).toAbsolutePath();
        roundtrip(connection(RemoteFiles.Protocol.FTP,Integer.parseInt(args[1]),""));
        roundtrip(connection(RemoteFiles.Protocol.SFTP,Integer.parseInt(args[2]),args[4]));
        denied(()->RemoteFiles.connect(connection(RemoteFiles.Protocol.SFTP,Integer.parseInt(args[2]),"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),new RemoteFiles.Cancellation()),"wrong SSH host key rejected");
        // A successful socket roundtrip requires curve25519 + Ed25519 on this listener.
        // Disabling multi-release JARs in the second JVM exercises JSch's Java 8 BC implementations.
        roundtrip(connection(RemoteFiles.Protocol.SFTP,Integer.parseInt(args[6]),args[7]));
        denied(()->RemoteFiles.connect(connection(RemoteFiles.Protocol.SFTP,Integer.parseInt(args[6]),"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),new RemoteFiles.Cancellation()),"wrong Ed25519 host key rejected");
        System.out.println("PASS SFTP curve25519/Ed25519/AES-CTR/HMAC-SHA256 with pinned host key");
        roundtrip(connection(RemoteFiles.Protocol.SMB,Integer.parseInt(args[3]),""));
        roundtrip(connection(RemoteFiles.Protocol.FTPS,Integer.parseInt(args[5]),""));
        Path web=Files.createDirectory(workspace.resolve("dav")); HttpServer dav=dav(web);
        try { roundtrip(connection(RemoteFiles.Protocol.WEBDAV,dav.getAddress().getPort(),"")); } finally { dav.stop(0); }
        transfer(Files.createDirectory(workspace.resolve("http")));
        cancellationAndTimeout();
        System.out.println("PASS "+checks+" assertions");
    }
}
