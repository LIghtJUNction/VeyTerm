# File tool output directories and standalone format entry points

## Launch contract

`ApkToolsActivity` and `DecompiledJavaActivity` accept the String Intent extra
`file_output_directory`. Its value must be an absolute, app-writable local
directory: the other local pane when writable, otherwise VeyTerm Home
(`getFilesDir()/home`). `FileTools` supplies it for non-local staged sources and retains the lease until
the child Activity returns. Source edits write back through the storage adapter;
new exports remain in the selected local destination.

When present, the extra changes the parent directory of every user-facing new
output while retaining the existing output basename. When absent, borrowed local
inputs keep their previous defaults. Users can still edit every output path.
Installed-app export with no input defaults to Home even without the extra.

## Default destinations

For a staged source named `demo.apk`, let `D` be the supplied output directory.

| Action | Default destination |
| --- | --- |
| Compile resource/Smali edit, replace/delete entry, assemble DEX into APK | `D/demo-edited.apk` |
| Sign using device or imported key | `D/demo-signed.apk` |
| Detached v4 signature, when requested | `D/demo-signed.apk.idsig` |
| Export APK DEX as Smali | `D/demo.apk-classes.dex-smali/` (or the selected DEX entry name) |
| Extract a ZIP/resource entry | `D/<entry basename>` |
| Export installed base and splits | `D/<package>-apks/` |
| Export complete Java class | `D/<simple class name>.java` |
| Export selected Java text | `D/<simple class name>-selection.txt` |
| Export all Java sources | `D/demo.apk-sources.zip` |
| Recompile standalone `demo.dex` | `D/demo-edited.dex` |
| Export standalone DEX as Smali | `D/demo.dex-smali/` |
| Recompile standalone `resources.arsc` | `D/resources-edited.arsc` |
| Recompile standalone binary `AndroidManifest.xml` | `D/AndroidManifest-edited.xml` |

A standalone binary XML input without an extension gets `-edited.axml` appended.
Without the extra, new standalone format outputs default beside the input;
Java export retains its previous external-app-files/internal-files default.
Installed export with a local input retains its previous external-app-files
fallback. Outputs are reported with their full local paths.

## Directory and collision behavior

The output dialog initially suggests a path without creating anything. On
confirmation, a missing supplied default directory is created with `mkdirs()`
and checked again, including the case where another writer created it first.
A non-directory component, creation failure or unwritable directory raises a
visible error. There is no fallback to a cache or lease directory. Custom output
parents must already exist and be writable.

The Activity preflight refuses existing files, directories and dangling symlinks
using `NOFOLLOW_LINKS`. Signing also checks the `.idsig` destination. Existing
format writers retain their final publication checks; Java export additionally
uses its existing atomic `CREATE_NEW` reservation. Collisions require choosing a
new path; this integration never adds overwrite confirmation or truncation.
APK's existing explicit Apply action remains a separate source-edit operation.
Standalone raw edits offer separate outputs only and do not offer APK Apply.

APK installation staging, imported signing keys, editor drafts, compiler scratch
and decompiler session data remain private. Installation scratch is not a
user-facing export and does not use this extra.

## Standalone DEX / AXML / ARSC

The previous Activity treated every non-package-set source as an APK ZIP despite
`FileTools.open` routing `.dex`, `.arsc` and sniffed binary XML to it. This Activity
now classifies `.dex` and `.arsc`, plus DEX magic and binary XML header
`03 00 08 00`, before enabling the corresponding file workspace.

* DEX connects to actual `ApkFormats.classes`, `members`, `strings`, `smali`,
  `replaceClass`, `disassemble` and `assemble`. Smali edits preserve other classes;
  directory assembly creates a standalone DEX, not an APK ZIP.
* AXML connects to `xmlPreview`, `decodeAxml` and `compileAxml`. Standalone typed
  JSON editing needs no APK resource-symbol context. APK-context source XML
  editing remains in the APK ZIP workflow.
* ARSC connects to `decodeArsc` and `compileArsc` for real typed resource JSON.

Raw reads use the engine's 64 MiB edit bound and cancellation-aware copy. Existing
2 MiB editor text limits still apply. Format parsers reject malformed input;
filename recognition does not bypass validation. Source fingerprints bracket raw
reads and edit compilation. New binary outputs use existing `ApkZip.writeNew`
without calling ZIP rewrite/test on standalone files.

## Recreation and lease result

Both Activities save and restore the source path and `file_output_directory` in
`Bundle`, and keep those values synchronized with the original returned Intent.
They retain all other original Intent extras, including parent lease metadata.
APK also restores its existing `modified` state and redetects the source format
on reopen; raw Smali draft recovery reads the standalone DEX rather than a ZIP
entry. Opening a new borrowed local file from the no-input APK tools screen
clears the prior output-directory hint.

Decompiler exports do not modify the source. Both its initial result and
`finish()` explicitly use `setResult(RESULT_CANCELED, getIntent())`, allowing the
parent to release the original staging lease. APK's existing Apply result
continues to return `RESULT_OK` only after successful source modification;
separate APK/raw outputs leave its result canceled.

## Local verification, 2026-10-30

* Both modified Activities compiled as Java 8 with SDK 36 `android.jar` and
  build-tools 36 `core-lambda-stubs.jar` as the **bootclasspath**, an empty source
  path, the parent's existing debug classes/resource `R.jar`, cached apksig and
  dexlib2, and the parent's Android-filtered ARSCLib JAR. Heap capped at 256 MiB.
  The parent classes, resources and dependency caches were read-only. No Gradle
  task or dependency download was run. Existing deprecated Android API warnings
  remain; compilation passed with the existing resource `R.jar`.
* A temporary host harness invoked the actual compiled Activities' path helpers:
  **72 assertions passed** for staged routing/basenames, absent-extra defaults,
  missing-directory creation, editable custom destinations, existing files and
  directories, dangling symlinks, sidecar collision, invalid paths and invalid
  parent refusal. Tests verify that merely suggesting a path does not create it.
* A second host harness used real DEX/AXML/ARSC fixtures and the actual Activity
  format-detection/read/name helpers: **22 assertions passed**, plus **27 existing
  format assertions** reused from `ApkRoundTripTest`'s DEX/AXML/ARSC portions.
  It edited and compiled each binary format, wrote it to the suggested visible
  directory, reread the changed value/instruction, and verified unchanged source
  bytes. The expected bad-Smali diagnostic belongs to a rejection test.
* `git diff --check` passed. Temporary harnesses, classes and fixtures were
  removed after verification; only these two Activities and this document are
  committed.

These checks establish local compilation, path handling and real format behavior.
They do **not** establish Android UI execution, actual rotation/process recreation,
SAF/remote write-back or lease cleanup on a device, installation, or signing/export
through the running GUI. Those remain parent/device acceptance tasks.
