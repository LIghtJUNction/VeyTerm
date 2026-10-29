# Built-in editors: integration contract and validation

## Dependencies and ownership

No new runtime or test dependency. Uses Android framework APIs (minimum API 21),
Java 8, existing `com.termux.R.color.vey_*` colors, and existing JUnit 4.13.2.
No Manifest, Gradle, shared resource, bootstrap, or other module changes.
All application sources are in `app/src/main/java/com/termux/app/files/editor/`.
Focused tests and their offline runner are in the matching `app/src/test/java/` package.

Parent integration must register these **non-exported** activities in the app Manifest:

- `com.termux.app.files.editor.TextEditorActivity`
- `com.termux.app.files.editor.HexEditorActivity`
- `com.termux.app.files.editor.FileViewerActivity`
- `com.termux.app.files.editor.FileCompareActivity`

No resource merging, FileProvider, network permissions, or Gradle dependencies are
needed by these classes. Audio/video codecs depend on the Android installation.
Framework `android.system.Os` handles Unix mode preservation and readlink on API 21+.
The JVM directory-comparison implementation selects Java's readlink reflectively;
it does not impose `java.nio.file` APIs on older Android versions.

## Launch and result API

```java
Intent intent = new Intent(activity, TextEditorActivity.class);
intent.putExtra("file_path", accessibleLocalFile.getAbsolutePath());
// Preserve any staging token, source URI, archive entry, or conflict token here.
intent.putExtra("parent_token", token);
activity.startActivityForResult(intent, EDIT_FILE_REQUEST);

Intent compare = new Intent(activity, FileCompareActivity.class);
compare.putExtra("file_path", left.getAbsolutePath());
compare.putExtra("compare_path", right.getAbsolutePath()); // Optional; prompts if absent.
activity.startActivity(compare);
```

All four screens initialize `RESULT_CANCELED` **with the original `getIntent()`**.
Actual text/hex saves change it to `RESULT_OK`, also **with `getIntent()`**, retaining
all launch extras and the exact original `file_path`. A viewer propagates a successful
nested editor result using the viewer's own original launch Intent. A successful
save stays `RESULT_OK` if the user subsequently discards a later page edit; the
last saved file still needs parent writeback. This result state survives recreation.
Read-only viewing and comparisons remain `RESULT_CANCELED`.

These activities only access the local path. SAF, archive-entry, and remote staging,
writeback, external-backend conflict checks, and cleanup remain the parent's job.
Comparison accepts directories; editors/viewer require readable regular files.
An opened file symlink resolves to its initial canonical target; saves retain the link.

## Implemented behavior

Text editor:

- Editable 128 KiB **byte** pages, explicit byte ranges and page navigation. No
  silent truncation; untouched prefixes and suffixes stream through saves.
- UTF-8 default with strict errors, BOM detection for UTF-8/16/32 with explicit
  endianness. Reopen with a chosen stateless charset. Supported multibyte legacy
  charsets include Shift_JIS, Windows-31j, GBK/GB18030/GB2312, Big5, EUC-JP/KR;
  single-byte encodings work too. Stateful encodings are explicitly rejected.
- Save encoding/BOM and preserve/LF/CRLF/CR settings. Conversions stream the
  **whole file**, including unseen pages, and reject malformed or unrepresentable
  text. Format conversions return to the first page. Invalid data on unseen pages
  is retained byte-for-byte when the encoding and line endings stay unchanged.
- Normalized display with original mixed line-ending styles retained by newline
  order within the edited page; added newlines use the prevailing page style.
  Unedited pages retain their exact original bytes.
- Logical line-number gutter, wrapping, font size, basic source token colors,
  literal/regex search and regex capture replacements, case sensitivity, and
  search highlights. Find/replace-all explicitly affect the **current page**.
  At most 2,000 highlights are drawn; replacement scans every match on the page.
- Global jump-to-line streams through the complete file. Undo/redo are bounded
  to 50 snapshots / 2 Mi characters and intentionally reset between pages.
- Save/discard/cancel confirmation on back, navigation, and reopening; draft,
  original page, fingerprint, and format options survive Activity state restoration.

Hex editor:

- Random-access 4 KiB pages, editable hex bytes, actual edited row offsets,
  decimal/hex offset jump, ASCII view, undo/redo, and full-file streaming byte
  search (hex or UTF-8), including buffer-boundary and overlapping matches.
- Real atomic save with untouched bytes retained. Insertion/deletion is supported
  and requires explicit confirmation when the file length changes.
- Same unsaved-change and conflict protections as text; current draft restores.

Viewer:

- Sampled image preview bounded to about 4 Mi pixels, pinch zoom, pan, reset on
  double tap. The sampling factor is displayed; animated images show a static frame.
- Local audio play/pause/seek and video MediaController. Does not autoplay.
  Playback pauses on leaving the screen and releases on destruction.
- Font preview with editable sample and size control (32 MiB font input limit).
- Selectable, explicitly paged text preview and direct native text/hex editor launch.

Comparison:

- Binary differing byte ranges, actual recursive directory content hashes, ZIP/APK
  uncompressed entry-content hashes, and explicit text mode showing changed line
  positions, EOF, encoding/BOM, and line-ending differences.
- Directory comparisons read symbolic links without following them, including
  dangling and cyclic links; special files fail explicitly.
- ZIP compares logical entry names/content, independent of ordering/compression.
  Duplicate entry names and encrypted/unsupported ZIPs fail explicitly.
  ZIP metadata and APK signing blocks outside entries are not compared.
- Output display is capped at 256 Ki characters with a visible limit notice;
  scans and difference counts continue. Text lines above 1 Mi characters, ZIPs
  above 100,000 entries, and directory nesting above 128 levels fail explicitly.
- Auto mode chooses directory/ZIP/binary; text mode is selected explicitly.
  Text differences compare line positions, rather than producing a minimal edit script.

## Save guarantees and boundaries

`EditorIo.snapshot()` records canonical file, length, modification time, and a full
SHA-256 fingerprint. `saveRange()` and `saveTextPage()` create a same-directory
private temporary file, stream its complete content, fsync, preserve original Unix
permission bits through the Android metadata hook, verify the original fingerprint
again, then use same-directory Unix rename. No delete-before-rename fallback.
Write, encoding, metadata, cancellation, or conflict failures preserve the original
and clean the temporary file. Saves serialize within this process.

As with ordinary filesystem rename, another non-cooperating process can still race
between the final check and rename; this is not a filesystem compare-and-swap.
Directory fsync/power-loss durability, extended attributes, ownership of other users,
and hard-link identity preservation are not promised. Parent backend writeback must
perform its own conflict check against the original SAF/archive/remote source.

Regex work runs on the worker thread with an 8,192-character query cap and explicit
stack-limit errors. The JVM engine additionally checks a two-second character-access
deadline and interruption probes. Android native Matcher copies the input to String,
so that JVM guard does **not** establish a hard Android regex timeout or cancellation
guarantee; pathological native regex remains a runtime limitation. See the
[AOSP Matcher implementation](https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/java/util/regex/Matcher.java).
Native UI work is limited to page text and basic highlighting. Background tasks serialize per screen
and are interrupted on destruction. Directory/ZIP comparison is a read, not a
transactional snapshot of concurrently changing trees or archives.

## Concrete local checks

```sh
app/src/test/java/com/termux/app/files/editor/run-editor-tests.sh
EDITOR_ANDROID_JAR=/opt/android-sdk/platforms/android-36/android.jar \
  app/src/test/java/com/termux/app/files/editor/run-editor-tests.sh
```

The runner uses cached JUnit/Hamcrest, creates and removes its temporary classes
and fixtures inside the scoped tests directory, and never invokes Gradle or downloads.
The optional SDK-symbol check generates temporary color IDs and compiles only this
package. It checks Java signatures, **not** Android resource packaging or APK behavior.

Current local result: **50 JVM tests passed** and all editor activity sources passed
the focused Android-36 symbol compilation. Tests cover exact BOM roundtrips,
UTF-8/16 paging, CRLF boundaries, mixed endings, full-document conversion, charset
errors, malformed unseen data, large-file byte retention, same-size/same-timestamp
external edits, mid-write conflicts, I/O/metadata/cancellation failures, symlink saves,
byte search boundaries, regex/literal replacement, JVM regex deadlines/cancellation, history, and real differences in
text/binary/directory/ZIP inputs including dangling/cyclic links and report limits.

Pending parent acceptance: Manifest registration, normal serialized Android build,
live input/keyboard/gutter/scroll/rotation behavior, result-extra roundtrip and staged
SAF/archive/remote writeback, shared-storage permission semantics, image gestures,
installed media codecs/playback, and device font rendering. No Android build, device,
emulator, CI, bootstrap download, or push was run by this worker.
