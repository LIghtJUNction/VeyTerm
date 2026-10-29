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
    private static Path protocolRoot;
    private static void check(boolean ok,String description) { checks++; if (!ok) throw new AssertionError(description); }
    private interface Task { void run() throws Exception; }
    private static void denied(Task action,String description) throws Exception {
        try { action.run(); throw new AssertionError(description); }
        catch (IOException | IllegalArgumentException expected) { checks++; check(!expected.toString().contains("local-fixture-only"),"redacted error"); check(expected.getCause()==null,"no credential-bearing cause"); }
    }
    private static void unsupported(Task action,String description) throws Exception {
        try { action.run(); throw new AssertionError(description); }
        catch (IOException expected) {
            check(expected.getMessage().contains("not supported"),description+" explicitly unsupported");
            check(expected.getCause()==null && !expected.toString().contains("local-fixture-only"),"unsupported error redacted");
        }
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
                if (config.protocol==RemoteFiles.Protocol.SFTP) {
                    inside.chmod("/你好 space #%.bin",0640);
                    check(inside.permissions("/你好 space #%.bin").startsWith("0640 · uid "),"nested permission path");
                    inside.symlink("你好 space #%.bin","/nested-link");
                    check(inside.readLink("/nested-link").equals("你好 space #%.bin"),"nested link target stays relative");
                    check(inside.stat("/nested-link").symbolicLink,"nested lstat nofollow");
                    inside.delete("/nested-link");
                    inside.setModified("/你好 space #%.bin",2208988800000L);
                    check(inside.stat("/你好 space #%.bin").modified==2208988800000L,"nested mtime path");
                }
            }
            metadata(remote,config,original,protocolRoot.resolve(dir.substring(1)).resolve("你好 space #%.bin"));
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
            denied(()->remote.permissions("/"),"cancel prevents permissions");
            denied(()->remote.chmod("/cancelled",0600),"cancel prevents chmod");
            denied(()->remote.readLink("/cancelled"),"cancel prevents readLink");
            denied(()->remote.symlink("target","/cancelled"),"cancel prevents symlink");
            denied(()->remote.setModified("/cancelled",1000),"cancel prevents setModified");
        }
        check(!config.location("/a b").contains("network-fixture"),"opaque URI excludes user");
        System.out.println("PASS "+config.protocol+" list/stat/read/write/mkdir/rename/delete/progress/cancellation");
    }
    private static void metadata(RemoteFiles remote,RemoteFiles.Connection config,String path,Path physical) throws Exception {
        for (String bad:new String[]{"relative","/../escape","/bad\r\nDELE /victim","/bad\0tail"}) {
            denied(()->remote.permissions(bad),"permissions rejects unsafe path");
            denied(()->remote.chmod(bad,0600),"chmod rejects unsafe path");
            denied(()->remote.readLink(bad),"readLink rejects unsafe path");
            denied(()->remote.symlink("target",bad),"symlink rejects unsafe link path");
            denied(()->remote.setModified(bad,1000),"setModified rejects unsafe path");
        }
        for (int mode:new int[]{-1,010000,Integer.MAX_VALUE}) denied(()->remote.chmod(path,mode),"chmod rejects out-of-range mode");
        for (String target:new String[]{null,"","bad\0target"}) denied(()->remote.symlink(target,path+".link"),"reject invalid link payload");
        denied(()->remote.chmod("/",0600),"chmod protects connection root");
        denied(()->remote.symlink("target","/"),"symlink protects connection root");
        denied(()->remote.setModified("/",1000),"setModified protects connection root");
        denied(()->remote.setModified(path,-1),"negative modification time");
        try (InputStream in=remote.read(path)) {
            denied(()->remote.permissions(path),"metadata refuses open stream");
            denied(()->remote.chmod(path,0600),"chmod refuses open stream");
            denied(()->remote.readLink(path),"readLink refuses open stream");
            denied(()->remote.symlink("target",path+".link"),"symlink refuses open stream");
            denied(()->remote.setModified(path,1000),"setModified refuses open stream");
            check(in.readAllBytes().length==160003,"stream remains readable after refused metadata calls");
        }
        if (config.protocol==RemoteFiles.Protocol.SFTP) {
            String owner=" · uid "+Files.getAttribute(physical,"unix:uid")+" · gid "+Files.getAttribute(physical,"unix:gid");
            for (int mode:new int[]{0000,0640,0751,01750,07777,0600}) {
                remote.chmod(path,mode);
                check(remote.permissions(path).equals(String.format(Locale.ROOT,"%04o",mode)+owner),"SFTP octal/uid/gid roundtrip");
                check(((Integer)Files.getAttribute(physical,"unix:mode") & 07777)==mode,"SFTP chmod changes actual Unix mode");
            }
            for (long millis:new long[]{0,1700000123456L,2147483647000L,2147483648000L,2208988800123L,4294967295999L}) {
                remote.setModified(path,millis);
                long seconds=millis/1000*1000;
                check(remote.stat(path).modified==seconds,"SFTP unsigned uint32 mtime roundtrip");
                check(Files.getLastModifiedTime(physical).toMillis()==seconds,"SFTP mtime reaches filesystem");
                check(remote.list(RemoteFiles.parent(path)).stream().anyMatch(e->e.path.equals(path) && e.modified==seconds),"SFTP listing unsigned mtime");
            }
            denied(()->remote.setModified(path,4294967296000L),"SFTP uint32 overflow rejected");
            denied(()->remote.setModified(path,Long.MAX_VALUE),"SFTP long overflow rejected");
            check(remote.stat(path).modified==4294967295000L,"overflow does not wrap mtime");
            remote.chmod(RemoteFiles.parent(path),0750);
            check(remote.permissions(RemoteFiles.parent(path)).startsWith("0750"),"SFTP directory permissions");
            remote.setModified(RemoteFiles.parent(path),2208988800000L);
            check(remote.stat(RemoteFiles.parent(path)).modified==2208988800000L,"SFTP directory mtime");
            check(Files.getLastModifiedTime(physical.getParent()).toMillis()==2208988800000L,"SFTP actual directory mtime");
            sftpLinks(remote,path,physical);
        } else {
            unsupported(()->remote.permissions(path),"permissions");
            unsupported(()->remote.readLink(path),"readLink");
            unsupported(()->remote.symlink("../你好\n*?\\target",path+".link"),"symlink");
            if (config.protocol==RemoteFiles.Protocol.FTP || config.protocol==RemoteFiles.Protocol.FTPS) {
                for (int mode:new int[]{0000,0640,0751,0600}) {
                    remote.chmod(path,mode);
                    check(((Integer)Files.getAttribute(physical,"unix:mode") & 07777)==mode,"FTP SITE CHMOD permission roundtrip");
                }
                remote.setModified(path,0);
                check(Files.getLastModifiedTime(physical).toMillis()==0,"FTP MFMT Unix epoch zero");
                Locale oldLocale=Locale.getDefault(); TimeZone oldZone=TimeZone.getDefault();
                try {
                    Locale.setDefault(new Locale("ar","EG")); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
                    remote.setModified(path,2208988800456L);
                } finally { Locale.setDefault(oldLocale); TimeZone.setDefault(oldZone); }
                check(Files.getLastModifiedTime(physical).toMillis()==2208988800000L,"FTP MFMT UTC seconds including 2040");
                denied(()->remote.setModified(path,Long.MAX_VALUE),"FTP rejects non-four-digit year");
            } else if (config.protocol==RemoteFiles.Protocol.SMB) {
                unsupported(()->remote.chmod(path,0640),"SMB chmod");
                remote.setModified(path,2208988800123L);
                check(remote.stat(path).modified==2208988800123L,"Samba mtime roundtrip including milliseconds and 2040");
                check(Files.getLastModifiedTime(physical).toMillis()==2208988800123L,"SMB changes actual filesystem mtime");
                unsupported(()->remote.setModified(path,0),"jcifs cannot write Unix epoch zero");
                check(remote.stat(path).modified==2208988800123L,"SMB epoch zero is not silently ignored");
                denied(()->remote.setModified(path,Long.MAX_VALUE),"SMB FILETIME overflow rejected");
                remote.setModified(RemoteFiles.parent(path),2208988800123L);
                check(remote.stat(RemoteFiles.parent(path)).modified==2208988800123L,"Samba directory mtime roundtrip");
                check(Files.getLastModifiedTime(physical.getParent()).toMillis()==2208988800123L,"SMB changes actual directory mtime");
            } else {
                unsupported(()->remote.chmod(path,0640),"WebDAV chmod");
                unsupported(()->remote.setModified(path,1000),"WebDAV setModified");
            }
        }
        System.out.println("PASS "+config.protocol+" metadata/links/validation");
    }
    private static void sftpLinks(RemoteFiles remote,String path,Path physical) throws Exception {
        String parent=RemoteFiles.parent(path), link=path+".link", copy=path+".copy";
        String name=physical.getFileName().toString();
        remote.symlink(name,link);
        check(remote.stat(link).symbolicLink && !remote.stat(link).directory,"SFTP lstat file link nofollow");
        check(remote.list(parent).stream().anyMatch(e->e.path.equals(link) && e.symbolicLink && !e.directory),"SFTP listing nofollow");
        check(remote.readLink(link).equals(name),"SFTP readLink returns raw target");
        check(Files.readSymbolicLink(physical.resolveSibling(name+".link")).toString().equals(name),"actual Unix link target");
        remote.symlink(remote.readLink(link),copy);
        check(remote.stat(copy).symbolicLink && remote.readLink(copy).equals(name),"SFTP copy preserves symlink target");
        ByteArrayOutputStream target=new ByteArrayOutputStream(); remote.download(copy,target,null);
        check(target.size()==160003,"copied relative link resolves to original target");
        denied(()->remote.chmod(link,0777),"chmod refuses symbolic link");
        denied(()->remote.setModified(link,1000),"mtime refuses symbolic link");
        check(remote.permissions(path).startsWith("0600"),"link chmod did not modify target");
        check(remote.stat(path).modified==4294967295000L,"link mtime did not modify target");
        check(remote.permissions(link).startsWith("0777"),"permissions lstat nofollow");
        denied(()->remote.readLink(path),"readLink rejects regular file");
        denied(()->remote.symlink("different",copy),"link creation no clobber");
        check(remote.readLink(copy).equals(name),"existing link target preserved");
        remote.delete(link); remote.delete(copy);
        remote.symlink(".",link);
        check(remote.stat(link).symbolicLink && !remote.stat(link).directory,"directory link lstat nofollow");
        remote.delete(link); check(remote.stat(parent).directory,"delete directory link preserves directory");
        for (String raw:new String[]{"../missing 你好\nline\r*?[x]\\tail","/absolute/../你好*?\\tail","./"+name}) {
            remote.symlink(raw,link);
            check(remote.stat(link).symbolicLink,"dangling or relative link lstat");
            check(remote.readLink(link).equals(raw),"Unicode/newline/glob/backslash/absolute target preserved verbatim");
            remote.symlink(remote.readLink(link),copy);
            check(remote.readLink(copy).equals(raw),"copy arbitrary Unix link payload");
            remote.delete(link); remote.delete(copy);
        }
        String glob=parent+"/literal*[?].link";
        remote.symlink(name,glob); check(remote.readLink(glob).equals(name),"literal wildcard link path"); remote.delete(glob);
        glob=parent+"/literal*[?].file";
        remote.upload(glob,new ByteArrayInputStream(new byte[]{7}),false,null);
        remote.chmod(glob,0640); check(remote.permissions(glob).startsWith("0640"),"literal wildcard chmod and permissions");
        remote.setModified(glob,2147483648000L); check(remote.stat(glob).modified==2147483648000L,"literal wildcard setModified");
        remote.delete(glob);
        check(remote.stat(path).size==160003,"link deletion preserves target");
    }
    private static void ftpUnsupported(int port) throws Exception {
        try (RemoteFiles remote=RemoteFiles.connect(connection(RemoteFiles.Protocol.FTP,port,""),new RemoteFiles.Cancellation())) {
            String path="/no-metadata.bin"; remote.upload(path,new ByteArrayInputStream(new byte[]{1}),false,null);
            Path physical=protocolRoot.resolve(path.substring(1));
            long before=Files.getLastModifiedTime(physical).toMillis(); int mode=(Integer)Files.getAttribute(physical,"unix:mode");
            unsupported(()->remote.setModified(path,1000),"server rejects MFMT");
            unsupported(()->remote.chmod(path,0640),"server rejects SITE CHMOD");
            check(Files.getLastModifiedTime(physical).toMillis()==before && (Integer)Files.getAttribute(physical,"unix:mode")==mode,"unsupported metadata writes leave file unchanged");
            check(remote.stat(path).size==1,"FTP remains usable after unsupported commands"); remote.delete(path);
        }
        System.out.println("PASS FTP optional MFMT/SITE CHMOD rejection");
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
    private static void xmlSecurity() throws Exception {
        String valid="<?xml version=\"1.0\" encoding=\"UTF-8\"?><d:multistatus xmlns:d=\"DAV:\"><d:response>你好 &amp; safe</d:response></d:multistatus>";
        check(WebDavBackend.parsePropertiesXml(valid.getBytes(StandardCharsets.UTF_8)).getDocumentElement().getNamespaceURI().equals("DAV:"), "portable namespace-aware UTF8 DOM");
        check(WebDavBackend.parsePropertiesXml(("\ufeff"+valid).getBytes(StandardCharsets.UTF_8)).getDocumentElement()!=null,"UTF8 BOM accepted");
        String[] attacks={"<!DOCTYPE x SYSTEM 'http://127.0.0.1:1/private'><x/>",
            "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///private'>]><x>&secret;</x>",
            "<!DOCTYPE x [<!ENTITY % p SYSTEM 'http://127.0.0.1:1/private'>%p;]><x/>",
            "<!DOCTYPE x [<!ENTITY a '123'><!ENTITY b '&a;&a;'>]><x>&b;</x>",
            "<!ENTITY secret SYSTEM 'file:///private'><x/>", "<x>&undeclared;</x>"};
        for (String attack:attacks) {
            try { WebDavBackend.parsePropertiesXml(attack.getBytes(StandardCharsets.UTF_8)); throw new AssertionError("unsafe XML accepted"); }
            catch (IOException | org.xml.sax.SAXException expected) { checks++; }
            for (String encoding:new String[]{"UTF-16LE","UTF-16BE","UTF-32LE","UTF-32BE","IBM037"}) {
                try { WebDavBackend.parsePropertiesXml(attack.getBytes(encoding)); throw new AssertionError("non-UTF8 unsafe XML accepted"); }
                catch (IOException | org.xml.sax.SAXException expected) { checks++; }
            }
        }
        try { WebDavBackend.parsePropertiesXml(new byte[]{'<','x','>',(byte)0xc0,(byte)0xbc,'/','x','>'}); throw new AssertionError("malformed UTF8 accepted"); }
        catch (java.nio.charset.CharacterCodingException expected) { checks++; }
        System.out.println("PASS portable XML UTF8 parsing and fail-closed DTD/entity/encoding checks");
    }
    private static void diagnostics() throws Exception {
        String secret = "https://user:local-fixture-only@private.invalid/path?token=secret";
        IOException leaf = new IOException(secret);
        leaf.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.example.Transport", "send", secret, 73)});
        IOException outer = new IOException(secret, leaf);
        outer.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.example.Client$Worker", "<init>", secret, 12)});
        String expected = RemoteFiles.safeDiagnostic(outer);
        check(expected.contains("java.io.IOException at org.example.Transport.send:73"), "nested cause code frame");
        check(expected.contains("org.example.Client_Worker._init_:12"), "code symbols fit host report filter");
        check(!expected.contains(secret) && !expected.contains("local-fixture-only") && !expected.contains("private.invalid"), "diagnostic omits messages and file names");
        check(expected.matches("[A-Za-z0-9_./:@,\\[\\] -]*"), "diagnostic accepted by host instrumentation filter");
        java.lang.reflect.Constructor<RemoteFiles> constructor = RemoteFiles.class.getDeclaredConstructor(RemoteFiles.Backend.class, RemoteFiles.Cancellation.class);
        constructor.setAccessible(true);
        RemoteFiles.Backend backend = (RemoteFiles.Backend) java.lang.reflect.Proxy.newProxyInstance(RemoteFiles.Backend.class.getClassLoader(), new Class<?>[]{RemoteFiles.Backend.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("close")) return null;
                if (method.getName().equals("read")) return new InputStream() {
                    @Override public int read() throws IOException { throw outer; }
                    @Override public void close() throws IOException { throw outer; }
                };
                if (method.getName().equals("write")) return new OutputStream() {
                    @Override public void write(int value) throws IOException { throw outer; }
                    @Override public void flush() throws IOException { throw outer; }
                    @Override public void close() throws IOException { throw outer; }
                };
                throw outer;
            });
        try (RemoteFiles remote = constructor.newInstance(backend, new RemoteFiles.Cancellation())) {
            Task[] failures = {() -> remote.list("/"), () -> remote.stat("/"),
                () -> { try (InputStream in = remote.read("/test")) { in.read(); } },
                () -> { try (InputStream in = remote.read("/test")) { in.available(); } },
                () -> { remote.read("/test").close(); },
                () -> { try (OutputStream out = remote.write("/test", true)) { out.write(1); } },
                () -> { try (OutputStream out = remote.write("/test", true)) { out.flush(); } },
                () -> { remote.write("/test", true).close(); }};
            for (Task task : failures) {
                try { task.run(); throw new AssertionError("backend failure expected"); }
                catch (IOException safe) {
                    check(safe.getCause() == null, "original cause not retained");
                    check(RemoteFiles.safeDiagnostic(safe).equals(expected), "operation and stream failures retain safe code chain");
                    StringWriter trace = new StringWriter(); safe.printStackTrace(new PrintWriter(trace));
                    check(!trace.toString().contains("local-fixture-only") && !trace.toString().contains("private.invalid"), "standard exception log redacted");
                }
            }
        }
        IOException deep = new IOException(secret);
        StackTraceElement[] deepFrames = new StackTraceElement[12];
        Arrays.fill(deepFrames, new StackTraceElement("java.net.SocketInputStream", "read", secret, 3));
        for (int i=4;i<10;i++) deepFrames[i]=new StackTraceElement("com.android.org.conscrypt.ConscryptEngineSocket", "read", secret, 6);
        deepFrames[10] = new StackTraceElement("org.apache.commons.net.ftp.FTPSClient", "_openDataConnection_", secret, 4);
        deepFrames[11] = new StackTraceElement("com.termux.app.files.remote.FtpBackend", "write", secret, 5);
        deep.setStackTrace(deepFrames);
        String deepDiagnostic=RemoteFiles.safeDiagnostic(deep);
        check(deepDiagnostic.contains("FTPSClient._openDataConnection_:4") && deepDiagnostic.contains("FtpBackend.write:5"), "retain protocol callers beyond socket frames");
        check(!deepDiagnostic.contains("@"), "frame separators avoid email redaction");
        leaf.initCause(outer);
        check(RemoteFiles.safeDiagnostic(outer).length() < 3501, "cyclic causes bounded");
        IOException invalid = new IOException(secret);
        invalid.setStackTrace(new StackTraceElement[]{new StackTraceElement(secret, secret, secret, 1)});
        check(!RemoteFiles.safeDiagnostic(invalid).contains("private.invalid"), "invalid code symbols redacted");
        System.out.println("PASS safe diagnostics for nested causes, stream failures, redaction and cycles");
    }
    public static void main(String[] args) throws Exception {
        diagnostics();
        xmlSecurity();
        if (args.length==1 && args[0].equals("diagnostics-only")) { System.out.println("PASS "+checks+" focused assertions"); return; }
        if (args.length==2 && args[0].equals("reject-ftps")) {
            denied(()->RemoteFiles.connect(connection(RemoteFiles.Protocol.FTPS,Integer.parseInt(args[1]),""),new RemoteFiles.Cancellation()),"untrusted certificate rejected");
            System.out.println("PASS FTPS rejects untrusted certificate"); return;
        }
        denied(()->RemoteFiles.path("/../escape"),"remote traversal");
        denied(()->RemoteFiles.child("/","../escape"),"child traversal");
        denied(()->RemoteFiles.path("/a\r\nDELE /"),"FTP command injection");
        Path workspace=Paths.get(args[0]).toAbsolutePath();
        protocolRoot=Paths.get(args[8]);
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
        ftpUnsupported(Integer.parseInt(args[9]));
        Path web=Files.createDirectory(workspace.resolve("dav")); HttpServer dav=dav(web);
        try { roundtrip(connection(RemoteFiles.Protocol.WEBDAV,dav.getAddress().getPort(),"")); } finally { dav.stop(0); }
        transfer(Files.createDirectory(workspace.resolve("http")));
        cancellationAndTimeout();
        System.out.println("PASS "+checks+" assertions");
    }
}
