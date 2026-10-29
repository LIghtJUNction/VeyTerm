# File workspace data tools

`com.termux.app.files.data.FileDataTools` is a Java 8 utility with no Android or
third-party runtime dependencies. Native dialogs can call these exact methods on
a worker thread; all I/O goes through the supplied `FileAccess`:

```java
public static List<FileLocation> split(
    FileAccess fs, FileLocation source, FileLocation destination,
    String prefix, long partBytes, FileOperations.Monitor monitor) throws IOException;

public static FileLocation merge(
    FileAccess fs, List<FileLocation> orderedSources, FileLocation destination,
    String name, FileOperations.Monitor monitor) throws IOException;

public static String verifyManifest(
    FileAccess fs, FileLocation manifest, FileOperations.Monitor monitor) throws IOException;
```

`destination` is an existing, nonsymlink directory. Inputs must be files, not
directories or symlinks. Output names and prefixes must be single names without
slashes, backslashes or control characters. A null monitor means
`FileOperations.NONE`. Monitor callbacks run on the calling thread; forward
progress to the UI as needed. Check cancellation by throwing
`InterruptedIOException` from `checkCancelled()`.

## Split and merge behavior

- Split uses `prefix.001`, `prefix.002`, etc., with at least three decimal digits.
  `partBytes` must be positive and is a `long`. Parts contain consecutive source
  bytes without headers. An empty input creates one empty `.001` part. Exact
  multiples do not create an extra empty part. The returned list is immutable and
  in merge order. Keep that order instead of lexically sorting names: numbering
  eventually exceeds three digits.
- Merge uses exactly the caller's list order, including any repeated files. Empty
  individual inputs are valid; an empty selection is an error. The result is the
  byte concatenation, without delimiters or text conversion.
- Each output uses `fs.createTemporary(destination)`, writes only that returned
  handle with `write(handle, true)`, closes the stream, checks source metadata and
  cancellation, checks that the intended target is absent, and calls
  `fs.rename(handle, finalName)`. The returned rename location is authoritative:
  provider document IDs can change. Existing final targets are never opened for
  writing or deliberately removed/replaced. A conflict is an error.
- Only this operation's unfinished temporary is deleted. Deletion is attempted
  even if the provider does not expose pending temporaries through `exists()`.
  A cleanup failure is attached as a suppressed exception; split keeps it under
  the reported exception's cause. A provider that refuses deletion can therefore
  leave an unfinished temporary, with that failure available to the caller.
- Merge cancellation/failure discards its unfinished output. Split preserves all
  published parts and throws an `IOException` whose message explicitly includes
  the count and complete URI list of completed outputs. Cancellation retains the
  `InterruptedIOException` type. Do not present the retained list as a successful
  complete split or automatically delete those parts.
- Sources are never deleted or modified. Size, modification time and file type
  are compared after reading. Known size is also compared against bytes actually
  returned by the stream. Merge checks every source again just before publication,
  including sources read earlier. Split checks after every part and again after
  EOF/stream closure; a late failure can leave already published parts, listed in
  its exception. Payload copying uses a 64 KiB buffer, not whole-file byte arrays.

`progress(name, bytes, files)` receives cumulative payload bytes. For split,
`files` counts published parts; for merge it counts consumed, verified sources.
Callbacks are emitted during copying and at completion boundaries.

## Checksum manifests

Format is detected per entry, without depending on the manifest extension:

```text
; SFV: filename followed by its eight-digit CRC32
folder/binary data.bin 352441C2

# GNU: digest, space, then either space (text) or * (binary), then filename
900150983cd24fb0d6963f7d28e17f72  folder/binary data.bin
a9993e364706816aba3e25717850c26c9cd0d89d *folder/binary data.bin
ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  ./folder/binary data.bin
```

These examples are the hashes of the three bytes `abc`. GNU digest lengths select
MD5, SHA-1 or SHA-256. Both GNU markers hash raw bytes; line endings are never
rewritten. Hex is case-insensitive. Mixed supported entries are accepted. Input
is strict UTF-8, with optional initial BOM and LF/CRLF lines; blank lines and lines
beginning with `;` or `#` are ignored. Spaces and Unicode in filenames are
preserved. GNU's leading backslash marker and filename escapes are parsed, but
decoded control characters/backslashes are rejected by the confinement policy.

Every referenced file is relative to the manifest's parent. A single initial
`./` is allowed. Absolute, URI and drive paths, backslashes, control characters,
empty components, `.` components and any `..` component are rejected. Components
are passed separately to `fs.child()`, checked with `fs.contains()`, and inspected
with `fs.stat()`. Symlink files and intermediate symlink directories are rejected,
even if their target would be inside the manifest directory. Paths are never
interpreted by a shell or converted from URIs into local paths. Percent sequences
are ordinary filename characters, not decoded by this utility.

The returned text begins with counts for OK, mismatched, missing and error
entries, followed by per-entry `OK:`, `MISMATCH:`, `MISSING:` or `ERROR:` details.
Mismatch details include algorithm, expected digest and actual digest. Missing
entries and file-read errors allow later entries to be verified. Cancellation
immediately throws `InterruptedIOException`; it never returns a success report.
Malformed entries, unsafe paths, incorrect path types, path-resolution errors,
manifest read failures/changes, or a manifest containing no entries throw
`IOException`. File changes during hashing appear as `ERROR:` entries.

Payload bytes are hashed in 64 KiB chunks; manifest lines are read incrementally
and limited to 65,536 characters. Report details are limited to 262,144 characters
plus a truncation notice, while summary counts include every entry. The result
is a display report, not a machine-readable interchange format. Verification
progress counts payload bytes and processed entries, including missing/error
entries; manifest bytes themselves are not included in progress.

## Backend contract and limits

The shared interfaces are consumed directly; this change does not define/copy
them. Backends must create exclusive operation-owned temporaries, support writing
the returned handle and renaming it within the destination, report missing files
accurately, expose symlink metadata, and implement containment correctly.

There is no atomic no-replace rename or descriptor-relative confined-open API in
`FileAccess`. This layer checks absence immediately before rename and relies on
the backend to reject an existing target at rename time. A backend implemented
with an absence check followed by an overwriting OS rename still has a race with
external writers; strict concurrent no-clobber requires a backend/API guarantee.
Likewise, metadata/confinement checks cannot exclude a malicious concurrent
symlink/path substitution between inspection and opening. These guarantees cannot
be manufactured in a backend-independent utility without stronger shared APIs.

Size/mtime fingerprints detect observable changes, not same-size edits that
preserve timestamps or changes hidden by unavailable/coarse provider metadata.
They do not provide a multi-file snapshot. Cancellation is cooperative between
I/O chunks/path components; it cannot interrupt a blocked provider call. A
provider may stage data or use additional memory/storage internally. Power-loss
durability and atomic rename semantics depend on that provider. BSD digest
syntax, Windows SFV separators, non-UTF-8 manifests and filenames containing
newlines/backslashes are not supported.

## Plain JVM verification

Run from the isolated data worktree. Read the current untracked shared sources
from the parent checkout using `-sourcepath`; do not copy or add them to this
branch. The tests use JUnit 4 already available in the local Gradle cache, without
running Gradle, Android or network services. All fixtures, classes and JVM scratch
files stay under this worktree's `build/file-data-tests`.

```sh
DATA_PARENT=/home/lightjunction/Documents/GITHUB/VeyTerm
DATA_JUNIT=/home/lightjunction/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar
DATA_HAMCREST=/home/lightjunction/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar
mkdir -p build/file-data-tests/classes
javac --release 8 -Xlint:all,-options -cp "$DATA_JUNIT" \
  -d build/file-data-tests/classes \
  -sourcepath "app/src/main/java:app/src/test/java:$DATA_PARENT/app/src/main/java" \
  app/src/test/java/com/termux/app/files/data/FileDataToolsTest.java
java -Djava.io.tmpdir="$PWD/build/file-data-tests" \
  -cp "build/file-data-tests/classes:$DATA_JUNIT:$DATA_HAMCREST" \
  org.junit.runner.JUnitCore com.termux.app.files.data.FileDataToolsTest
```

Local verification on 2026-10-30: `javac --release 8` on OpenJDK 21 and JUnit
4.13.2 passed **40 tests**. The sole `-Xlint` warning comes from the parent
`FileAccess.createFile()` unused try-with-resources variable; data sources compile
without additional warnings.

Fixtures use actual NIO filesystem bytes, plus a provider simulation exposing
opaque non-hierarchical locations and invisible temporaries. Tests cover binary,
empty/exact/remainder splits, ordered merges, cancellation, conflicts, injected
I/O/close/rename/cleanup failures, changed/truncated inputs, manifest known
vectors, mismatch/missing/read-error reports, path escapes/symlinks, malformed
input, short/zero reads, and bounded reports. Local JVM results do not establish
Android/SAF/remote/archive backend or dialog acceptance. No device build, Gradle
task, push or CI is needed for this workstream.
