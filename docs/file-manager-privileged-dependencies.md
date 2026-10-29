# ROOT / Shizuku file backend integration

## Parent API (stable wiring contract)

`com.termux.app.files.privileged.PrivilegedFiles.get(Context)` is an application
singleton. Constructing it, `rootAccess()` / `shizukuAccess()` and navigating Files
never request permission or launch a privileged process.

Call only from an explicit ROOT / Shizuku button on the main thread:

```java
PrivilegedFiles privileged = PrivilegedFiles.get(activity);
privileged.connectRoot(activity, new PrivilegedFiles.Callback() {
    public void connected(int uid) { /* ROOT: verified uid == 0 */ }
    public void failed(String message) { /* display actionable error */ }
});
// Same Callback for privileged.connectShizuku(activity, callback).
FileAccess root = privileged.rootAccess();
FileAccess shell = privileged.shizukuAccess();
FileLocation location = PrivilegedFiles.location("root", "/data/local/tmp");
// or new FileLocation("shizuku:/storage/emulated/0").
// All FileAccess operations run on the parent's I/O executor.
```

`Callback.connected(int uid)` / `failed(String message)` run on the main thread,
once per connection attempt. ROOT requires uid 0; Shizuku accepts actual uid 2000
or 0. Show `Shizuku (shell, UID 2000)` or `Shizuku (root, UID 0)` accordingly.
UID 2000 does not grant other apps' private data; SELinux can deny access even at
UID 0. There is no PRoot or terminal-shell fallback.

`disconnectRoot()`, `disconnectShizuku()` and `disconnect()` run on the main
thread, cancel pending callbacks, close client file streams, remove listeners and
unbind/stop services. Call `disconnect()` when the Files workspace is closed.
Destroying the Activity that initiated a connection also disconnects that backend;
after recreation the user explicitly reconnects. No Activity permission-result
forwarding is needed: the manager owns Shizuku's permission listener.

Both schemes implement `FileAccess`, including `exists`, `contains`, `readLink`,
`symlink`, `permissions`, `chmod`, `setModified` and exclusive creation. Operations
Filesystem operations on a disconnected backend fail without reconnecting or
showing permission UI; path-only `parent` / `child` remain available.
Parent routing must select these backends by scheme before falling back to local
or SAF. Parent copy/delete code must treat `symbolicLink` before `directory` and
must preflight `privileged.requireDeletable(location)` **before recursive
enumeration**, so protected storage roots cannot have their children removed first.
The service also checks resolved aliases and actual `/proc/self/mountinfo` mount
roots. Deletion is one entry at a time, never recursive inside the service.

ROOT launch uses `RootService.bindOrTask` with a private libsu `Shell` that is
closed after the launch task; it does not leave a cached global root shell.
Late connections after cancellation are immediately unbound. Shizuku uses a unique
per-attempt tag so late cleanup cannot terminate a newer connection.

## Required shared-file edits (parent owns these)

Repositories: Maven Central for Shizuku; JitPack for libsu. Dependencies:

```groovy
implementation 'com.github.topjohnwu.libsu:core:6.0.0'
implementation 'com.github.topjohnwu.libsu:service:6.0.0'
implementation 'dev.rikka.shizuku:api:13.1.5'
implementation 'dev.rikka.shizuku:provider:13.1.5'
// android { buildFeatures { aidl true } }
```

Inside `<application>`:

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

The provider artifact merges `moe.shizuku.manager.permission.API_V23` and the
`moe.shizuku.client.V3_SUPPORT` metadata. Verify the merged manifest. The provider
receiving Shizuku's Binder is not a permission request or UserService launch.
Do not export/register either file service as an Android `<service>`: libsu's
`RootService` extends `ContextWrapper`, and Shizuku's UserService is an AIDL Stub,
not `android.app.Service`. Both frameworks instantiate the classes themselves.
No boot receiver, foreground service, automatic permission request or daemon mode.

If shrinking is enabled, retain the reflectively instantiated classes:

```proguard
-keep class com.termux.app.files.privileged.RootFilesService { public <init>(); }
-keep class com.termux.app.files.privileged.ShizukuFilesService { public <init>(android.content.Context); }
```

Minimum Shizuku server version: 13 (Context constructor / non-daemon lifecycle).
The **published api 13.1.5 AAR declares minSdk 24**, while provider 13.1.5 declares
23. This was checked in their actual `AndroidManifest.xml` files, beyond the
general guide's Android 6 requirement. Shizuku connect is guarded at API 24.
The parent app currently declares minSdk 21: integrating these exact artifacts
into one APK therefore requires a platform/dependency decision before the full
build. Do not silently use `tools:overrideLibrary` to bypass that incompatibility.
This worker has not changed the parent's minimum platform. The ROOT backend itself
uses Android API 21+; the combined dependency set declares API 24+.

## Official sources checked 2026-10-30

- [libsu 6.0.0 RootService source](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/ipc/RootService.java): bind/unbind, client lifetime, no daemon category.
- [libsu 6.0.0 example manifest](https://github.com/topjohnwu/libsu/blob/6.0.0/example/src/main/AndroidManifest.xml): no Android service declaration.
- [Shizuku API guide](https://github.com/RikkaApps/Shizuku-API#userservice): UserService, actual UIDs, destroy transaction 16777114 in AIDL.
- [Shizuku implementation](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/Shizuku.java): `newProcess` deprecated; use `bindUserService` / `unbindUserService`, `daemon(false)`.
- [published 13.1.5 metadata](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.pom).
- [published API AAR](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar) and [provider AAR](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/provider/13.1.5/provider-13.1.5.aar): actual minSdk declarations, inspected from the embedded manifests.
- [kernel mountinfo format](https://docs.kernel.org/filesystems/proc.html#proc-pid-mountinfo-information-about-mounts).

## Verification

Run in the integrated checkout:

```sh
python3 app/src/test/java/com/termux/app/files/privileged/verify_privileged.py --typecheck
```

In the isolated worker checkout, add
`--core-source /home/lightjunction/Documents/GITHUB/VeyTerm/app/src/main/java` to
read the parent's uncommitted core interfaces, without changing them.

PASS: 17 JUnit host tests cover URI encoding (spaces, Unicode, shell metacharacters,
CR/LF and literal percent escapes), wrong schemes/authorities, NUL/dot traversal,
UTF-8 length, path navigation, storage-root protection, real host symlink aliases,
kernel UID parsing/privilege rejection and mountinfo escaping/errors. The host
symlink test creates/removes only its own temporary links; no root access is used.

PASS: SDK 36 AIDL generation (min SDK 21) and focused `javac --release 8` against
the actual libsu 6.0.0 and Shizuku 13.1.5 AAR classes, plus the parent's current
`FileAccess`, `FileEntry`, `FileLocation` and `LocalFileAccess`. This checks type
compatibility, including libsu's private launch-shell API. It does not perform an
app manifest merge, resource processing, DEX/APK build or Android execution.
The runner bounds downloads, confines them and compiler outputs to one temporary
subdirectory beneath the owned test directory, and removes it in all exit paths.

Parent still owns the minSdk/dependency decision above, Gradle/manifest/R8 wiring,
scheme routing, recursive-delete preflight and real ROOT/Shizuku acceptance.
Specifically unverified: permission denial/grant/revocation, Activity destruction,
late service callbacks, service death, PFD transfer/large-file I/O, SELinux access,
readonly mounts and Android file operations. No emulator/device, bootstrap, CI,
push or complete Android build was used.

Operational limits are explicit failures: at most 100000 names per directory,
64 metadata entries per Binder page, eight live listing cursors with a two-minute
idle expiry, and 4096 UTF-8 bytes per absolute path. A disappearing file is skipped;
access failures are not turned into empty directories. Streams reject special
files; write uses `O_EXCL` for exclusive create and `O_NOFOLLOW`, then verifies
the opened inode is regular before truncation. `chmod` uses `fchmod` on a
no-follow descriptor. Changing a symlink's timestamp returns an explicit error
rather than changing its target. ROOT/SELinux access failures are always surfaced to the caller.
Rename reuses the parent's `LocalFileAccess` existence-check plus rename, which
is not an atomic no-replace guarantee against other processes racing a target.
