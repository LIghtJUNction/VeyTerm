# APK worker integration

This worker owns `app/src/main/java/com/termux/app/files/apk/`, matching tests,
`res/xml/apk_file_paths.xml`, `res/raw/apk_third_party_notices.txt`, and this note.
It does not modify shared Gradle, Manifest, file-manager entry points or storage.
No RAR dependency is added.

## Entry and result contract

```java
Intent intent = new Intent(context, com.termux.app.files.apk.ApkToolsActivity.class);
intent.putExtra("file_path", stagedLocalFile.getAbsolutePath());
startActivityForResult(intent, requestCode);
```

The input is an app-readable/writable **local file**, including a caller-staged
SAF/remote/archive entry. The original launch Intent and its `file_path` are
returned unchanged:

* An explicitly applied edit: `setResult(RESULT_OK, getIntent())`.
* No applied edit, or discarded edits: `setResult(RESULT_CANCELED, getIntent())`.
* If an earlier edit was applied, discarding a later edit still returns OK for
  the already changed file. Rotation restores that applied flag.

Editing/signing first creates a separate output. For single-file results the dialog offers
**Apply and stay**, **Apply and return**, or **Keep separate copy**. Apply validates
a same-directory staged copy, checks the opened file SHA-256 against the workspace
fingerprint before and after staging, then atomically renames it over the exact opened
file. It never deletes/truncates the original before that rename. Only successful
Apply marks the opened file modified. Creating a separate copy alone does not
cause the caller to commit an unchanged staged original. Applied unsigned edits
must be explicitly re-signed before installation. A retained separate output is
an intentional user deliverable, not an automatically deleted backup.

For v4, the UI confirms both new paths **before** signing: `output.apk` and
`output.apk.idsig`. Both must verify before publication. This pair is retained as
separate outputs and does not mark the opened file modified. There is no pair
Apply button: two file replacements cannot be atomic. Publication rolls back a
sidecar if normal APK publication fails; process death between the two renames
can still leave an orphan sidecar. The original is never overwritten by signing.
Applying any single-file result is refused if the opened APK already has an
`.idsig`, so Apply cannot silently leave an existing sidecar stale.

## Pinned dependencies and Android packaging

Repositories: existing `google()` and `mavenCentral()`. These are direct library
calls, not shell commands, network services or remote APK processing.

| Coordinate | Use | License / primary source |
| --- | --- | --- |
| `org.smali:dexlib2:2.5.2` | Class/string/member indexes and complete DEX rewriting | BSD-3-Clause + AOSP Apache-2.0, [upstream notice](https://github.com/JesusFreke/smali/blob/v2.5.2/NOTICE) |
| `org.smali:baksmali:2.5.2` | Class and whole-DEX disassembly | Same notice |
| `org.smali:smali:2.5.2` | Actual source assembly | Same notice |
| `org.antlr:antlr-runtime:3.5.2` | Generated Smali parser runtime | BSD-3-Clause, [source notice](https://github.com/antlr/antlr3/blob/3.5.2/runtime/Java/src/main/java/org/antlr/runtime/ANTLRStringStream.java) |
| `io.github.reandroid:ARSCLib:1.4.0` | Typed JSON/binary and resource-aware source XML codecs | Apache-2.0, [upstream](https://github.com/REAndroid/ARSCLib), [license](https://github.com/REAndroid/ARSCLib/blob/master/LICENSE) |
| `com.android.tools.build:apksig:8.13.2` | Cryptographic v1/v2/v3 signing and detached v4 generation/verification | Apache-2.0, [AOSP signer API](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkSigner.java), [verifier API](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkVerifier.java) |
| existing `androidx.core:core:1.13.1` | Legacy narrow read-only FileProvider API (session UI does not need it) | Apache-2.0, [official FileProvider API](https://developer.android.com/reference/androidx/core/content/FileProvider) |

Use Smali's `org.smali:util:2.5.2`, `com.beust:jcommander:1.64`, and Guava's
`27.1-android` / `failureaccess:1.0.1` transitives. The project currently declares
Guava 24.1-jre; ordinary dependency resolution selects the newer Smali Android
variant. The host tests explicitly use 27.1-android. Do not force the older JRE
variant without checking the resulting runtime.

Exclude the unused ANTLR **tool** and StringTemplate; the adapters use only the
generated parser and `antlr-runtime`. The standalone tests pass without the tool,
StringTemplate 3/4, or ANTLR 2. This avoids pulling desktop generator code into
an Android runtime:

```groovy
implementation 'org.smali:dexlib2:2.5.2'
implementation 'org.smali:baksmali:2.5.2'
implementation('org.smali:smali:2.5.2') {
    exclude group: 'org.antlr', module: 'antlr'
    exclude group: 'org.antlr', module: 'stringtemplate'
}
implementation 'org.antlr:antlr-runtime:3.5.2'
implementation 'com.android.tools.build:apksig:8.13.2'
```

**ARSCLib's published JAR includes JVM stubs in `android/**` and `org/xmlpull/**`.**
For the Android app use a JAR with those entries removed, relying on the Android
platform's classes instead. Do not add both the original and stripped JAR to the
app. The host runner compiles production adapters against this stripped JAR and
the real SDK API JAR, then uses the original JAR for JVM format tests. A parent
Gradle integration can use this lazy preparation task (or an equivalent artifact
transform):

```groovy
configurations { apkArscSource }
dependencies {
    apkArscSource('io.github.reandroid:ARSCLib:1.4.0') { transitive = false }
}
def apkArscAndroidJar = tasks.register('apkArscAndroidJar', Jar) {
    archiveFileName.set('ARSCLib-1.4.0-android.jar')
    destinationDirectory.set(layout.buildDirectory.dir('apk-tools-deps'))
    from { zipTree(configurations.apkArscSource.singleFile) }
    exclude 'android/**', 'org/xmlpull/**'
    exclude 'META-INF/*.SF', 'META-INF/*.RSA', 'META-INF/*.DSA'
}
dependencies {
    implementation files(apkArscAndroidJar.flatMap { it.archiveFile })
}
```

Java bytecode in ARSCLib 1.4.0 and apksig 8.13.2 is Java 8 (class version 52).
Keep the existing core-library desugaring for minSdk 21. The full Android D8/R8
packaging and runtime API compatibility gate belongs to the parent build.

`apk_third_party_notices.txt` is shipped as a raw resource and available from the
native licenses screen. It includes upstream Smali BSD/Apache notices, Apache 2.0,
ANTLR runtime BSD, and ARSCLib's bundled kXML MIT and JSON public-domain notices.
ARSCLib's own source headers identify its relocated JSON classes as public domain;
kXML retains Stefan Haustein's MIT notice. No proprietary MT implementation is used.

## Manifest recommendations (parent-owned)

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<!-- Inside application -->
<activity
    android:name="com.termux.app.files.apk.ApkToolsActivity"
    android:exported="false"
    android:configChanges="keyboardHidden|orientation|screenSize"
    android:windowSoftInputMode="adjustResize" />
<!-- Required for session installation. No intent-filter and never exported. -->
<receiver
    android:name="com.termux.app.files.apk.ApkInstallStatusReceiver"
    android:enabled="true"
    android:exported="false" />
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.apktools.files"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/apk_file_paths" />
</provider>
```

The **session installer** checks that this receiver exists, is enabled and is not
exported before creating a session. It uses an explicit component, package-specific
action and random URI token, plus the expected session ID persisted in private
preferences. Its PendingIntent is mutable on API 31+ because Android fills in the
status; it is always explicitly addressed. Final status comes only from this
receiver, never from `commit()` returning or the Activity resuming. Terminal
callbacks cannot be replaced by a later pending callback.

The receiver retains the actual confirmation Intent using an Android Parcel in
private preferences (not a lossy Intent URI). Only the visible Activity launches
it. It polls while resumed and offers **Continue Android install confirmation**
after a later relaunch; callbacks received while backgrounded never force a UI.
A status survives normal process death. Force-stop/OS failure can delay or prevent
callbacks: the workspace continues to show unconfirmed, not success. A vendor
confirmation Intent that cannot be marshalled reports that limitation rather than
claiming success. Device acceptance of these OS flows remains required.

The optional **legacy** provider exposes only `cache/apk-install/`, not home, root, or arbitrary
staged/user files. The legacy ACTION_VIEW installer receives a random APK filename and a temporary
read grant. Copies older than 24 hours are pruned on the next install preparation;
active install copies are retained for the external installer. The user handles
Android's per-app unknown-source permission through the native settings screen.

The installed-app list obeys Android package visibility. Current target 28 can
see installed packages on the supported OS rules. If the parent raises target
SDK, decide the appropriate package queries / permitted visibility policy there;
this worker does not silently request broad package visibility. No root required.

## Public engine API (off the main thread)

* `ApkTools.metadata(context, apk)`: Android PackageManager archive metadata,
  requested/declared permissions, activities, receivers, services, providers,
  export/enabled/process flags, SDK/version and instrumentation/features.
* `ApkZip.list/read/extract/test/diff`: real entries, CRC-checked bounded reads and
  extraction, full CRC test, SHA-256 differences on inflated bytes.
* `ApkZip.rewrite(input, newOutput, Map<String,File> replacements, Set<String> deleted)`:
  atomic separate unsigned output. Removes v1 signatures, source stamp and the
  external-to-ZIP v2/v3 signing block. Preserves unrelated META-INF files. Duplicate
  paths, traversal and output clobbers are rejected. Stored resource tables align
  to 4 bytes; stored native libraries align to 16 KiB before signing.
* `ApkZip.apply(candidate, openedFile)`: explicitly commit a CRC-validated APK by
  same-directory atomic rename. Cancellation or validation/rename failure keeps
  the opened file intact. Successful apply preserves a signed candidate byte for
  byte, including its v2/v3 signing block. The overload
  `apply(candidate, openedFile, expectedSha256)` rejects stale workspace/draft
  fingerprints and returns the committed-byte fingerprint without interruptible
  post-commit reads. Checks detect external changes around staging; they do not lock
  unrelated writers across the final check and rename.
* `ApkSignatures.verify/report(apk)`: apksig's actual verification result, v1/v2/v3
  scheme status, signer X.509 subjects/issuers/serials/validity and SHA-256
  fingerprints, plus verifier and per-scheme signer errors/warnings.
  `verify(apk, idsig)` actually supplies the detached v4 file to apksig;
  `report(apk)` also checks an adjacent `.idsig` when present. Verification without
  a sidecar never claims v4.
* `ApkTools.deviceIdentity(context)`: generate/reuse a non-exportable 2048-bit RSA
  AndroidKeyStore identity, alias `veyterm-apk-user-v1`. API 21/22 use
  KeyPairGeneratorSpec; API 23+ use KeyGenParameterSpec. The key permits SHA-1
  for legacy APK v1 signatures as well as SHA-256/SHA-512. No private key is shipped.
  It is lost on app-data deletion/uninstall; the UI states this explicitly.
* `ApkSignatures.importKey(file,type,storePassword,alias,keyPassword)` and
  `sign(input,newOutput,identity)`: PKCS12/BKS, or JKS **only if** a JKS provider
  exists. Android does not normally supply JKS. RSA and EC only. Validate the
  key/cert pair. The compatibility overload signs v1+v2;
  `sign(input, output, identity, enableV3, enableV4)` adds actual v3 and/or v4.
  Every requested scheme is cryptographically verified before publishing. v1/v2
  are additionally checked in API 21–23 / 24–27 ranges, so modern-minSdk APKs are
  not falsely rejected because normal verification skips legacy schemes. v4 uses
  `setV4SignatureOutputFile`, strict `setV4ErrorReportingEnabled(true)`, and
  `ApkVerifier.Builder.setV4SignatureFile`; no missing/failed sidecar fallback.
  Signing-key rotation/lineage creation is not provided. Passwords stay out of
  saved state/logs; char arrays are cleared after import, selected stores deleted.
* `ApkTools.installedApps` / `extractInstalled(context,packageName,newDirectory)`:
  actual PackageManager source APK plus **all** splitSourceDirs, as base.apk and
  split_NNN.apk; CRC-check every copied APK, publish the directory only at success.
* `ApkTools.prepareInstall(context,apk)` then `installIntent(context,prepared)`:
  ACTION_VIEW with APK MIME type and a FileProvider read grant. The system decides
  install success. This is not an install-completed callback.
* `ApkFormats.classes/strings/members/smali(bytes,...)`: dexlib2 + baksmali parsing
  and disassembly of standard DEX. `replaceClass(original,type,source,scratch)`
  actually assembles the edited class and rebuilds the **complete** DEX, retaining
  other classes. Renamed descriptors are rejected because references would need
  rewriting. `disassemble(bytes,newDirectory)` and `assemble(directory,newDex,api)`
  support whole-DEX Smali workflows. Multidex is handled one selected ZIP DEX at
  a time, not concatenated or silently merged.
* `ApkFormats.decodeAxml/compileAxml`, `decodeArsc/compileArsc`: binary → editable
  typed JSON → binary, reparsed before returning. The envelope is
  `{format:"AXML"|"ARSC",schema:1,document:{...upstream ARSCLib JSON...}}`.
  Numeric IDs, value types, configurations and tree/bag contents are handled by
  ARSCLib rather than patched display metadata. `xmlPreview` serializes literal
  attribute names/numeric references without a downloaded framework APK.

* `ApkSourceXml.decode(apk, entry)` / `compile(apk, source)`: readable source
  XML ↔ compiled AXML using ARSCLib 1.4.0 `ResXmlDocument.serialize(serializer,true)` /
  `parse(XmlPullParser)`. Context comes from the APK's own resource table and the
  bundled Android framework. **Keep `frameworks/**` resources when stripping JAR
  platform stubs.** Names compile to actual numeric resource IDs. A strict
  `ResXmlAttribute.encode(true,...)` pass rejects unknown/invalid attributes and
  unresolved references before the default lenient parser. DTD/entities and `@+`
  resource creation are rejected; this replaces AXML against existing symbols and
  does not rebuild resources.arsc. Typed JSON remains alongside source XML.
* `ApkPackageSet.prepare(input, newPrivateDirectory)`: snapshot and cryptographically
  inspect a single APK (also caller-staged without an .apk extension), direct APK
  directory, or nested APKs in APKS/APK-only XAPK.
  Filenames, XAPK JSON and `toc.pb` are not trusted identities. Source fingerprints
  bracket copying; failed preparation removes private copies. Limit 256 APKs /
  4 GiB total; nested APK ZIP entries retain the engine's 512 MiB limit. OBB/APEX
  payloads fail explicitly.
* `ApkPackageSet.validate(selected, sdk, supportedAbis)`: exactly one base, unique
  split names, identical package / 64-bit major+version code / verified current
  signer set, `configForSplit` and `uses-split` closure without cycles,
  `isSplitRequired`, required/offered split types, minimum SDK and a common native
  ABI. Immutable Part fingerprints are rechecked before commit. Selection is
  explicit because APKS may contain independent bases and mutually exclusive
  variants. Validation failure leaves the selection dialog open for correction.
* `ApkInstaller.install(context, parts)`: ordinary `MODE_FULL_INSTALL`
  `PackageInstaller.Session`, base and every selected split written and fsynced;
  abandon on pre-commit errors/cancellation. API 31+ explicitly requests OS user
  action. `status(context)` reads persisted system callbacks;
  `confirmation(context)` restores the OS confirmation Intent. v4 sidecars remain
  separate signing deliverables; this is not incremental/v4 session transport.
  Installing never marks the opened file modified or changes its result contract.
* `ApkDrafts.save/load/discard(privateDirectory, apk, ...)`: atomic private source
  draft storing schema, canonical opened path, APK SHA-256, entry, compiler mode,
  Smali descriptor and UTF-8 text. UI saves after 500 ms idle and on pause/destruction,
  on the same serial worker. Recreation prompts Restore / Discard / Close workspace;
  stale fingerprints cannot restore. Apply/discard removes the draft. No passwords
  or keys enter draft data. Last unflushed typing, process kill during busy work,
  app-data deletion or forced storage removal is not promised recoverable.

Edits, signing, metadata queries, extraction and format work use a serial worker
thread. Cancel interrupts streaming operations; Smali/ARSCLib may finish their
current internal unit before checking interruption, and publication checks
cancellation. The Activity does not start a second operation until completion.
Scratch cleanup runs after active work, not concurrently with the format writer.

Limits: 100,000 ZIP entries; 512 MiB per inflated entry; 4 GiB total inflated APK;
64 MiB binary DEX/resource editor input; 2 MiB native text display/edit. Larger
formats fail explicitly without truncation. ZIP mutation still supports streamed
entry replacement up to its higher entry limit. Output parents must exist and
new-output paths must not exist. Resource JSON compilation is **not aapt2 semantic
validation**: syntactically valid but invalid resource references/package values
can still produce a non-installable APK, as the upstream library documents.

## Concrete local validation

The matching test runner does not call Gradle, download bootstraps, run CI, use adb,
start an emulator, or upload APKs. It creates two fresh PKCS12 test keys with local
`keytool`, generates all fixtures, compiles only these ten production Java files,
and removes its temporary keys, fixtures, classes and downloads on exit:

```sh
python3 app/src/test/java/com/termux/app/files/apk/run_apk_tests.py --download
# Or supply already-resolved JARs with --dependency-dir /path/to/jars
# --android-jar /path/to/platforms/android-36/android.jar overrides the compile API.
```

All test/source-inspection temporary files stay under the worker's own
`build/apk-advanced-check/` on `/home`, never `/tmp`. Java compilation and the JVM
run serially at `-Xmx256m`; keytool uses 128 MiB. No native build is started.
New API calls are isolated in private typed guards (23/24/26/28/31), including the
AndroidKeyStore initializer. No reflection fallback hides missing symbols.

The runner uses the existing Gradle cache for apksig/AndroidX and a bounded 32 MiB
explicit dependency-download fallback. It strips ARSCLib's platform stubs for the
production compile. Host tests use Guava 27.1-android and no ANTLR generator/tool.

Validated locally on 2026-10-30: standalone Java 8 compile against SDK 36 and
**132 assertions** covering:

* Two-class DEX assembly, class/string/member browsing, edited instruction/string
  replacement while retaining the other class, whole-DEX Smali roundtrip,
  descriptor rename/invalid source/compact DEX/symlink workspace rejection.
* Real AXML package, label edit, permission/component retention and numeric
  Android attribute ID preservation through JSON and binary.
* Real ARSC value edit and roundtrip of numeric ID, French locale, typed boolean
  and reference, wrong-format rejection and original bytes retained.
* ZIP replacement/deletion/diff, original/existing-output protection, traversal,
  duplicate paths, editor bounds, CRC corruption, cancellation, old signature
  and source-stamp stripping, resource/native-library data alignment.
* Fresh user PKCS12 import, real v1+v2 signature generation and verification,
  byte-level tamper rejection, re-signing a changed APK, wrong password/alias
  rejection, explicit apply to a caller-staged path, preservation on failed or
  cancelled apply, and intact v2 signatures after apply.
* Actual v3 and detached v4 generation/verification, v4 absence without sidecar,
  tampered/mismatched sidecar, sidecar destination protection, failed pair cleanup,
  modern minSdk 33 plus independent legacy-scheme verification.
* Source XML resource-name decode, exact numeric ID and Android attribute ID
  recompilation, real label editing, unresolved resources/unknown attributes/
  malformed XML/DTD/new-resource rejection and unchanged source APK.
* Signed nested APKS / APK-only XAPK / arbitrarily named directory base+splits,
  duplicate/missing base and split rejection, different package/version/major
  version/current signer, missing dependencies/cycles/required types, unsupported
  ABI/minimum SDK, tampered nested signatures, changed snapshots, OBB rejection
  and failed private-workspace cleanup.
* Private draft roundtrip/update/discard, correct UTF-8 and Smali mode/descriptor,
  cancellation retaining the previous draft, stale draft rejection and apply
  conflict retaining an externally modified opened file.

The malformed-Smali negative test intentionally prints an assembler diagnostic
before the PASS line. No production/private signing key or binary fixture is
checked in.

## Exact remaining gaps / parent acceptance

* Parent owns all Gradle/Manifest/menu integration and the serial full build. Add
  the private receiver snippet above. Dependencies stay pinned at ARSCLib **1.4.0**,
  apksig **8.13.2**, Smali **2.5.2** and ANTLR runtime **3.5.2**; no new coordinate.
  The old FileProvider API remains compatible, while the new session UI does not
  depend on URI grants. This worker has not modified shared integration files.
* The host compile uses actual SDK 36 platform symbols and a stub-stripped
  ARSCLib JAR, not ARSCLib's bundled Android stubs. It is not D8/R8/full packaging,
  an API 21 runtime test or a device installation test. No Gradle, device/emulator,
  CI, push or release was run by this worker.
* Parent device acceptance: AndroidKeyStore v1/v2/v3/v4 with API 21/22 fallback and
  modern providers; imported Android PKCS12/BKS; actual base+feature/config split
  installs, invalid selections, OS unknown-source/confirmation/cancel/failure/
  success callbacks, background/process-death callback persistence, and exact
  original Intent extras / staged-path result delivery. Test edit/relaunch/restore,
  stale draft discard, Apply and separate v4-pair paths on real files. A host
  signature/CRC pass does not establish successful Android installation or launch.
* Explicit APK selection is implemented; automatic bundletool device targeting,
  AAB/APEX/OBB installation, dynamic feature downloading, incremental/v4 session
  transport, signing lineage/rotation and graphical resources editing are absent.
  Android still decides installed-package update compatibility, resource/runtime
  semantics and device-specific restrictions. Current signer equality is strict;
  it does not infer trust from shared signing history.
* Readable XML compilation replaces one existing AXML entry against existing
  resource symbols. It is not a full aapt2 source resource/project rebuild. No
  new resource IDs, vendor framework download, odex/vdex/compact DEX, cross-DEX
  rename or editable Java→DEX pipeline is supplied. Smali/typed JSON remain usable.
* Tests use generated fixtures, not every obfuscated APK, styled string, complex
  bag or vendor extension. Unsupported parse/compile operations fail without
  modifying the opened file. Final Android resource/runtime semantics need device
  validation even after reparse, CRC and signature checks pass.

Primary API references used for this implementation:
[ARSCLib 1.4.0 XML document](https://github.com/REAndroid/ARSCLib/blob/V1.4.0/src/main/java/com/reandroid/arsc/chunk/xml/ResXmlDocument.java),
[attribute encoder](https://github.com/REAndroid/ARSCLib/blob/V1.4.0/src/main/java/com/reandroid/arsc/chunk/xml/ResXmlAttribute.java),
[apksig signer](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkSigner.java),
[apksig verifier](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkVerifier.java),
[Session commit/streams](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session),
[status/confirmation contract](https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_PENDING_USER_ACTION),
[manifest split type definitions](https://android.googlesource.com/platform/frameworks/base/+/master/core/res/res/values/attrs_manifest.xml).
The exact published 1.4.0 and 8.13.2 source JARs were also inspected against the
resolved binary APIs; they are temporary inspection inputs, not shipped copies.
