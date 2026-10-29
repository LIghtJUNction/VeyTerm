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

Editing/signing first creates a separate output. The result dialog offers
**Apply and stay**, **Apply and return**, or **Keep separate copy**. Apply validates
a same-directory staged copy, then atomically renames it over the exact opened
file. It never deletes/truncates the original before that rename. Only successful
Apply marks the opened file modified. Creating a separate copy alone does not
cause the caller to commit an unchanged staged original. Applied unsigned edits
must be explicitly re-signed before installation. A retained separate output is
an intentional user deliverable, not an automatically deleted backup.

## Pinned dependencies and Android packaging

Repositories: existing `google()` and `mavenCentral()`. These are direct library
calls, not shell commands, network services or remote APK processing.

| Coordinate | Use | License / primary source |
| --- | --- | --- |
| `org.smali:dexlib2:2.5.2` | Class/string/member indexes and complete DEX rewriting | BSD-3-Clause + AOSP Apache-2.0, [upstream notice](https://github.com/JesusFreke/smali/blob/v2.5.2/NOTICE) |
| `org.smali:baksmali:2.5.2` | Class and whole-DEX disassembly | Same notice |
| `org.smali:smali:2.5.2` | Actual source assembly | Same notice |
| `org.antlr:antlr-runtime:3.5.2` | Generated Smali parser runtime | BSD-3-Clause, [source notice](https://github.com/antlr/antlr3/blob/3.5.2/runtime/Java/src/main/java/org/antlr/runtime/ANTLRStringStream.java) |
| `io.github.reandroid:ARSCLib:1.4.0` | Full typed AXML and resource-table JSON/binary codecs | Apache-2.0, [upstream](https://github.com/REAndroid/ARSCLib), [license](https://github.com/REAndroid/ARSCLib/blob/master/LICENSE) |
| `com.android.tools.build:apksig:8.13.2` | Cryptographic APK verification and v1/v2 signing | Apache-2.0, [AOSP signer API](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkSigner.java), [verifier API](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkVerifier.java) |
| existing `androidx.core:core:1.13.1` | Narrow read-only install FileProvider | Apache-2.0, [official FileProvider API](https://developer.android.com/reference/androidx/core/content/FileProvider) |

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

The provider exposes only `cache/apk-install/`, not home, root, or arbitrary
staged/user files. The installer receives a random APK filename and a temporary
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
  byte, including its v2 signing block.
* `ApkSignatures.verify/report(apk)`: apksig's actual verification result, v1/v2/v3
  scheme status, signer X.509 subjects/issuers/serials/validity and SHA-256
  fingerprints, plus verifier and v1/v2 signer errors/warnings.
* `ApkTools.deviceIdentity(context)`: generate/reuse a non-exportable 2048-bit RSA
  AndroidKeyStore identity, alias `veyterm-apk-user-v1`. API 21/22 use
  KeyPairGeneratorSpec; API 23+ use KeyGenParameterSpec. The key permits SHA-1
  for legacy APK v1 signatures as well as SHA-256/SHA-512. No private key is shipped.
  It is lost on app-data deletion/uninstall; the UI states this explicitly.
* `ApkSignatures.importKey(file,type,storePassword,alias,keyPassword)` and
  `sign(input,newOutput,identity)`: PKCS12/BKS, or JKS **only if** a JKS provider
  exists. Android does not normally supply JKS. RSA and EC only. Validate the
  key/cert pair, sign v1+v2, verify both before publishing. Signature scheme v3/v4
  generation and signing-key rotation are not enabled. Passwords stay out of
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
start an emulator, or upload APKs. It creates a fresh PKCS12 test key with local
`keytool`, generates all fixtures, compiles only these five production Java files,
and removes its temporary keys, fixtures, classes and downloads on exit:

```sh
python3 app/src/test/java/com/termux/app/files/apk/run_apk_tests.py --download
# Or supply already-resolved JARs with --dependency-dir /path/to/jars
# --android-jar /path/to/platforms/android-36/android.jar overrides the compile API.
```

The runner uses the existing Gradle cache for apksig/AndroidX and a bounded 32 MiB
explicit dependency-download fallback. It strips ARSCLib's platform stubs for the
production compile. Host tests use Guava 27.1-android and no ANTLR generator/tool.

Validated locally on 2026-10-30: standalone Java 8 compile against SDK 36 and
**73 assertions** covering:

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

The malformed-Smali negative test intentionally prints an assembler diagnostic
before the PASS line. No production/private signing key or binary fixture is
checked in.

## Exact remaining gaps

* Shared Gradle/Manifest registration and parent menu routing are intentionally
  not changed here. Until those are integrated the Activity is not launchable in
  the shipped app. Parent owns the serial full build and resulting APK.
* No device/emulator operations were authorized. AndroidKeyStore (including API
  21/22 fallback and hardware/provider behavior), Android PKCS12/BKS variants,
  PackageManager archive metadata, actual installed base/split extraction,
  FileProvider/unknown-source installer flow, rotation and result delivery still
  require Android acceptance. Host tests do not prove those device workflows.
* No jadx Java decompiler is bundled or advertised. This implementation provides
  Smali editing/reassembly, not editable Java source or Java→DEX roundtrip. Jadx's
  [official project](https://github.com/skylot/jadx) is a separate dependency/plugin
  integration with Android runtime and memory validation work not performed here.
* No split-set/session installer, odex/vdex/compact DEX, cross-DEX reference rename,
  v3/v4 signing/lineage creation, aapt2 source-XML rebuild or graphical resource
  editor. XML preview is read-only; **AXML editing/compilation is typed JSON**.
* Basic native editing includes selection and find-next; it has no undo history.
  Ordinary orientation changes are retained via the recommended Manifest config;
  unsaved text drafts are not recovered after process death or forced recreation.
* Format tests are generated fixtures, not exhaustive tests of obfuscated APKs,
  styled strings, complex bags, vendor extensions or every future DEX version.
  Unsupported parse/compile operations fail and preserve the original. Successful
  reparse/CRC/signature checks do not prove Android resource/runtime semantics.
