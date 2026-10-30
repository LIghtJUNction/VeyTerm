# APK / DEX / Java Android smoke

`com.termux.app.files.ApkWorkspaceSmoke` extends the platform
`android.app.Instrumentation`; it adds no JUnit, AndroidX test, or other dependency.
All fixtures are constructed in the `com.veyterm` target process on Android.
No host test output, pre-generated DEX, host keystore, or placeholder Java is used.

## Registration and execution

The test APK registers this entry under `<manifest>` in
`app/src/androidTest/AndroidManifest.xml`, alongside the existing instrumentation
entries. Keep the existing default runner and providers:

```xml
<instrumentation
    android:name="com.termux.app.files.ApkWorkspaceSmoke"
    android:targetPackage="com.veyterm"
    android:functionalTest="true"
    android:handleProfiling="false" />
```

Use the parent's normal compatible debug target and test APK build. The target
must include the real APK tools, JADX runtime and Android-filtered ARSCLib already
configured by the parent. Do not package ARSCLib's host `android/**` or
`org/xmlpull/**` stubs. The examples assume test application ID `com.veyterm.test`;
if overridden, use the package shown by `pm list instrumentation`. Always choose
the parent's intended device explicitly:

```sh
adb -s "$SERIAL" shell am instrument -w -r \
  -e scenario core \
  com.veyterm.test/com.termux.app.files.ApkWorkspaceSmoke
```

Allow roughly two minutes externally for core execution (there is no runner
watchdog that could finish while a worker is still writing files). A hang, crash,
missing final output, `result=FAIL`, or non-success instrumentation result fails
acceptance. Successful core output has `result=PASS`,
`acceptance=CORE_PASS_INSTALL_NOT_RUN`, and final `INSTRUMENTATION_CODE: -1`.
Stages stream before each operation; failures include stage and stack trace,
including actual runtime crypto/JADX linkage errors rather than skipping them.

## What core actually checks

* Production `ApkFormats.assemble`, `smali`, `replaceClass`, `disassemble`, and
  `assemble` create a two-class DEX, edit a string in real instructions, rebuild
  it, and preserve the neighboring class and original input.
* ARSCLib builds real AXML and ARSC. Production JSON codecs decode, change and
  re-encode them. Assertions inspect the decoded binary label, Android attribute
  ID, resource ID, locale, boolean and reference target. Android PackageManager
  also reads the generated APK package and edited label.
* Android JCA generates a fresh exportable RSA key and self-signed X.509
  certificate. It writes a password-protected PKCS12 file, loads it again,
  compares the private key and certificate, and imports it through
  `ApkSignatures.importKey`. The tiny DER encoder only creates this certificate;
  Android `CertificateFactory`, certificate verification, PKCS12 and production
  APK signing perform the actual operations. There is no `SigningKeys` class in
  this base revision; its real public signing facade is `ApkSignatures`.
* `ApkSignatures.sign(input, output, identity, true, true)` requests v1/v2/v3/v4.
  Explicit platform-range verifiers check v1 at API 21–23 and v2 at 24–27;
  `ApkSignatures.verify(apk, idsig)` must verify v3 and detached v4. Without the
  sidecar, v4 must not be reported. Rewriting a signed payload produces an
  unsigned output that must fail verification. No requested scheme is skipped.
* `DecompilerSession.open` loads both the constructed DEX and signed APK on
  Android. JADX must emit a real `fixture.DeviceSmoke` Java class containing
  `String message()` returning the **edited** string and `int answer()` returning
  `42`, with zero code-generation errors/warnings. The runner also checks search,
  unchanged input bytes, and empty session scratch after close. No Java compiler
  is embedded; DEX is compiled from Smali on-device and JADX generates Java.

The core scenario also round-trips resource-aware source XML and signs twice
with `ApkTools.deviceIdentity`, verifying v4 and persistence of its AndroidKeyStore
certificate. **Run this expanded scenario only in a disposable dedicated AVD**:
it creates or reuses the target app's persistent `veyterm-apk-user-v1` alias.
It never deletes that alias or changes security provider ordering. The separate
PKCS12 fixture remains inside its random cache directory.

## Optional retained APK and installation

For a signed, minimal fixture without submitting an installation:

```sh
adb -s "$SERIAL" shell am instrument -w -r \
  -e scenario core -e keep_apk true \
  com.veyterm.test/com.termux.app.files.ApkWorkspaceSmoke
```

The runner returns exact `run_id`, `fixture_package`, `signed_apk`, `idsig`,
`receipt`, `retained_cache`, and `certificate_sha256` values. The only retained
files are `fixture.apk`, `fixture.apk.idsig`, and `receipt.json`, under
`<target.getCacheDir()>/apk-device-<UUID>/`. Normally this is
`/data/user/0/com.veyterm/cache/apk-device-<UUID>/`; use the **reported** path.
The APK has minSdk 21, targetSdk 36, versionCode 1, no requested permissions,
activities, services, receivers, providers, native libraries or external data.
Its exact package is `com.veyterm.smoke.apkdevice.p<UUID-without-hyphens>`,
reported before any installation. It is an install fixture, without a launcher.

For real PackageInstaller/Receiver acceptance, first allow installation from
VeyTerm through the device's normal settings. Then:

```sh
adb -s "$SERIAL" shell am instrument -w -r \
  -e scenario install \
  com.veyterm.test/com.termux.app.files.ApkWorkspaceSmoke
```

This repeats all core checks, refuses an existing fixture package or another
unfinished production install, calls `ApkPackageSet.prepare` and
`ApkInstaller.install`, then waits up to 20 seconds for the **real system**
callback to `ApkInstallStatusReceiver`. The parent target manifest must already
register that enabled, non-exported production receiver and the normal install
permission. The test manifest needs no fake receiver. No callback is synthesized.

Successful submission has `acceptance=CORE_PASS_INSTALL_PENDING_USER_ACTION`,
`install_status=-1`, `session_id`, and `install_token`, plus the retained paths.
Here `install_status=-1` is PackageInstaller's pending-user-action status;
`INSTRUMENTATION_CODE: -1` is the unrelated instrumentation success convention.
The runner asserts `ApkInstaller.confirmation(target)` reconstructs the system
confirmation Intent. This **does not mean the package is installed**. Unexpected
automatic success, failure, or missing callback fails this manual-confirmation
scenario. If submission fails after a session may have been created, the receipt
and fixture remain available for diagnosis; inspect the reported session or the
matching package's production preference record before retrying.

After instrumentation exits, open the parent's visible APK tools screen:

```sh
adb -s "$SERIAL" shell am start \
  -n com.veyterm/com.termux.app.files.apk.ApkToolsActivity \
  --es file_path '<reported signed_apk>'
```

Tap **Continue Android install confirmation**, then manually approve the actual
Android PackageInstaller dialog. If that Activity is not exported, open the
reported APK using VeyTerm's file manager instead. Do not use `adb install` for
this step: it bypasses the submitted session and its Receiver acceptance.
Confirm the fixture package matches the reported random package.

After the dialog completes, run:

```sh
adb -s "$SERIAL" shell am instrument -w -r \
  -e scenario status -e run_id '<reported UUID>' \
  com.veyterm.test/com.termux.app.files.ApkWorkspaceSmoke
```

Status uses that run's receipt, checks its exact session/package against the
Receiver's persisted record, requires `STATUS_SUCCESS` (0), and independently
asks Android PackageManager for installed version and certificate fingerprint.
Only `acceptance=ANDROID_RECEIVER_SUCCESS_AND_FIXTURE_INSTALLED` establishes
the async install result. The status scenario never submits or retries a session.

## Cleanup boundaries

Core without `keep_apk=true` removes its entire random root on success or failure.
Every scenario that constructs fixtures removes its own `work/` in `finally`,
including the PKCS12/private key, Smali, DEX, unsigned/modified APKs, package-set
copies and JADX scratch. Cleanup refuses symlinks and paths outside that root,
and reports failures. No cache-wide sweep, other app data, user signing alias,
`cache/apk-install` pruning, or automatic uninstall occurs. Retained APK/sidecar/
receipt are intentional acceptance artifacts, not leftover build scratch.

After parent acceptance (or cancellation), uninstall **only** the exact reported
`com.veyterm.smoke.apkdevice.p…` package. Verify the spelling against the receipt:

```sh
adb -s "$SERIAL" uninstall '<reported fixture_package>'
adb -s "$SERIAL" shell run-as com.veyterm rm -r 'cache/apk-device-<reported UUID>'
```

Do not delete `com.veyterm` data, other `apk-device-*` runs, or any package by a
wildcard. For an unfinished session, finish/cancel it in Android's confirmation
UI before removing its artifacts; the runner never abandons other sessions.
Android owns its session staging. `scenario install` also intentionally retains
the production `apk_install_status` record for real Receiver acceptance:
`<token>.session`, `.status`, `.package`, `.message`, optional `.confirmation`, and
`latest`. Do not clear that preferences file. If the parent implements record
cleanup, remove only the reported token's keys after a terminal result; change
`latest` only if it still equals that token (the receipt saves `previous_latest`).
The read-only status scenario leaves those records and artifacts intact.

## Local verification boundary

The permitted local check is javac against SDK 36 `android.jar`, matching
`core-lambda-stubs.jar`, the parent's existing
`app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes`, cached
apksig/dexlib jars and the parent's Android-filtered ARSCLib jar. Compile only
this runner into a disposable private directory and remove it afterwards.
Do not run shared Gradle/native tasks or alter dependency versions in this
worktree. The parent still must build/package/register the test APK and execute
both device scenarios; no Android execution is claimed by this worker.
