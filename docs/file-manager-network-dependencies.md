# File manager network module integration

Worker scope: `app/src/main/java/com/termux/app/files/remote/**` and corresponding
focused tests. No shared Gradle, Manifest, storage implementation, bootstrap, or
root-access backend is changed. `AndroidTransferRoot` is only the HTTP server's
confined local directory capability, not the parent's storage/root backend.

## Exact dependency coordinates

Parent adds these to `app/build.gradle` (not changed in this worktree):

```groovy
implementation 'com.github.mwiede:jsch:2.28.8'
implementation 'commons-net:commons-net:3.13.0'
implementation 'commons-io:commons-io:2.22.0'
implementation 'eu.agno3.jcifs:jcifs-ng:2.1.10'
// Override jcifs-ng's obsolete 1.76 transitive; do not package both versions.
implementation 'org.bouncycastle:bcprov-jdk18on:1.86'
implementation 'org.slf4j:slf4j-api:2.0.17'
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
// Keep protocol libraries silent: their debug messages can contain authentication material.
runtimeOnly 'org.slf4j:slf4j-nop:2.0.17'
```

Verified on 2026-10-30 against official release notes and Maven Central metadata.
JSch's current release is **2.28.8**, superseding the initially proposed 2.28.7;
its release adds SFTP data, authentication prompt and arithmetic-overflow bounds
checks. The repository ChangeLog currently stops at 2.28.0, so it alone cannot
establish the latest release. Commons Net 3.13.0 adds a runtime Commons IO
transitive ([2.21.0 in its POM](https://repo.maven.apache.org/maven2/commons-net/commons-net/3.13.0/commons-net-3.13.0.pom));
the tested override here is 2.22.0.

OkHttp 4.12.0 remains the parent's existing integration contract, **not** a claim
that it is the latest major release (Maven currently lists 5.5.1). SLF4J API/nop
2.0.17 likewise follows the parent's explicit matched-version pin, not the latest
2.0.x metadata. This refresh does not migrate to OkHttp 5 or SLF4J alpha releases.

Retain Java 8 and core-library desugaring; the parent selects
`com.android.tools:desugar_jdk_libs_nio:2.1.5`. SDK compilation here does not
validate that Gradle desugaring configuration or runtime.
Resolve only the maintained JSch fork; exclude `com.jcraft:jsch` if another library
introduces the old artifact (same Java package). Do not add HTTP logging
interceptors, JSch loggers, or a competing SLF4J binding. No trust-all TLS manager,
TOFU SSH acceptance, SMB1 fallback, external SSH executable, or native protocol
client is used.

Transitives used by the local checks:

| Coordinate | License / note |
| --- | --- |
| `org.slf4j:slf4j-api:2.0.17` | MIT, jcifs-ng API dependency |
| `org.bouncycastle:bcprov-jdk18on:1.86` | Bouncy Castle license (MIT-style), explicit override of jcifs-ng 1.76 |
| `commons-io:commons-io:2.22.0` | Apache-2.0, explicit override of Commons Net transitive |
| `com.squareup.okio:okio-jvm:3.6.0` | Apache-2.0, OkHttp dependency |
| `org.jetbrains.kotlin:kotlin-stdlib:1.9.10` | Apache-2.0, local runtime; parent's Kotlin version may resolve newer |

Pinned artifacts were fetched from Maven Central and compiled directly with
`javac`. The machine-readable test lock is
[`dependency-pins.json`](../app/src/test/java/com/termux/app/files/remote/dependency-pins.json).
The runner verifies every SHA-256 before compiling and constructs an explicit
classpath, so an old JAR left in the directory cannot shadow the verified pin.
SHA-256:

```text
jsch-2.28.8.jar              2afca1357d816028f2ac33f22407d2488a68406682973949cebb1b8ea6688a8b
commons-net-3.13.0.jar       9e88444afadc9467f0bf3d328f93b9538da35a9be05cdee430766e64fddbd6c5
commons-io-2.22.0.jar        2b9a7b1f726fb86216dbd2c8321eabe0221dbd5b1be81c18e1cb53811b104758
jcifs-ng-2.1.10.jar          1940332a416644b3b464335d0e0fb12530fb2c1e4cfb57e7d3339b46b2f0af48
bcprov-jdk18on-1.86.jar      2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f
okhttp-4.12.0.jar            b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0
slf4j-api-2.0.17.jar         7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832
slf4j-nop-2.0.17.jar         3716f83649ec66161a2edefd4f49df34d1dd1c51cdcf941996c6987260f0a829
okio-jvm-3.6.0.jar           67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412
kotlin-stdlib-1.9.10.jar     55e989c512b80907799f854309f3bc7782c5b3d13932442d0379d5c472711504
```

### Upstream API and license evidence

- [JSch 2.28.8 release](https://github.com/mwiede/jsch/releases/tag/jsch-2.28.8),
  [licenses](https://github.com/mwiede/jsch/blob/jsch-2.28.8/LICENSE.txt): revised
  BSD, with included component BSD/ISC notices. Uses `HostKeyRepository`,
  `Session.connect(int)`, `setTimeout`, `ChannelSftp.ls/lstat/get/put/mkdir/rename/rm/rmdir`.
- [Commons Net release history](https://commons.apache.org/proper/commons-net/changes.html),
  [Apache license](https://github.com/apache/commons-net/blob/rel/commons-net-3.13.0/LICENSE.txt),
  [FTPS API warning about hostname verification](https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ftp/FTPSClient.html).
  Uses passive binary transfers, `retrieveFileStream/storeFileStream`,
  `completePendingCommand`, integer data timeout, `FTPSClient(false)`, system
  X509 trust, endpoint checking, `PBSZ 0`, `PROT P`.
- [Commons IO release history](https://commons.apache.org/proper/commons-io/changes.html):
  2.22.0 released 2026-04-19, Java 8+; Apache-2.0.
- [Bouncy Castle 1.86 release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/):
  released 2026-09-15. Its base classes, JSch, Commons Net and Commons IO were
  inspected as Java 8 bytecode (major 52). Real signed Samba NTLM transfers below
  exercise the jcifs-ng override, rather than just linking its crypto classes.
- [jcifs-ng changelog](https://github.com/AgNO3/jcifs-ng/blob/master/CHANGELOG.txt)
  and [Maven release metadata](https://repo.maven.apache.org/maven2/eu/agno3/jcifs/jcifs-ng/maven-metadata.xml)
  still identify 2.1.10 as current.
- [jcifs-ng 2.1.10 POM](https://github.com/AgNO3/jcifs-ng/blob/jcifs-ng-2.1.10/pom.xml),
  [source license header and implementation](https://github.com/AgNO3/jcifs-ng/blob/jcifs-ng-2.1.10/src/main/java/jcifs/smb/SmbFile.java):
  LGPL-2.1-or-later. Uses per-connection `BaseContext/PropertyConfiguration`,
  separate `NtlmPasswordAuthenticator`, `SmbFile`, `SmbFileInputStream`,
  `openOutputStream(false, flags, access, sharing)` with `O_EXCL` for create-only,
  `renameTo(target,false)`, and `CIFSContext.close()`. A per-URL handler preserves
  raw Unicode/space/#/% path characters because jcifs consumes URL.getPath literally. SMB202 minimum, SMB311
  maximum, mandatory signing, DFS disabled. Reparse attribute `0x400` is reported
  as `symbolicLink`; deletion of observed reparse points is refused.
- [OkHttp 4.12.0 source](https://github.com/square/okhttp/tree/parent-4.12.0),
  [Apache-2.0 license](https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt):
  uses arbitrary method `Request.Builder.method`, streaming `RequestBody`,
  `ResponseBody.byteStream`, `Call.cancel`, and connect/read/write/call timeouts.
  No automatic redirects or retries; PROPFIND permits only an exact same-origin
  canonical trailing-slash redirect. WebDAV uses PROPFIND, GET, PUT, MKCOL,
  MOVE with `Overwrite: F`, DELETE; no nonstandard method reflection.

The upstream APIs above were checked against the pinned binaries. Distribution
must retain dependency license/notice texts. LGPL SMB redistribution needs the
library source/rebuild/relink materials required by its license; do not assume
an APK dependency eliminates those obligations. Parent owns final packaging and
any R8 optional-package rules; this worker did not run D8/R8 or an Android build.

## Manifest / platform recommendations (parent-owned)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<!-- Inside application: no intent-filter, no external launch surface. -->
<activity android:name="com.termux.app.files.remote.RemoteConnectionsActivity"
          android:exported="false" />
```

INTERNET and `allowBackup=false` already exist in the inspected base Manifest.
No storage permission, service declaration, boot receiver, foreground-service
permission, notification, port exposure, or automatic server startup is added.
The transfer server is scoped to the visible picker and is stopped by dialog
close, Stop, `onStop`, and `onDestroy`. It is an HTTP service implemented as an
in-process socket server, not a persistent Android `Service`.

Engine/HTTP sharing target API 21+. Saving credentials and the connection picker
require API 23+ AndroidKeyStore AES-GCM; API 21/22 explicitly disable saved
connections and keep the HTTP transfer button available. No plaintext fallback.

HTTPS WebDAV is the default. `Connection.allowInsecure=true` selects HTTP WebDAV
and is a user-visible opt-in. Android's app network policy must also permit the
chosen HTTP host. Parent can add a narrow cleartext domain config for known hosts;
arbitrary user-entered HTTP hosts require an explicit app-level cleartext policy
(e.g. `android:usesCleartextTraffic="true"`). This worker does not silently relax
that policy. The raw HTTP listener itself does not require enabling outbound
cleartext. FTP also requires the explicit insecure flag; FTPS is explicit TLS on
port 21 by default (implicit FTPS on 990 is not implemented).

## Integration API

Launch from parent UI using its normal activity-result mechanism:

```java
Intent pick = new Intent(activity, RemoteConnectionsActivity.class);
// Optional: reopen a saved connection at a root-relative path.
pick.putExtra("remote_connection", savedConnectionId);
pick.putExtra("remote_path", "/folder");
activity.startActivityForResult(pick, REQUEST_REMOTE);
// RESULT_OK: getStringExtra("remote_connection"), getStringExtra("remote_path").
```

Both result extras are strings. `remote_connection` is an opaque saved record ID,
never an endpoint/credential JSON/URI. `remote_path` is absolute **within that
connection's configured root**, not the server's absolute path. `/` maps to
`Connection.root`; SMB roots must include the share, e.g. `/documents/subdir`.
The picker supports saved connection creation/edit/delete, unreadable-record
removal, asynchronous directory browsing, parent navigation, folder/file choice,
and cancellation. Connection labels and virtual paths are shown; credentials
are not interpolated into addresses/errors. Password widgets disable state
saving/autofill, and credential/share dialogs use FLAG_SECURE.

```java
ConnectionStore store = new ConnectionStore(context);
RemoteFiles.Connection config = store.load(id); // off UI thread
RemoteFiles.Cancellation cancel = new RemoteFiles.Cancellation();
try (RemoteFiles remote = RemoteFiles.connect(config, cancel)) {
    List<RemoteFiles.Entry> entries = remote.list("/");
    RemoteFiles.Entry item = remote.stat("/folder/file");
    remote.download("/folder/file", localOutput, (bytes,total) -> { /* UI progress */ });
    remote.upload("/folder/new", localInput, false, (bytes,total) -> { /* UI progress */ });
    remote.mkdir("/new-folder");
    remote.rename("/old", "/new"); // full destination virtual path
    remote.delete("/new");
    String opaqueLocation = config.location("/folder/file"); // remote://<id>/folder/file
}
// Another thread may call cancel.cancel(); it closes the connection and active I/O.
```

`read(path[,progress])` / `write(path,replace[,progress])` expose closeable streams.
Close the stream before another operation on that session. Stream close checks
FTP completion / HTTP status; do not mark success before close returns. One
session belongs to one worker; only cancellation/close are cross-thread APIs.
Use separate sessions for concurrent panes/transfers. Local streams passed to
upload/download remain caller-owned. Progress reports cumulative bytes and
`total=-1` (unknown); use stat size if the parent needs a determinate total.
Copy/move/conflict policy and recursive traversal remain parent-owned.

The entire connection record (including username, password, host and root) is
AES-GCM encrypted to `noBackupFilesDir/remote-connections/<id>.gcm`, with a random
IV and ID authenticated as AAD. Only the key alias is fixed. Keystore failures,
tampering, key loss and oversized records fail closed. AtomicFile transactions
are serialized across store instances in the same process. No preferences hold
credentials; no remote library exceptions or causes escape the public engine.

## HTTP transfer API

Only an explicit Start button or explicit API call opens a listener:

```java
LocalHttpTransferServer server = new LocalHttpTransferServer(
    new AndroidTransferRoot(chosenDirectory), 1024L * 1024 * 1024);
int port = server.start(false, 0); // bind 127.0.0.1; ephemeral port
String token = server.token();   // 256-bit random bearer token; not part of a URL
// start(true, port) explicitly opts into all IPv4 interfaces (LAN).
server.close();                 // stop accepting, close clients/workers/root
```

Every request requires `Authorization: Bearer <token>`. The token is shown only
in the secure transfer dialog, never logged, stored or included in the URI.
HTTP is unencrypted; the LAN checkbox says so and is unchecked by default.

- `GET /list?path=%2F` → JSON name/directory/size/modified array.
- `GET /download?path=%2Fname` → streamed bytes with Content-Length.
- `PUT /upload?path=%2Fname` + Content-Length → new file, 201 on committed success.

URL-encode the single root-relative path parameter. No CORS, directory HTML,
external assets, anonymous requests, recursive delete endpoint, or auto exposure.
Uploads refuse existing destinations, have a configured size limit, and stage
0600 files; interrupted uploads remove their stage. Publish uses an atomic
same-directory hard link (filesystems that reject hard links explicitly fail).
Two workers, bounded queue, header limits and socket deadlines bound request
resources. Chunked request bodies and Expect: 100-continue are rejected.

`AndroidTransferRoot` pins directory descriptors, opens each descendant with
O_NOFOLLOW, checks fstat types, and uses `/proc/self/fd/<fd>/...` for descriptor-
relative access. It excludes links, devices, sockets and FIFOs, and refuses
traversal even during path-component swaps. Root access is limited to app-readable
local directories; SAF/privileged files are not re-exported. The JVM HTTP fixture
uses a separate test root and does **not** establish Android descriptor behavior.

## Local verification and concrete gaps

Focused runner (no Gradle/bootstrap/download/device/CI):

```sh
python3 app/src/test/java/com/termux/app/files/remote/run_local.py \
  --deps /path/to/pinned-jars \
  --android-jar /opt/android-sdk/platforms/android-36/android.jar \
  --lambda-stubs /opt/android-sdk/build-tools/36.0.0/core-lambda-stubs.jar
```

Provide the jars listed above (plus SLF4J nop), Python `pyftpdlib==2.1.0`,
`paramiko==3.5.1`, `pyOpenSSL`, `impacket` (only fixture hash helpers), and local
`javac/java` (JDK 17+ for this runner), `openssl/keytool`, `smbd`. Optional Python packages can live in the
supplied dependency directory's `python/`. The runner downloads nothing, uses
only loopback high ports, temporary Samba databases/config with mandatory
signing, an ephemeral SSH host key and a one-day local TLS certificate, and
cleans test directories and fixture processes. All temporary keys, databases and
compilation output stay under this worktree's `build/network-refresh/`. No system Samba config/user/password
is modified. Fixtures use public dummy credentials, not user credentials.

Verified locally: the six platform-independent production classes compile with
`javac --release 8` (Java 8 APIs and bytecode). All nine production classes also
compile at Java 8 source/target using **Android SDK 36 as the bootclasspath** plus
SDK `core-lambda-stubs.jar`; the desktop JDK is not used to fill missing Android
APIs. The host-JDK suite and a second JVM with
`-Djdk.util.jar.enableMultiRelease=false` both complete the checks below. The
latter selects JSch's root Java 8 classes and BC crypto implementations; it is
not an Android VM or an actual Java 8 runtime. Five protocols complete real socket
list/stat/mkdir/160003-byte binary upload/download/no-clobber/rename/replace/
nonempty-delete rejection/file-delete/directory-delete/progress/cancel checks,
including Unicode/space/#/% filenames, nested configured roots, and rejecting
listing a regular file.
SFTP tests literal wildcard filenames and rejects mismatched host keys for both
RSA and Ed25519 listeners. The second listener permits only
`curve25519-sha256@libssh.org`, `ssh-ed25519`, `aes128-ctr` and `hmac-sha2-256`.
Both JVM modes complete real binary transfers against it, covering BC XDH and
EdDSA operation under the Java 8 base-JAR path. No incompatible provider was
observed in these local checks; no production provider, global security registry,
host-key, algorithm-default, or TLS trust change was needed. Android's own
bootclasspath/provider class loading still needs device acceptance. FTPS
completes authenticated protected-data transfers with the fixture CA and rejects
that CA in a fresh untrusted JVM. SSH timeout, active SSH/HTTP cancellation,
opaque URI/error redaction, HTTP authentication/list/upload/download/limit/
traversal/symlink/no-clobber/interrupted-upload/stop checks also pass.

Final focused run: **213 assertions passed in each JVM mode (426 total)**, plus
the separate fresh-JVM FTPS untrusted-certificate rejection check. A modified-JAR
negative check also confirms that the lock fails before compilation/fixture startup.
`git diff --check` passed. Task-owned jars,
Python packages, generated keys/databases and compile output were removed afterward.

Acceptance remaining for parent:

1. No Android build, D8/R8, emulator/device operation, or live activity launch was
   performed. AndroidKeyStore save/load/tamper/key-invalidation and descriptor
   root operations require actual Android acceptance; SDK compilation is not
   runtime evidence. UI rotation recreates the picker and safely cancels current
   I/O instead of restoring a network session. Persistent background sharing is
   intentionally not provided.
2. FTP/SFTP create-only checks and rename destination preflight cannot guarantee
   atomic no-clobber against another writer with these APIs. SMB create-only uses
   O_EXCL; WebDAV uses If-None-Match. SFTP/FTP partial remote uploads may remain on
   failure; the parent must not delete source files until successful completion.
3. Directory delete rejects observed nonempty directories, but WebDAV DELETE is
   recursive at the server and jcifs-ng's delete implementation traverses a
   directory. A concurrent server-side writer between preflight and delete can
   invalidate emptiness. Do not claim transactional/nonrecursive isolation under
   concurrent writers. SFTP/FTP rmdir enforce emptiness on the server.
4. FTP listing depends on server LIST support; FTPS requires system-trusted TLS
   and supports explicit TLS only. SFTP currently authenticates with password
   (private-key/agent authentication is not included). SMB signing is mandatory;
   encryption and dialect/server interoperability beyond the tested local Samba
   are not established. SMB reparse point behavior needs Windows/NAS acceptance.
5. WebDAV XML is bounded to 2 MiB/20000 responses and rejects DTD/entity markup,
   external entities, foreign-authority hrefs and general redirect responses (an exact same-origin
   PROPFIND trailing-slash redirect is accepted). HTTPS uses
   platform trust; arbitrary HTTP hosts need parent network-policy integration.
   Cancellation can be immediate for sockets/HTTP, while a blocked SMB operation
   may take its configured socket/response timeout. DNS/OS scheduling is outside
   the client's strict deadline guarantees.
