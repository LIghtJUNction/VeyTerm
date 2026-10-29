# File workspace device smoke instrumentation

`com.termux.app.files.FileWorkspaceSmoke` is a platform `android.app.Instrumentation`
runner. It uses the target app's production filesystem/archive classes, real Android
`Os`/`ParcelFileDescriptor`/`ContentResolver` APIs, and a separate test APK provider.
There are no AndroidX, JUnit, Robolectric, or new test dependencies.

## Integration and execution (parent owner)

This change owns only `app/src/androidTest/**` and this document. Gradle, the main
manifest, full builds, installation, and the dedicated device belong to the parent.
Integrate the parent filesystem sources before building: they were untracked in the
parent checkout and are intentionally not copied into this worker's worktree.

The parent must configure `android.defaultConfig.testInstrumentationRunner` to
`com.termux.app.files.FileWorkspaceSmoke`; the test manifest also declares that
runner with `targetPackage="com.veyterm"`. The default test application ID is
`com.veyterm.test`. If the parent overrides it, use the installed instrumentation
component's package in the commands below. Preserve a **different test UID**:
do not add the target's `sharedUserId` to the test APK.

The test manifest registers both providers exclusively in the test APK:

* Documents authority: `com.veyterm.fileworkspacesmoke.test.documents.20261030`.
* Control authority: the same authority plus `.control`.
* Both run in the test APK's `:file_workspace_fixture` process. The document
  provider is exported, grants URIs, and requires `MANAGE_DOCUMENTS`, as required
  by the platform. The control provider has no file endpoints; it accepts only
  `begin`/`end` for canonical UUID sessions, and only from the installed
  `com.veyterm` UID.

The control provider creates its own private cache fixture and issues a read/write
prefix grant for **only that session's tree** to the target UID. Tests then use
`DocumentFileAccess.tree(Uri)` and the target's resolver. Creating a Context for
the test package does not impersonate its UID. The runner asserts that provider
PID and UID differ from its own; a shared-UID or in-process substitute fails.
The provider contract and required permissions are documented in the
[Android DocumentsProvider reference](https://developer.android.com/reference/android/provider/DocumentsProvider).

Parent-only sequence, after the normal space check and dependency integration
(Commons Compress 1.28.0, zip4j 2.11.6, XZ 1.12, the selected RAR backend, and
NIO-capable core-library desugaring):

```sh
./gradlew :app:assembleDebugAndroidTest
# Install the parent's compatible debug target APK and generated androidTest APK.
# Use the exact dedicated device supplied by the parent; never substitute an attached device.
SERIAL='emulator-5570'
adb -s "$SERIAL" shell am instrument -w \
  com.veyterm.test/com.termux.app.files.FileWorkspaceSmoke
adb -s "$SERIAL" shell run-as com.veyterm \
  cat cache/file-workspace-smoke-last-report.txt
```

The build task and device commands above were **not run by this worker**. Do not
use `connectedAndroidTest`, which can select other attached devices. A supervisor
may put a bounded timeout around `am instrument`; a hang, killed process, missing
final bundle, provider grant failure, or unsupported codec is a failure requiring
investigation, never a skipped pass. Abrupt termination cannot execute Java
`finally` blocks; inspect only that run's UUID cache/fixture before retrying.

## Acceptance and report

The final Bundle contains `report` and `stream` (textual assertion results),
`report_path`, `assertions`, `passed`, `failed`, and `smoke_status` (0 pass, 1 fail).
The instrumentation finish code is **-1 (`Activity.RESULT_OK`) for pass** and
**0 (`Activity.RESULT_CANCELED`) for fail**. Check both the final code and
`smoke_status=0`/`failed=0`; adb process exit alone does not establish success.
Each failed case includes the exception and a bounded stack trace. Cases continue
after failures, so the report exposes independent failures in one run.

The runner writes one small replacement report at target
`cache/file-workspace-smoke-last-report.txt`. All payloads, archive members,
staging leases, and scratch files live under a new target
`cache/file-workspace-smoke-<UUID>`. A `ContextWrapper` changes only the cache root
while retaining the real target resolver and Android services. Cleanup recursively
removes only that run's root, without following symlinks. The provider's `end`
revokes the tree grant and removes only its matching UUID fixture and, if empty,
its fixture base. Cleanup failures fail the run. Existing `cache/file-workspace`,
existing archive caches, HOME, bootstrap, and user documents are never enumerated
or changed by these tests. `/proc/self/fd` is read only for descriptor counts.

## Behavioral coverage

| Boundary | Assertions |
| --- | --- |
| Real local filesystem | Unicode names and binary/UTF-8 bytes through nested recursive copy and move; empty directories and hidden user files; source survives copy; complete moved subtree disappears; descendant destination rejected. |
| Cancellation/conflicts | Cancel a move after actual byte progress; source bytes remain; incomplete destination/temp is absent. Introduce a real competing destination during copy; winner is preserved, move source survives, and temp is removed. Exclusive write rejects existing files; skipped move retains both versions. |
| Batch rename | Swap two Unicode filenames and verify payloads by name; cancel after the first final rename and verify full rollback with no temporary names. |
| Destructive authorization | A delegating local backend denies the second selected source through `checkDelete`; routed move, remove, and batch rename must reject before modifying the first source or destination. All allowed I/O still delegates to production local storage. |
| Native descriptor ownership | Warm up, then 256 production write/read/close pairs without GC; compare `/proc/self/fd` counts with margin 8 and verify bytes every iteration. Read/replace a fixture FIFO must reject without blocking; it remains a FIFO and rejected opens leak no descriptors. No real device node is opened. |
| ArchiveTools | ZIP, 7z, TAR, TAR.GZIP, TAR.XZ and plain GZIP: detect signature despite unrelated extension, test integrity, list expected members, extract exact bytes, preserve explicit empty directories where supported, reject extraction over existing content. Plain GZIP uses one ASCII-named member; nested/Unicode names are tested in all container formats. Plain standalone XZ is unsupported by the production API; XZ coverage uses TAR.XZ. |
| Virtual archives | For ZIP, 7z, TAR, TAR.GZIP and TAR.XZ: local-to-archive and archive-to-local nested transfers; explicit/empty directories; user `.origin.json` staged edit, restore, save and fresh-session re-open; moves in both directions delete their sources; last file removal preserves explicit parent/empty sibling; recursive virtual deletion leaves a valid archive. |
| Malformed input | Unknown signature and truncated ZIP/7z/GZIP/XZ fail integrity and extraction before output appears; a ZIP containing `../escaped.txt` cannot create output or escape. |
| Real SAF | Actual cross-UID tree grant, query/list/create/open/rename/delete via Binder and ContentResolver; local-to-SAF-to-local Unicode nested roundtrip with empty directories and `.origin.json` user data. |
| SAF staging identity | Unchanged save is a no-op. Save `.origin.json` twice, restoring lease metadata between saves; each replacement has a new document URI, persisted origin follows it, and current content stays readable. Recovery lists the lease; discard removes user data and independent metadata. |
| SAF edit conflict | External and staged changes have identical byte length **and identical provider timestamp**. Commit must reject using content identity, preserving both external source and private edit; the lease remains restorable. The provider deliberately reports stable timestamps to make a metadata-only conflict check insufficient. |
| SAF-backed virtual ZIP | Copy archive to SAF, read virtual Unicode entry, stage/save edit through archive rewrite and SAF replacement; verify container URI changes and a new archive session sees saved bytes. |

Document IDs contain session, per-occupant generation, and relative path. Rename
invalidates the old ID and produces a new ID, including when a replacement is
published back to the same display name. The provider resolves only existing
paths under its fixture base, rejects empty/dot/dotdot/separator names, validates
generations, checks tree ancestry, and exposes no arbitrary filesystem root.
This intentionally exercises the platform's `renameDocument` new-ID behavior.

## Local evidence and remaining acceptance

On 2026-10-30 the two instrumentation Java sources plus ten latest production
filesystem/archive sources compiled with `javac --release 8 -Xlint:all,-options`
against the installed Android 35 `android.jar` and hash-verified archive jars.
Compilation exited 0; warnings were unused try-with-resources handles. Temporary
jars/classes were created under this worker's own `build/device-smoke-symbols-*`
and removed on exit. No Android APIs were executed on the JVM, and no mock-stub
behavior was used as test evidence. `git diff --check` also passed.

Pending with parent: Android manifest merge, D8/desugaring, test APK assembly and
installation, provider permission/visibility behavior, execution on the designated
device, and the actual assertion report. The target app's normal Application
startup also runs under instrumentation; these tests launch no activities.
This is filesystem behavior coverage, not UI/editor-intent/picker acceptance.
It does not test actual remote servers, privileged root/Shizuku services, RAR JNI,
APK package installation, encryption/password UI, device hardware, or performance.
The isolated edit-lease recovery test does not simulate process death. Descriptor
counts are a smoke guard with a stated margin, not a general leak profiler.
