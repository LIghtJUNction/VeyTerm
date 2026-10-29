# Native Java decompiler integration

`com.termux.app.files.decompile.DecompiledJavaActivity` is a real, in-process,
read-only JADX browser for **local APK / standard DEX 035, 037, 038, 039**. It does
not launch a command, compile user code, upload inputs, or synthesize Java from
class metadata. The APK path loads every root `classes.dex`, `classes2.dex`, …
together, so references across DEX files are available to JADX. Resources and
nested/archive asset DEX files are deliberately outside this entry point.

This worker changes only `files/decompile/**`, its matching standalone tests,
`dec_` resources and this document. Shared Gradle, Manifest and menu routing are
parent-owned and **have not been changed**.

## Activity and result contract

```java
Intent intent = new Intent(context,
    com.termux.app.files.decompile.DecompiledJavaActivity.class);
intent.putExtra("file_path", stagedLocalFile.getAbsolutePath());
// Keep the caller's other extras, including staged-source/commit bookkeeping.
startActivityForResult(intent, requestCode);
```

`EXTRA_FILE_PATH` is the public constant for `"file_path"`. The file must already
be locally readable; a SAF/remote/archive caller stages it first. The Activity
always uses `setResult(RESULT_CANCELED, getIntent())`, preserving the original
Intent and extras. It never returns `RESULT_OK`: **exporting a separate Java file
or ZIP does not mutate or commit the caller's staged original**. No launch extras
are removed or replaced.

Parent registration, inside `<application>`:

```xml
<activity
    android:name="com.termux.app.files.decompile.DecompiledJavaActivity"
    android:exported="false"
    android:windowSoftInputMode="adjustResize" />
```

No new provider, permission, service or external intent filter is needed. Normal
Activity recreation is supported: reload the index, restore the committed search,
selected class and source byte offset. A running operation is cancelled on
destruction; exports are not resumed automatically after recreation.

The native `Activity`, `ListView`, `Spinner`, selectable monospace `TextView` and
flat scrolling toolbar follow the existing editor structure. Day mode uses a warm
light palette; night `dec_` colors reference the existing `vey_` editor colors.
All resource files and names introduced here begin with `dec_`.

## Exact pinned graph

Verified on 2026-10-30: Maven Central metadata and the official GitHub latest
release both identify **JADX 1.5.6**, released 2026-07-10, as current stable.
Primary references: [release](https://github.com/skylot/jadx/releases/tag/v1.5.6),
[Maven metadata](https://repo.maven.apache.org/maven2/io/github/skylot/jadx-core/maven-metadata.xml),
[library API guide](https://github.com/skylot/jadx/wiki/Use-jadx-as-a-library).

| Artifact | Role |
| --- | --- |
| `io.github.skylot:jadx-core:1.5.6` | Actual Java decompilation, bundled platform classpath |
| `io.github.skylot:jadx-input-api:1.5.6` | Input/plugin interfaces used by core |
| `io.github.skylot:jadx-zip:1.5.6` | Core's required ZIP API, not a CLI |
| `io.github.skylot:jadx-dex-input:1.5.6` | Real DEX decoding |
| `com.google.code.gson:gson:2.14.0` | Core runtime |
| `com.android.tools.smali:smali-baksmali:3.0.9` | JADX DEX plugin's Smali diagnostics |
| `com.android.tools.smali:smali-dexlib2:3.0.9` | Relocated fork required by those diagnostics |
| `com.android.tools.smali:smali-util:3.0.9` | Relocated fork utility classes |
| **existing** `org.slf4j:slf4j-api:2.0.17` | Shared logging API; no desktop binding |
| **existing** `com.google.guava:guava:27.1-android` | Shared Android Guava, not a JRE variant |
| **existing** `com.google.guava:failureaccess:1.0.1` | Guava runtime |
| **existing** `org.smali:dexlib2:2.5.2` | DEX string/reference index for global search |
| **existing** `org.smali:smali/baksmali/util:2.5.2` | Parent APK tooling; also fixture assembly/coexistence tests |
| **existing** `org.antlr:antlr-runtime:3.5.2`, `com.beust:jcommander:1.64` | Parent's legacy Smali graph |
| **required parent update** `com.android.tools:desugar_jdk_libs_nio:2.1.5` | Core-library/NIO desugaring down to API 21 |

Repositories are `mavenCentral()` and `google()`. The relocated Google Smali
3.0.9 artifacts come from **Google Maven**, not Maven Central.

### Prevent accidental Guava / SLF4J / JVM variant upgrades

The published JADX DEX POM requests Guava **33.6.0-jre**, Google Smali requests
**31.1-android**, and JADX requests SLF4J **2.0.18**. Simply adding the upstream
transitive dependency will override the parent's selected versions. JADX's
published Gradle variant also advertises JVM **11**, while the app compiles Java
sources at **8**. Do not downgrade to an old JADX or change the app's source level
to conceal either issue.

Use the exact, audited JAR graph below (artifact-only resolution intentionally
bypasses the JVM-11 Gradle variant and all unwanted transitives). Dependencies
already supplied by the parent should be declared once in its shared graph:

```groovy
dependencies {
    implementation('io.github.skylot:jadx-core:1.5.6@jar') { transitive = false }
    implementation('io.github.skylot:jadx-input-api:1.5.6@jar') { transitive = false }
    implementation('io.github.skylot:jadx-zip:1.5.6@jar') { transitive = false }
    implementation('io.github.skylot:jadx-dex-input:1.5.6@jar') { transitive = false }
    implementation('com.google.code.gson:gson:2.14.0@jar') { transitive = false }
    implementation('com.android.tools.smali:smali-baksmali:3.0.9@jar') { transitive = false }
    implementation('com.android.tools.smali:smali-dexlib2:3.0.9@jar') { transitive = false }
    implementation('com.android.tools.smali:smali-util:3.0.9@jar') { transitive = false }

    implementation('org.slf4j:slf4j-api') { version { strictly '2.0.17' } }
    implementation('com.google.guava:guava') { version { strictly '27.1-android' } }
    implementation 'com.google.guava:failureaccess:1.0.1'
    // Keep the parent's org.smali 2.5.2 / antlr-runtime-only declarations.
    // Replace the old 1.1.5 coreLibraryDesugaring declaration; do not add both.
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs_nio:2.1.5'
}
```

The standalone runner pins **SHA-256 for every JAR**, scans all 5,550 classes in
the selected graph for duplicates, and checks **all 52 Guava/SLF4J member symbols**
referenced by JADX + its relocated Smali libraries against these older shared
versions, including inherited methods/fields. It also actually disassembles with
both Smali families using Guava 27.1-android. This supports this specific override;
it is not a promise that every future JADX/Guava version is interchangeable.

### Smali coexistence, XML and desktop exclusions

Smali **3.0.9** uses `com.android.tools.smali.*`; legacy **2.5.2** uses `org.jf.*`.
They **do not define duplicate classes**. Keep both namespaces. Excluding the
Google fork while retaining JADX's `SmaliUtils` leaves unresolved references on
error/diagnostic paths. Do not substitute the legacy JAR under the new coordinate.

This graph adds no ANTLR generator, StringTemplate, `jadx-cli`, `jadx-gui`,
`jadx-java-input`, Java-to-DEX converter, Java compiler, AWT or Swing dependency.
The APK worker's existing exclusion of ANTLR's **tool** and StringTemplate remains
appropriate. Its ARSCLib 1.4.0 transformation must still remove bundled
`android/**`, `org/xmlpull/**`, **`org/kxml2/**`** and duplicate XML parser code as
the parent has planned; this worker adds no second parser or ARSCLib copy.
Commons Compress 1.28.0, XZ 1.12 and apksig 8.13.2 remain parent-owned and are not
used by this engine.

JADX **core itself contains optional desktop-facing API signatures** and unused
CFG/nine-patch helpers. They are not separate runtime dependencies. The engine
never calls them: empty file-input list, manually supplied DEX loaders, no
ServiceLoader plugins, no resource decode, no CFG or built-in save/export path.
The host test runs real decoding/export with a classloader that throws if AWT,
Swing or compiler APIs are requested. That passes. For the parent release R8
gate, inspect unreachable optional references; if R8 requests missing desktop
rules, narrowly scope them to the unused `java.awt.**`, `javax.swing.**` APIs.
Do not add desktop JARs or broadly suppress all `jadx.**` warnings.

Preserve JADX's **`clst/core.jcst`** resource in packaging. It is the bundled
class hierarchy needed for accurate type inference. Do not strip all non-class
entries from core. No runtime Maven/plugin download is used.

## Android min21 audit and remaining compatibility gates

* App adapters and Activities compile as Java 8 against actual installed **SDK
  36** symbols. JADX JARs contain **classfile major 55 (Java 11)**. These are two
  different requirements: the parent D8/R8 must accept and desugar Java-11 library
  bytecode while the app's sources remain Java 8. Artifact-only resolution above
  handles dependency selection, not dexing.
* **`desugar_jdk_libs_nio:2.1.5` is required**, replacing 1.1.5. JADX uses
  `File.toPath`, `Path`, `Files`/file visitors and Java-9 collection factories;
  some visitors use `String.repeat`. The matching NIO 2.1.5 min21 API list covers
  these, including `Files.readString/writeString` and the fully rewritten `Path`
  interface. The standalone runner checks the relevant API declarations.
* JADX's unused `FileUtils.streamToByteArray` and ZIP fallback helper contain
  `InputStream.readAllBytes`, which is **not** in that min21 desugar API list.
  This engine uses its own bounded `java.io` loops and does not invoke those
  helpers. Core's bundled classpath parser uses `DataInputStream`, not that helper.
  APK reading is via `java.util.zip.ZipFile`; APK paths are never passed to the
  JADX ZIP fallback. Full packaged min21 runtime testing must confirm this remains
  true after shrinking; do not claim generic compatibility of all upstream APIs.
* Gson 2.14.0 and both Smali families in this graph use Java-8-or-older ordinary
  bytecode; multi-release module descriptors are not Android application classes.
  No host test establishes ART verification, desugared NIO provider behavior,
  low-memory limits, or R8's treatment of optional GUI signatures.
* No D8/R8, complete Gradle/APK build, device/emulator, CI or push was run here.
  Parent must validate min21 **and** a current Android release, theme colors,
  class selection, selection/copy, paging, cancellation/recreation, output path
  access and unchanged caller results in the integrated APK.

## Engine API and practical limits

All session calls run on one owner worker thread. `open`, `decompile`, export and
`close` also guard JADX's process-wide temporary-root pointer with one static
lock, so two Activity instances cannot race upstream load/close/codegen.

* `DecompilerSession.open(input, privateCacheParent, cancellation)` reads the
  original only; creates a unique private `dec-session-*` directory; validates
  DEX length, header, endianness, SHA-1 and Adler32; and checks ZIP DEX CRCs.
  It calls the pinned `DexInputPlugin.loadDexData(List<IDexData>)`,
  `JadxDecompiler.addCustomCodeLoader`, `load` and `getClassesWithInners` APIs.
  It verifies that every indexed class was loaded by JADX. The byte-array API's
  uninitialized checksum option is not relied on.
* `search(Search.CLASSES|STRINGS|REFERENCES, literal, resultOffset, cancellation)`
  returns at most **200** results and `hasMore`. Further pages rescan the immutable
  DEX index, rather than retaining an unbounded hit list. Search is case-insensitive
  literal text, not regex. Class results accept dotted names or DEX descriptors.
  String results cover field string constants and `const-string` instructions in
  **every loaded DEX**. Reference results cover superclass/interfaces, field and
  method signature types, instruction type/field/method/prototype/call-site refs,
  including dual-reference instructions. Hits name the owning class and method /
  instruction code-unit offset where available. This is not a reflective call
  graph; annotations, unused raw string-table entries and runtime-computed names
  are not searched. Hit snippets show at most 400 characters and explicitly direct
  the user to open the class for the full value.
* `decompile(originalClassName, cancellation)` uses
  `JavaClass.getTopParentClass().getCode()` for real Java, writes the **entire**
  source to private cache, and calls `JavaClass.unload()` after writing. Anonymous
  and method inlining are disabled; real inner classes are restored into their
  owning Java unit while remaining searchable by their original DEX names.
  `NoOpCodeCache` prevents retaining every generated Java string. A `Source` handle
  stays current until the next decompile; stale handles are rejected on export.
* `SourcePages.read(sourceFile, utf8ByteOffset)` displays **32 KiB** maximum per
  page without splitting or dropping a UTF-8 character. UI shows `[start,end)` /
  total bytes, Previous/Next, and session error/warning counts. No silent source
  truncation. JADX itself must materialize one complete class internally; paging
  bounds Android text rendering, not that upstream allocation. An OOM is reported
  as failure, never converted into substitute Java.
* `exportSource(source,newPath,cancellation)`,
  `exportSelection(actualSelectedText,newPath,cancellation)`, and
  `exportAll(newZipPath,cancellation,progress)` write complete content. All-source
  ZIP export streams each top-level compilation unit once, preserving packages
  and nested classes; generated names are validated and collisions rejected.
  Original and existing paths are refused via **atomic CREATE_NEW open**, including
  pre-existing symlinks/hardlinks. Output inside the private session scratch tree
  is refused so an exported deliverable cannot be erased on session cleanup.
  Users choose an absolute path in an existing app-writable directory; app external
  files is the suggested default. This is local-path export, not SAF export.
  The new file can be visible while writing; failures/cancellation delete incomplete
  exports, success is shown only after stream close. Process death can leave a
  partial separate export; it cannot damage the input.
* Every operation checks cancellation between reads/classes/methods/instructions.
  JADX's current internal class/load operation may finish before returning.
  The Activity keeps Cancel active while other controls are disabled, waits for
  that work to leave, discards an interrupted session, and queues cleanup behind
  active work on destruction. No unsafe thread stopping or concurrent scratch
  deletion. Private session files are removed on close; abrupt process death can
  leave disposable Android cache files for the OS to reclaim.

Limits: **64 MiB total expanded DEX**, 256 DEX files, 100,000 ZIP entries,
100,000 classes, 1,024 query characters, 200 result rows/page, 32 KiB source
display/page. Exceeding a limit is an explicit failure. Duplicate ZIP DEX names
or class descriptors are rejected. DEX 040/041 containers, compact DEX, ODEX,
VDEX, Java editing/reassembly and APK mutation are outside this adapter.
Decompilation can be imperfect on obfuscation or unsupported constructs: the UI
reports JADX diagnostics and the export retains upstream error/warning comments.
Java source output is not a claim that arbitrary applications recompile.

## Reproducible local validation

```sh
python3 app/src/test/java/com/termux/app/files/decompile/run_decompiler_tests.py --download
# Offline alternative:
python3 app/src/test/java/com/termux/app/files/decompile/run_decompiler_tests.py \
    --dependency-dir /path/to/resolved/jars
```

Requires local JDK (tested with 21), Python 3 and an installed SDK 36 `android.jar`;
`--android-jar` overrides its path. No SDK or bootstrap downloads. Maven fallback
is explicit, SHA-256 pinned and capped at 32 MiB. All downloads, generated fixtures,
classes and exports live under this worktree's `build/dec-validation-*` and are
removed even after a failing test. No `/tmp` data, private keys or binary fixtures
are checked in.

Validated locally on 2026-10-30:

* Three production Java files compile at Java 8 with real SDK 36 symbols and
  resource identifiers generated from the scoped XML (not an Android resource build).
* **155 format/behavior assertions**: actual Smali assembly, DEX and multidex APK
  load, real readable Java, field/instruction strings and call/field/type refs,
  source/selection/whole-ZIP export, bounded class/string/reference result pages,
  exact Unicode page reconstruction including astral characters on boundaries,
  no original changes, stale handles, existing/original/scratch output refusal,
  cancellation cleanup, malformed/checksum/newer-DEX rejection, oversized ZIP DEX
  declaration rejection, duplicate APK DEX names/classes, cache cleanup, and both
  Smali families working together, nested-class selection and complete owning-unit
  export without duplicate inner compilation units.
* The actual exported Java is recompiled with **`javac --release 8`**, then executed
  for **13 checks** of loop/arithmetic, branch, method call, field, Chinese-string,
  try/catch and nested-class semantics. A compiler is used only by the host
  validation runner; none is shipped.
* No desktop/compiler class requested during real decompilation/export; no
  duplicate dependency classes; all selected-version Guava/SLF4J member symbols
  resolve; min21 NIO API-list audit passes.

Remaining real Android gaps are the packaging/runtime/UI gates above. Host tests
do not prove device acceptance or performance on large production/obfuscated APKs.

## Licenses

JADX core/input/ZIP (including bundled platform metadata), Gson and Guava are
Apache-2.0; Google Smali is BSD-3-Clause plus its AOSP notices; SLF4J is MIT.
Retain their notices with the integrated application. Sources:
[JADX license](https://github.com/skylot/jadx/blob/v1.5.6/LICENSE),
[Google Smali notice](https://github.com/google/smali/blob/3.0.9/third_party/NOTICE),
[Gson license](https://github.com/google/gson/blob/gson-parent-2.14.0/LICENSE),
[Guava license](https://github.com/google/guava/blob/v27.1/COPYING),
[SLF4J license](https://www.slf4j.org/license.html).
The parent's legacy Smali/ANTLR notices remain required independently.
