# Archive engine integration

Worker scope: `app/src/main/java/com/termux/app/files/archive/**`, matching JVM tests,
and this document. No Android/Gradle/Manifest/UI changes. Base: `be66f5e33a961d5d860af414b40d7da828c3cd23`.

## Exact dependencies

Add these in the integrating checkout (the worker has NOT changed Gradle):

```groovy
implementation 'net.lingala.zip4j:zip4j:2.11.6'
implementation 'org.apache.commons:commons-compress:1.28.0'
implementation 'org.tukaani:xz:1.12'
implementation 'com.github.junrar:junrar:8.1.2'
// Pin the actual transitive versions exercised by the standalone JVM tests:
implementation 'commons-io:commons-io:2.22.0'
implementation 'org.apache.commons:commons-lang3:3.21.0'
implementation 'commons-codec:commons-codec:1.19.0'
implementation 'org.slf4j:slf4j-api:2.0.17'
// Existing app test dependency, also used by the standalone runner:
testImplementation 'junit:junit:4.13.2'
```

| Dependency | License | APIs actually used |
| --- | --- | --- |
| zip4j 2.11.6 | Apache-2.0 | `ZipFile(File,char[])`, `isValidZipFile`, `isSplitArchive`, `getFileHeaders`, `getInputStream(FileHeader)`, `FileHeader` metadata/external Unix attributes; `io.outputstream.ZipOutputStream(OutputStream,char[])`, `putNextEntry(ZipParameters)`, `closeEntry`; `EncryptionMethod.AES/ZIP_STANDARD`, `AesKeyStrength.KEY_STRENGTH_256` |
| Commons Compress 1.28.0 | Apache-2.0 | `TarArchiveInputStream`, `TarArchiveOutputStream` with POSIX long-name/big-number modes, `TarArchiveEntry` header checksum/type inspection; gzip input builder/metadata and output with filename; `SevenZFile.builder().setFile().setPassword().setMaxMemoryLimitKiB().get()`, `getNextEntry`, `read`; `SevenZOutputFile(File[,char[]])`, `SevenZArchiveEntry`, LZMA2 methods and Unix/Windows attributes |
| XZ for Java 1.12 | 0BSD | `XZInputStream(InputStream,int)` with memory limit; `XZOutputStream(OutputStream,LZMA2Options)`, preset 1; same options passed to 7z LZMA2 writer |
| junrar 8.1.2 | UnRAR license (additional restriction; not Apache/MIT) | `ArchiveOptions.builder().password(char[]).maxDictionarySize(long).build()`, `Archive(File,ArchiveOptions)`, `hasBrokenHeaders`, `getHeaderFailures`, `getFileHeaders`, synchronous `extractFile(FileHeader,OutputStream)`; RAR4/RAR5 file mode/redirection/split checks |
| Commons IO 2.22.0 | Apache-2.0 | Transitive stream builders/utility code used by Commons Compress |
| Commons Lang 3.21.0 | Apache-2.0 | Transitive utilities used by Commons Compress |
| Commons Codec 1.19.0 | Apache-2.0 | Commons Compress declared transitive dependency; included in tested runtime classpath |
| SLF4J API 2.0.17 | MIT | junrar logging dependency. No logging provider added; tests print the expected no-provider warning |
| JUnit 4.13.2 / Hamcrest Core 1.3 | EPL-1.0 / BSD-3-Clause | JVM test execution/assertions only |

Upstream references: [zip4j](https://github.com/srikanth-lingala/zip4j),
[Commons Compress APIs](https://commons.apache.org/proper/commons-compress/apidocs/),
[XZ 1.12 license](https://github.com/tukaani-project/xz-java/blob/v1.12/COPYING),
[junrar 8.1.2 license](https://github.com/junrar/junrar/blob/v8.1.2/LICENSE).
The UnRAR license permits handling RAR archives but restricts recreating its proprietary
compression algorithm. Preserve its license and notices; distribution alongside this
repository's GPLv3-only code needs the parent's licensing decision. This document does
not assert license compatibility. No RAR compression algorithm is implemented here.

## Public invocation

All methods are static on `com.termux.app.files.archive.ArchiveTools`, synchronous,
throw `IOException`, and must run off the Android main thread. No Android imports.

```java
ArchiveTools.Options options = new ArchiveTools.Options();
options.password = passwordChars; // optional for reading, required for encrypted writing
options.encryption = ArchiveTools.Encryption.AES_256; // ZIP or 7z
ArchiveTools.Job job = new ArchiveTools.Job((phase, name, bytes, total) -> {
    // Dispatch UI progress yourself; total == -1, bytes counts processed plaintext.
});
List<ArchiveTools.Source> sources = Arrays.asList(
    new ArchiveTools.Source(localFile, "folder/name.txt"));
ArchiveTools.create(new File("out.zip"), ArchiveTools.Format.ZIP, sources, options, job);
List<ArchiveTools.Entry> entries = ArchiveTools.list(archive, options, job);
ArchiveTools.test(archive, options, job);
ArchiveTools.extract(archive, destination, null, options, job); // all members
ArchiveTools.extract(archive, destination, new HashSet<>(Arrays.asList("folder")), options, job);
ArchiveTools.addReplace(archive, sources, options, job);
ArchiveTools.rename(archive, "folder", "new-folder", options, job);
ArchiveTools.delete(archive, new HashSet<>(Arrays.asList("new-folder")), options, job);
// Another thread can call job.cancel(); use a fresh Job for each operation.
```

- Null options/job use defaults. Password arrays are cloned and the internal copy is
  wiped when an operation returns; the caller owns and wipes its own array.
- `Source(File,String)` uses an explicit archive-relative name. Directory sources
  create empty directory entries. Parent expands directory trees into individual
  sources, without following source symlinks. There is no implicit recursive walk.
- `Entry` has `name`, `directory`, `size`, `modified`, `encrypted`. GZIP size is `-1`
  until consumed. List is metadata inspection, not an integrity test or password check.
- Null selection means all, empty selection means none. Directory names select their
  subtree, including implicit directories. Missing selected names are errors. Delete
  rejects empty selections. Rename rejects collisions and supports subtree renames.
- `replaceExisting` defaults to false for create/extract. `addReplace` always replaces
  members of the same name; it cannot replace a directory tree with a file implicitly.
- A password alone does not request encryption for newly supplied sources. Set
  `encryption` to AES_256 or ZIP_STANDARD explicitly. Retained encrypted ZIP members
  remain encrypted on rewrite (AES-256 by default); retained encrypted 7z content
  forces encrypted 7z output. Password change is not exposed as a separate operation.
- Progress phases are `scan`, `write`, `verify`, `extract`; byte counts are cumulative
  within a processing phase, with scan reporting zero. A rewrite verifies its staged
  output before commit. Job cancellation is checked between members and on every
  32 KiB stream block; synchronous codec/header parsing may delay cancellation.
- Do not mutate the same archive or extraction destination concurrently. Parent must
  serialize jobs per archive and keep extraction directories private from concurrent
  untrusted filesystem changes.

## Actual capabilities

| Format | List/test/extract selected/all | Create | Add/replace/delete/rename |
| --- | --- | --- | --- |
| ZIP / APK (content signature detection) | Yes, ZIP/AES integrity verified by full reads | Yes, plain / AES-256 / ZipCrypto | Yes; complete streamed rewrite |
| TAR | Yes; header/size validation, no payload CRC exists | Yes | Yes |
| TAR.GZ | Yes, gzip trailer/CRC included | Yes | Yes |
| TAR.XZ | Yes, XZ checks included | Yes | Yes |
| GZIP | One logical payload; concatenated gzip members decode as one stream | One basename/file only | Replace/rename sole member; adding a second or deleting the last fails |
| 7z | Yes; Commons Compress supported methods and AES reading | LZMA2, optionally AES-256 | Yes; streamed rewrite, including encrypted content |
| RAR | Read-only using junrar; no asynchronous pipe/thread extractor | Explicit error | Explicit error |

Detection reads content signatures, probing at most 512 decompressed bytes for tar
inside gzip/xz. APK is a ZIP container here, not an APK signing implementation.
Split ZIP, multi-volume RAR, self-extracting stubs, sparse TAR, links/hardlinks,
7z anti-items/reparse points and device/FIFO entries are explicitly rejected.
Unsupported codec/password/data errors are propagated, never treated as empty files.
Archive member metadata is normalized on rewrites: names, data, directories and
modification times are carried; arbitrary comments, ownership, permissions, extra
fields, signatures and original compression settings are not preserved. Editing APK
contents invalidates its signature; the APK worker must re-sign it afterwards.

## Safety and failure behavior

- All members are checked, including unselected ones. Reject absolute paths,
  backslashes, drive/colon names, NULs, dot/dot-dot segments, empty components,
  duplicates, and file/child collisions. Check Unix ZIP modes, TAR member type,
  7z Unix/reparse attributes, and RAR Unix/redirection attributes.
- Extraction checks every destination component for symbolic-link resolution,
  including dangling links, before writes and before commit. Existing files are
  never streamed over: each new file is staged beside its target, synced, and renamed.
  A failed extraction may leave already completed members and created directories;
  it does not roll back the entire destination tree. It never recursively deletes it.
- Archive writes stage in the target's parent directory, close and fully read back
  the new archive, sync it, then use same-directory `File.renameTo` (atomic POSIX rename
  on Android/Linux). No delete-first or non-atomic fallback. A failed/cancelled write
  leaves original bytes intact and removes its staging file. No extra rollback copy.
  Directory fsync/power-loss durability is not promised by this Java-only implementation.
- Default limits: 100,000 entries, 4 GiB per member, 16 GiB processed plaintext per
  phase, 64 MiB reader dictionary/memory limit for XZ/7z/RAR. Limits are configurable;
  actual output bytes are checked, not just untrusted declared sizes. The byte limit
  also counts old replacement/deletion data read for integrity during a rewrite.
  List checks entry count/per-member declared size, not every payload or total inflation.
  Codec metadata allocations are controlled by the libraries, so this is not a hard
  bound on total process memory. Writers use LZMA2 preset 1 and stream file contents.

## Reproducible local verification

```sh
python3 app/src/test/java/com/termux/app/files/archive/run-jvm-tests.py
```

The runner verifies SHA-256 of exact Maven Central jars, compiles only this engine and
its tests with `javac --release 8 -Xlint:all,-options`, and executes JUnit with
`java -Xmx192m`. It uses a temporary directory beneath its own test directory and removes
jars/classes on exit. No Gradle, bootstrap, Android build, CI or device access.

Validated locally on OpenJDK 21: **23 JUnit methods passed**, including full roundtrips
and all edit operations for ZIP/TAR/TAR.GZ/TAR.XZ/7z, encrypted ZIP/APK AES and ZipCrypto,
encrypted 7z rewrite, plain gzip replace/rename, generated RAR4 STORE header/payload CRCs
and read-only refusal. Failure cases cover wrong/missing passwords, cancellation during
write/readback/extraction, injected missing-source read, corrupt ZIP payloads and truncated
compressed archives, collisions, missing selections, limits, duplicate members, hostile
ZIP/TAR/RAR names, archive/source/destination symlinks, 7z hostile paths/Unix symlinks, TAR hardlinks/FIFO
members and extraction over its own source archive.

Unverified integration: Android packaging/D8/R8, API 21 runtime, SAF staging, physical
devices, compressed/solid/encrypted RAR corpora and RAR5 corpora. Java 8 bytecode compilation
alone does not establish Android API availability: Commons Compress and junrar reference
`java.nio.file`, `java.nio.file.attribute.FileTime`, `java.time` and Java 8 library APIs.
The parent must verify appropriate core-library/NIO desugaring or guard formats on older
Android releases. JVM tests do not establish Android acceptance. No Gradle or Manifest
changes have been made by this worker.
