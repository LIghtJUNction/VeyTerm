# Native file workspace UI integration

Branch: `work/file-ui-20261030`, based on `be66f5e33a961d5d860af414b40d7da828c3cd23`.

The entry point is `com.termux.app.files.ui.FileManagerActivity`. This worker changes only `files/ui`, its matching tests, the new `values/fm_styles.xml`, and this document. Shared Gradle, Manifest, terminal code, and parent core sources are untouched. No additional dependencies are required.

## Parent integration required

Register the activity and foreground task service. Let Android recreate the activity on rotation; the retained model owns navigation/listing state and process-owned jobs own operations.

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<application ...>
    <activity
        android:name="com.termux.app.files.ui.FileManagerActivity"
        android:exported="false"
        android:launchMode="singleTop"
        android:theme="@style/fm_WorkspaceTheme" />
    <service
        android:name="com.termux.app.files.ui.FileTaskService"
        android:exported="false"
        android:foregroundServiceType="dataSync" />
</application>
```

If the target SDK is raised to 34+, also declare `android.permission.FOREGROUND_SERVICE_DATA_SYNC`. For targets 33+, integrate `POST_NOTIFICATIONS` into the app's real notification permission flow. Current checkout targets 28. Device acceptance must check foreground-service restrictions on the actual supported Android versions; this worker did not run an Android build or device.

The terminal/location entry can launch `new Intent(context, FileManagerActivity.class)`. Optional incoming `file_path` opens that local file's containing directory. Editor/viewer intents remain FileTools' responsibility and use their required `file_path` extra.

Core dependencies are read directly from the parent checkout, never copied or committed here:

- `files.fs.FileStores.create(Context)` returning a configured `RoutingFileAccess` / `FileAccess`.
- `FileLocation`, `FileEntry`, `FileAccess`, `FileOperations`, plus the parent's newly added `FileListing` and `FileSearch`.
- `FileOperations` provides copy/move staging, conflict resolution, batch-rename planning/rollback, removal and checksums. UI does not implement storage adapters or root orchestration.

Provide `com.termux.app.files.FileTools` with these static methods (all called on the main thread):

```java
void open(FileManagerActivity activity, FileEntry entry);
void showMenu(FileManagerActivity activity, List<FileEntry> entries);
void showLocations(FileManagerActivity activity);
void showTools(FileManagerActivity activity);
void onActivityResult(FileManagerActivity activity, int request, int result, Intent data);
void saveState(FileManagerActivity activity, Bundle state);
```

Normal file taps call `open`; long-press and More → Open with / file tools call `showMenu`. Locations and Tools are working top-level buttons delegating to the matching methods. Every activity result is delegated, followed by refreshing both columns. `saveState` is called while the activity saves its own bundle.

Public bridge API:

```java
FileAccess access();
void refreshPanels();
FileLocation activeDirectory();
FileLocation otherDirectory();
List<FileEntry> selectedEntries(); // selected entries currently visible in the active pane
void navigate(FileLocation directory); // navigate active column; call on main thread
void runOperation(String title, FileWork work); // call on main thread
interface FileWork {
    String run(FileOperations.Monitor monitor) throws Exception;
}
```

`FileWork.run` runs on the serial file worker after foreground-service startup succeeds. It must not touch views, show dialogs, start an Activity directly, or retain an Activity/View. Capture `FileAccess`, locations and immutable request values; return actual result details, throw real errors, check `monitor.checkCancelled()` in loops, and publish progress. Tools needing a follow-up UI should preserve their own request/result state through rotation. A second job is rejected with a visible explanation while another runs. No success is synthesized after an exception.

The shared FileAccess can receive two listing-reader calls alongside one serial operation. Backends with a non-thread-safe transport must serialize their own connection access; registration/connection lifecycle must also be safe while readers exist.

## Implemented workflows

- Two equal-width native columns remain side by side in portrait and landscape. Warm existing `vey_*` colors, flat actions, independent path/history/back/forward/up, clickable breadcrumbs and explicit path/URI entry; active-column indicator and explicit source → destination label.
- Per-column folder-first name/size/date/extension sorting, descending order, hidden-file toggle, case-insensitive literal or regex name filtering. Sync preserves the active source; Swap exchanges both complete pane states and safely reloads in-flight lists.
- Checkbox/long-press selection; all visible/invert/clear/same extension; Shift+click and range-to-row. Hidden/filtered selections are retained internally but excluded from `selectedEntries()` and operations until visible again.
- Copy/move always target the other column. Conflicts expose Replace / Keep both / Skip / Cancel and Apply to all. Destination and selected sources are captured before dispatch. Moves and deletes have confirmation, cancellation explains partial completion, and core errors/recovery warnings remain visible.
- Create folder/empty file, single rename and regex/numbered (`{n}`, `$1`) batch rename. The worker obtains a validated plan, displays the actual old → new names, and requires separate confirmation before dispatching `batchRename`.
- Properties include actual stat, URI/path, size, modification, permissions and link target. Chmod accepts validated octal mode, reports backend failures, and never recurses implicitly. SHA-256/SHA-512/SHA-1/MD5/CRC32 checksums use core streaming operations.
- Recursive case-sensitive name or UTF-8 line-content search, with literal/regex options and explicit read/decoding error summary. Searches include hidden folders, do not follow directory links, and install results only if the original column and navigation revision still match. Refresh exits search. Parent FileSearch defines streaming/line limits; UI does not silently treat read failures as an empty directory.
- Named bookmarks are persisted asynchronously in private preferences. Reads, commit failures and malformed saved data are surfaced; malformed bookmarks are not overwritten with an empty array.
- Listings and parent lookups run on a retained model's executor with revision checks to reject stale results. Jobs survive rotation/backgrounding and show progress/cancellation in the UI and a foreground notification, including a route back to a pending conflict. Notification reopening preserves editor activities instead of clearing them from the stack.
- Pane history, location tokens/SAF ancestry, selection, sort/filter/hidden and scroll are bundled for process recreation; the retained model preserves them through rotation. Process death does **not** resume or replay mutations. A persisted task marker produces an explicit interrupted-task warning, leaving completed/recovery files for inspection.
- Keyboard: Tab switches columns outside text fields; Alt+Left/Right/Up navigate; Ctrl+L path; Ctrl+F filter; Ctrl+A select visible; Space toggles a focused row; Shift+Space/click selects a range; F2 preview rename, F5 copy, F6 move, F7 folder, F8/Delete confirm delete, Escape clears selection. Controls and per-row checkboxes have accessible descriptions; rows reuse native view holders.

## Validation and remaining acceptance

Local checks completed:

- Standalone Java 8 compile of all five UI classes against the installed Android 36 `android.jar`, actual parent `FileAccess/FileEntry/FileLocation/FileListing/FileOperations/FileSearch` source, and temporary **signature-only** FileStores/FileTools/R stubs. This checks Java/API linkage to the advertised bridge, not parent integration or an APK.
- JUnit 4 `PaneStateTest`: 11 tests for independent/bounded history, SAF ancestry token round-trip, folder-first sorting in all directions, literal/regex/leading-symbol filters, range anchors including contextual selection, visible-only all/invert/hidden semantics, same-extension selection, and pruning stale selection on reload.
- XML parse and `git diff --check`. No Gradle build, bootstrap download, emulator/device, CI or push.

Re-run the pure tests without an Android build, from this worktree (adjust `fm_parent` only if the parent checkout moved):

```sh
fm_parent=/home/lightjunction/Documents/GITHUB/VeyTerm
fm_test_dir=$(mktemp -d /tmp/veyterm-pane-tests.XXXXXX)
fm_junit=$(rg --files "$HOME/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2" -g '*.jar' | head -n 1)
fm_hamcrest=$(rg --files "$HOME/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3" -g '*.jar' | head -n 1)
javac --release 8 -classpath "$fm_junit" -d "$fm_test_dir" \
  "$fm_parent/app/src/main/java/com/termux/app/files/fs/FileAccess.java" \
  "$fm_parent/app/src/main/java/com/termux/app/files/fs/FileEntry.java" \
  "$fm_parent/app/src/main/java/com/termux/app/files/fs/FileLocation.java" \
  "$fm_parent/app/src/main/java/com/termux/app/files/fs/FileListing.java" \
  app/src/main/java/com/termux/app/files/ui/PaneState.java \
  app/src/test/java/com/termux/app/files/ui/PaneStateTest.java
java -classpath "$fm_test_dir:$fm_junit:$fm_hamcrest" org.junit.runner.JUnitCore com.termux.app.files.ui.PaneStateTest
rm -r -- "$fm_test_dir"
```

Parent acceptance still required: actual Manifest/resource compilation and callback linkage; portrait/landscape at small widths and large font sizes; row taps and hardware keyboard/TalkBack; rotation during list/search/conflict/copy and rename preview; Home/background notification open/cancel; process kill/recovery marker; real local/SAF/remote read/write, denied/revoked storage, conflict apply-all, partial failures and cancellation. A cancelled job may leave completed changes; it does not claim transactional rollback of an entire multi-file batch.

Known limits: one serial foreground job per app process; no durable resume/queue; unsubmitted input forms must be reopened after rotation; a completed rename preview is retained and re-shown across rotation but must be recomputed after process death, with a fresh confirmation; search results themselves are retained across rotation but reconstructed as the ordinary directory listing after process death; listing/search results currently materialize the complete returned list in memory; operation labels are English and have not been localized. None of these are represented as completed device acceptance.
