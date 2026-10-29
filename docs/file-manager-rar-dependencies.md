# GPLv3-compatible RAR reader integration

Base commit: `01f5fcdaa3ae06a5223e7a53818ef76dfc0746d9` (`feat: implement safe built-in archive engine [skip ci]`).
This follow-up changes only RAR reading, its dependency integration, and tests.
All public `ArchiveTools` signatures and the ZIP/TAR/GZIP/XZ/7z implementations remain
unchanged. RAR is still read-only: create/addReplace/delete/rename throw IOException
and retain original bytes. No JNI library is loaded until a RAR operation is used;
a missing/wrong-ABI `.so` becomes an actionable IOException.

## Dependencies and reproducible source

Remove `com.github.junrar:junrar:8.1.2` from the integrating app's Gradle dependencies.
Remove `org.slf4j:slf4j-api:2.0.17` if nothing else needs it (the archive engine no
longer needs it). Keep every other archive dependency unchanged. The standalone
runner has already removed both jars. No Java RAR replacement dependency is added.
The UnRAR algorithm-use restriction in
https://raw.githubusercontent.com/junrar/junrar/v8.1.2/LICENSE is not used in this
combined GPLv3-only app.

Native source: [libarchive 3.8.10](https://github.com/libarchive/libarchive/releases/tag/v3.8.10),
release tarball https://www.libarchive.org/downloads/libarchive-3.8.10.tar.xz,
SHA-256 `56cad765459d5ee25a3b761f90c47d99379d28297fbfb72d908880d6b875fb96`.
`app/src/main/cpp/veyterm-archive/prepare.py` verifies this exact hash, extracts only
21 required C sources plus headers/COPYING, and applies checked policy/CRC patches.
No downloaded native binaries, full vendor tree, or upstream build program is
committed. Offline preparation accepts the identical hash-checked release tarball.
The Android source is therefore reproducibly available before ndk-build; builds do
not fetch implicitly. See the adjacent BUILD.md and patches.md for commands and fixes.

Licenses: libarchive core/RAR readers BSD-2-Clause, PPMd7 public-domain notices,
BLAKE2 reference implementation CC0-1.0 (selected from upstream's offered licenses),
and system zlib's permissive zlib license. These avoid the UnRAR additional use
restriction. Original source notices remain in fetched sources; LICENSE.libarchive
and full LICENSE.CC0 are retained beside the adapter. The JNI/allocator/Java glue
is GPL-3.0-only. The parent must include these notices with its distributed app.

## Android integration owned by the parent

1. Run `python3 app/src/main/cpp/veyterm-archive/prepare.py` before native configuration.
2. Include `app/src/main/cpp/veyterm-archive/Android.mk` from the existing Android.mk
   using the exact snippet in BUILD.md. It builds `veyterm_archive` plus its private
   static RAR core; Java calls `System.loadLibrary("veyterm_archive")`.
3. Preserve the existing four ABI filters and package the generated shared module.
   Add it to explicitly restricted native targets, if any. No extra permission or
   Manifest entry is required. Do not add a prebuilt `.so` to source control.
4. Keep `NativeRar` and `NativeRar$Member` in R8 using the BUILD.md rules.
5. Remove the junrar dependency; retain the other format dependencies/desugaring.

The parent Android.mk/Gradle/Manifest/ProGuard files were not changed here.

## API behavior and limits

`ArchiveTools.detect/list/test/extract` keep their existing signatures. `Entry`
still exposes name/directory/size/modified/encrypted; no public member type changes.
The internal JNI member additionally carries link and special-type flags. All link,
Unix special-type, reparse and RAR5 redirection entries are rejected, even if not
selected. Names pass the existing unsafe-name/duplicate/file-parent collision checks.
Raw RAR4 backslashes are rejected rather than silently translated to slashes.
RAR4 Unicode and RAR5 UTF-8 are decoded without JNI modified-UTF-8 corruption; NUL
and malformed names are rejected. Unicode Windows filenames with backslash path
separators are consequently rejected under the existing strict-name contract.

Reads transfer at most 32 KiB of plaintext per Java/native call, without retaining
the archive or a whole plaintext member. Libarchive may buffer a compressed
header/block and its dictionary; all library heap allocations (including accounting
headers), plus the reader/input buffer, share the `Options.maxMemoryKiB` budget.
Reallocation charges both the old and replacement allocations before allocating;
this may reject a tight-budget resize that would fit after freeing the old block.
Exhaustion throws IOException and disposes all owned native allocations, including
partially initialized codec state. The budget is not a cap on the JVM, thread stacks,
libc's allocator overhead/global locale state, or Java's bounded transfer/metadata objects. Large
RAR5 dictionaries may need more than the default 64 MiB because libarchive keeps
both a window and filtered window; exceeding the configured limit is an error.

Cancellation runs before each member/transfer and inside native input callbacks,
including callbacks used while skipping solid members. Java exceptions from those
callbacks propagate unchanged. A synchronous codec block with already-buffered
input can delay cancellation. The existing progress callback/meter, declared-size,
actual-plaintext-size, count limits and atomic extraction staging remain in use.
Listing remains metadata inspection, not an integrity test. Advancing past solid
members may decompress them internally; skipped members are not promised to have
payload checksums verified. `test` and extracted members consume data fully and
check header/payload CRCs (and BLAKE2sp when present). RAR5 files without a checksum
cannot acquire a checksum guarantee from this adapter. Originals are never opened
for writing. A failed extraction may retain completed earlier members as before.

## Verified capability and exclusions

| RAR type | Result |
| --- | --- |
| Single-volume RAR4 STORE, normal/best compressed first members | List/test/extract; exact contents and CRC failures checked |
| RAR4 multi-LZSS-block member, 20,131,111 plaintext bytes | Streamed extraction, progress, cancellation and native allocation budget checked |
| RAR5 STORE/compressed, multiple members, solid | List/test/extract all and selected final member; known contents checked |
| RAR5 BLAKE2sp | Valid fixture passes; changed payload fails |
| RAR5 Unicode supplementary characters | Exact names/content; Unicode hardlink/symlink corpus rejected |
| Data-encrypted RAR4/RAR5 | Clear metadata may list encrypted flags; test/extract encrypted content explicitly fail, regardless of supplied password |
| Header-encrypted RAR4/RAR5 | Explicit libarchive unsupported-encryption error; names unavailable |
| RAR4 solid | Unsupported by libarchive, explicit error; RAR5 solid is supported |
| Multi-volume / split RAR4/RAR5 | Explicit error, including first volume; no automatic file discovery |
| Symlinks, hardlinks, junctions, reparse, file-copy/unknown redirections | Rejected before destination creation, including unselected entries |
| RAR4 VM/filter programs or codec variants not supported by libarchive | Propagated codec errors; full variant coverage is not established |
| RAR4 legacy non-Unicode codepages requiring iconv | No iconv is bundled; undecodable names fail explicitly |
| RAR5 unknown unpacked sizes / dictionaries beyond libarchive's supported range | Explicit libarchive error |
| Self-extracting executables, RAR creation/editing | Unsupported; content detection requires RAR signature at byte zero |

## Exact verification

Run from the repository root:

```sh
python3 app/src/test/java/com/termux/app/files/archive/run-jvm-tests.py
```

The runner uses OpenJDK 21, `javac --release 8 -Xlint:all,-options`,
`java -Xmx192m -Xcheck:jni`, pinned Maven jars, a freshly compiled pinned-source JNI
reader, and 23 committed text corpus fixtures whose SHA-256 is checked before
uudecoding. It removes downloads, prepared source, classes and native outputs on
exit. Dependencies: Python >= 3.9, CMake, C compiler, JDK, zlib development headers.
**Local result (2026-10-30): 40 archive tests plus 1 missing-library test passed**
(41 total; the original 23-method suite remains included).
A separate fresh JVM deliberately omits the native library and verifies IOException
plus working ZIP operations. Corpus provenance/hashes are under matching tests'
`fixtures/README.md` and SHA256SUMS.

Corpus stems (all prefixed `test_read_format_`, suffixed `.rar.uu`):

```text
rar3_lowdist_reset
rar_multi_lzss_blocks
rar_compress_normal
rar_compress_best
rar4_encrypted
rar4_encrypted_filenames
rar4_solid_encrypted
rar_multivolume.part0001
rar5_stored
rar5_compressed
rar5_multiple_files
rar5_multiple_files_solid
rar5_solid
rar5_blake2
rar5_unicode
rar5_symlink
rar5_redir_varint_2byte
rar5_encrypted
rar5_encrypted_filenames
rar5_solid_encrypted
rar5_multiarchive.part01
rar5_bad_tables
rar5_truncated_huff
```

Successful RAR4 normal/best extraction uses the first original compressed member
and original ENDARC because the full upstream corpus contains a deliberately
unsupported symlink. Expected extracted HTML SHA-256:
`ee16390e87152d7dec632ae2b37566da6135966e46b5166e6f40a1c827fa61ad`.
RAR5 generated polynomial files are checked byte-for-byte using the independent
upstream formula. Crafted RAR4/RAR5 STORE archives additionally cover unsafe paths,
NULs, special types, duplicates, all redirection types, present-zero CRC, staged
failure, read-only refusal, entry/count/total limits, missing JNI, concurrent
independent readers and unchanged original/target bytes.

Native verification compiles ONLY this module with `/opt/android-ndk` revision
30.0.16248370, android-21, all of armeabi-v7a/arm64-v8a/x86/x86_64, and the parent
`-Wall -Wextra -Werror -Os -fno-stack-protector` flags. Both native modules override
the inherited stack flag with a final `-fstack-protector-strong`, checked in the
actual compile commands for JNI and RAR5 on all four ABIs. The resulting stripped libraries were 109,288 bytes (armeabi-v7a), 137,872 bytes
(arm64-v8a), 146,228 bytes (x86), and 139,096 bytes (x86_64). ELF inspection found
only the five JNI entry points exported and only Android system libraries needed
(`libz`, `libc`, `libm`, `libdl`); 64-bit load segments use 16 KiB alignment.
A deliberately incorrect source hash was rejected and its build stamp removed.
A tiny hostile compressed header declaring a 64 MiB RAR5 dictionary fails before that allocation under
a 1 MiB native budget; its recorded native peak remains within the configured bound.
Full Gradle/bootstrap builds,
D8/R8 packaging, Android runtime/device acceptance, emulator, CI and push are not
performed. The parent must verify actual Android loading/packaging and UI use.
