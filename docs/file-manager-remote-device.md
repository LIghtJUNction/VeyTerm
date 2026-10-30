# Android remote workspace runtime smoke

`RemoteWorkspaceSmoke` is a custom `android.app.Instrumentation`, with no JUnit,
AndroidX runner, or new app dependency. It exercises the actual packaged
`RemoteFiles`, `ConnectionStore`, and parent-owned `RemoteFileAccess`. The orchestration script is `tools/file_manager_remote_smoke.py`; matching target
and test APKs must already be installed.

## Parent integration

Add a **second** instrumentation declaration to the test APK's Manifest, alongside
any existing workspace runner. Do not put this in the shipping application's
Manifest or replace the existing runner:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <instrumentation
        android:name="com.termux.app.files.RemoteWorkspaceSmoke"
        android:targetPackage="com.veyterm"
        android:functionalTest="true"
        android:handleProfiling="false" />
</manifest>
```

The expected test application ID is `com.veyterm.test`; if the parent uses another
ID, pass `--instrumentation TEST_PACKAGE/com.termux.app.files.RemoteWorkspaceSmoke`.
Install matching debuggable target/test APKs signed with the same key. The target
needs INTERNET and must permit loopback cleartext for the explicit WebDAV HTTP
case. The inspected parent's target Manifest already declares INTERNET and
`usesCleartextTraffic="true"`; the worker does not change that policy.
No storage permission or Android CA installation is required.

Production dependency pins remain those in
`app/src/test/java/com/termux/app/files/remote/dependency-pins.json`: JSch 2.28.8,
Commons Net 3.13.0 / IO 2.22.0, jcifs-ng 2.1.10, BC 1.86, OkHttp 4.12.0,
SLF4J API/nop 2.0.17 and the locked Kotlin/Okio transitives. The host checks every
JAR SHA-256 and never downloads or installs packages. Keep one BC JAR and do not
change provider ordering to make this smoke pass: Android's boot classes can
shadow packaged `org.bouncycastle` classes, which is one reason to run on device.

## Host fixture orchestration

Read `docs/file-manager-network-dependencies.md` and the existing `run_local.py`
first. Supply its existing pinned JAR directory and fixture Python interpreter
(or packages under `DEPS/python`). Fixture pins checked here are
`pyftpdlib==2.1.0` and `paramiko==3.5.1`. The existing module also needs
pyOpenSSL, cryptography, impacket's hash helpers, and local `smbd`/`openssl`.
Python 3.12+ additionally needs pyasyncore/pyasynchat; Paramiko needs bcrypt/PyNaCl.
This repository's existing Python fixture uses Paramiko, not asyncssh.

The orchestrator executes the existing `fixtures/protocol_servers.py` with
`runpy`, adapting its pause boundary and inheriting its fixture credentials,
generated SSH keys, and private Samba database/configuration. It reserves eight
passive FTP/FTPS data ports before listeners start. A small stdlib WebDAV fixture
is necessary because the existing WebDAV server is embedded in the JVM checks,
not exposed by the Python module. It supports authenticated PROPFIND, GET,
chunked PUT, MKCOL, MOVE, DELETE, Unicode names, and zero-byte bodies.

Before exposing any ports to Android, the host verifies RSA/Ed25519 SFTP logins,
FTP and verified-TLS FTPS passive listings, signed Samba login/listing, and DAV
chunked binary/Unicode/zero-byte round trips. These are fixture readiness checks,
not Android acceptance. Only ports used by the six endpoints plus their fixed
passive data ports are reversed; the unused no-metadata FTP listener is not.

All generated config, certificates, keys, fixture roots, Samba databases, and
compiler output live inside one fresh `SOURCE_REPO/build/verification/remote-smoke-UUID/`.
Directories are 0700 and files default to 0600. TMPDIR/TMP/TEMP point there.
No real SSH, system user/password database, system Samba config, or remote server
is changed. No Gradle daemon or emulator is launched by this tool.

```sh
# Zero ADB calls: start actual local fixtures, validate plumbing, generate private
# config, and clean it all up. --serial is required even in this mode.
python3 tools/file_manager_remote_smoke.py \
  --serial NO_DEVICE_DRY_RUN --source-repo /absolute/parent/VeyTerm \
  --deps /absolute/pinned-jars --python /absolute/fixture-venv/bin/python \
  --dry-run

# Zero ADB/fixture calls: real SDK bootclasspath compilation, Java 8, fixed 256 MB.
# fs-source may be the parent's untracked source directory; it is read-only.
python3 tools/file_manager_remote_smoke.py \
  --serial NO_DEVICE_COMPILE_ONLY --source-repo /absolute/parent/VeyTerm \
  --fs-source /absolute/parent/VeyTerm/app/src/main/java/com/termux/app/files/fs \
  --deps /absolute/pinned-jars --compile-only \
  --android-jar /opt/android-sdk/platforms/android-36/android.jar \
  --lambda-stubs /opt/android-sdk/build-tools/36.0.0/core-lambda-stubs.jar
```

After the parent builds and installs the APKs, **the parent** runs:

```sh
python3 tools/file_manager_remote_smoke.py \
  --serial 'emulator-5570' \
  --expected-avd VeyTerm_Files_API33_20261030 \
  --source-repo /home/lightjunction/Documents/GITHUB/VeyTerm \
  --deps /absolute/pinned-jars --python /absolute/fixture-venv/bin/python
```

Replace the serial placeholder with the explicit serial of the fresh dedicated
`VeyTerm_Files_API33_20261030` AVD. Every ADB command uses `-s SERIAL`; there is no `adb devices`,
default/current device lookup, or phone selection. Before any mutation the tool
requires the exact `--expected-avd` name, `ro.kernel.qemu=1`, API 33, and successful `run-as`.
`--expected-avd` defaults to `VeyTerm_Files_API33_20261030`; the command also sets
it explicitly. The old `VeyTerm_API_33` contains an active 3.4 GB usr and only
405 MB free; the parent preserves it and stops it before reusing the serial for
the fresh AVD. This tool neither creates/stops AVDs nor cleans old app storage.
It refuses to rebind existing reverse mappings. An accidental phone serial fails
the emulator checks before staging/reversing/instrumenting.

Config is pushed to `/data/local/tmp/remote-smoke-UUID.json`, chmodded 0600,
then shell pipes it into `run-as com.veyterm` with umask 077 to
`cache/remote-smoke-UUID/config.json`. It is not made world-readable. Staging is
removed immediately. The actual runner invocation is:

```sh
adb -s SERIAL shell am instrument -w -r \
  -e config_path /data/user/0/com.veyterm/cache/remote-smoke-UUID/config.json \
  -e timeout_ms 240000 \
  com.veyterm.test/com.termux.app.files.RemoteWorkspaceSmoke
```

Use the orchestrator to keep fixtures/reverses alive; the manual invocation above
only documents its contract. `config_path` must name `config.json` inside the
target's private cache. `timeout_ms` is 30000..600000 (default 240000). A daemon
watchdog calls `finish(1, ...)` on a hang; success finishes with -1. The host has
an additional 30-second deadline and force-stops the dedicated target during
cleanup. It requires six PASS statuses, final empty errors, and result code -1;
ADB's own zero exit status alone is not success.

## Runtime coverage and report

Six endpoints are SFTP_RSA, SFTP_ED25519 (modern-only KEX/key fixture), FTP,
explicit FTPS, signed SMB2/3, and WebDAV HTTP with explicit insecure opt-in.
There are five production enum protocols, with two distinct SFTP endpoints;
this does not claim HTTPS WebDAV or local HTTP sharing-server coverage.

Each endpoint saves/loads a real AES-GCM record using AndroidKeyStore, compares
all credential fields without printing them, checks no plaintext credentials in
the record, rejects modified authentication tags and ciphertext copied to a
different record ID (AAD), and uses the decrypted connection for authenticated
traffic. It performs mkdir/list/stat, binary writes/reads and byte equality,
Unicode/space/#/% paths, create-only rejection, rename, replace/truncate,
zero-byte create/read, file/directory delete, pre-connect/connected cancellation.
The parent adapter independently performs CRUD/zero-byte/Unicode operations by
saved connection ID, covering its separate session and stream-close behavior.

SFTP additionally checks chmod/readback, link creation/lstat/readLink/delete and
mtime. FTP/FTPS issue CHMOD/MFMT only when the fixture advertises them through
SITE HELP/FEAT. SMB checks mtime. Metadata uses UTC within the instrumentation
process and restores the previous timezone; timestamp readback allows LIST's
minute precision. SFTP rejects a syntactically valid mismatched host-key pin.
WebDAV's non-opted-in connection attempts HTTPS against the HTTP-only fixture
and must fail before the opted-in HTTP round trip succeeds.

FTPS first rejects the ephemeral self-signed certificate under unmodified Android
default trust. A test-only TrustManagerFactory provider then trusts only that
certificate's private anchor **and** exact leaf SHA-256; hostname checking remains
enabled in the production FTPS client. A changed expected pin must fail; a correct
pin must complete control and protected passive data operations, including the
adapter. Finally the provider/default algorithm are restored. No trust-all
manager, platform CA change, production TLS seam, or BC replacement is used.
This tests production traffic under a fixture-scoped trust factory, not public
CA interoperability or an application FTPS certificate-pin feature.

Instrumentation output contains fixed endpoint/stage names, assertion counts,
and exception class/source symbols only. No exception messages, causes,
credentials, record JSON, endpoints, or raw library output are emitted. The host
filters `am instrument` output before reporting it. A linkage failure can thus
name `NoSuchMethodError@...` without exposing authentication data; report it to
the parent rather than patching production classes in this scope.

The runner deletes its own encrypted record IDs, and the host retries that exact
UUID's record/config cleanup even after failure. The host removes only reverses
it created and stops only its fixture process groups. It does not clear app data,
delete other saved connections, remove the shared AndroidKeyStore alias, or call
`reverse --remove-all`. Interrupted runs receive the same cleanup on SIGINT/TERM;
SIGKILL/host power loss cannot guarantee cleanup.

## Verification boundary

Worker validation: real Android SDK 35 bootclasspath / Java 8 compilation of all
production remote sources, the four absolute parent fs sources, and this runner;
fixed 256 MB compiler heap. Actual host fixtures passed dry-run startup and
protocol readiness, including the new DAV chunked/Unicode/zero-byte plumbing.
No device was queried or executed; no full Gradle/D8/R8/APK build, CI, or push.
The earlier 2330 JVM assertions are prior host evidence, not Android acceptance.
Argument/guard checks also passed: missing serial is rejected, a mocked wrong AVD
stops before mutation, dry-run succeeds with a nonexistent ADB executable, and
SIGTERM during real fixture startup removes that UUID's processes and files.

Still pending: the parent's test-Manifest packaging and device run; Android BC
bootclasspath/package interactions (especially signed SMB and modern SSH),
AndroidKeyStore provider behavior, actual Android TLS/data-channel behavior,
packaged dependency resolution/desugaring, and lifecycle cleanup on device.
No Android compatibility failure can be confirmed from compilation alone.

For the parent's immediate local run, the validated fixture inputs are retained
at the following path (about 6.6 MB of downloaded files, with JAR symlinks into
the existing Gradle cache). They contain no generated connection credentials or
server keys. This is a build-directory handoff input, not a committed dependency:

```sh
PARENT=/home/lightjunction/Documents/GITHUB/VeyTerm
WORKER="$PARENT/build/worktrees/remote-device"
DEPS="$WORKER/build/verification/remote-smoke-runtime-d7bc26b8a8d84146bdc0fdf9db049257/deps"
python3 "$WORKER/tools/file_manager_remote_smoke.py" \
  --serial 'emulator-5570' --source-repo "$PARENT" \
  --expected-avd VeyTerm_Files_API33_20261030 \
  --deps "$DEPS" --python /usr/bin/python
```

The retained Python fixture packages pin pyftpdlib 2.1.0, Paramiko 3.5.1,
bcrypt 4.3.0, PyNaCl 1.5.0, pyasyncore/pyasynchat 1.0.4. They reuse the existing
host's pyOpenSSL/cryptography/impacket. Remove this UUID runtime-input directory
after the parent finishes device acceptance; all per-run secrets/processes/files
are cleaned automatically by the orchestrator.
