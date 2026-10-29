# Changes applied to pinned libarchive 3.8.10

`prepare.py` verifies the release tarball SHA-256 before extraction. Each text
replacement checks the exact expected occurrence count and fails closed if the
pinned source does not match. Original copyright headers remain intact.

Only two upstream C files change. No codec is replaced and no other archive
format is registered or built.

* RAR4: reject main volume and member split flags; require a split flag before
  interpreting identical consecutive names as a continuation (ordinary duplicates
  must reach ArchiveTools' duplicate check); retain backslashes instead of
  normalizing them away; reject embedded NULs in ordinary and decoded UTF-16 names;
  reject DOS/OS2/Windows reparse attributes before Unix-mode conversion.
* RAR5: reject volume/member-split flags, embedded NUL names, Windows reparse
  attributes, and every redirection type, including file-copy, junction and unknown
  types that upstream otherwise ignores. Regular symlink/hardlink metadata is
  also rejected by the JNI/Java layers.
* Truncation: reject partial RAR4 headers (a completely absent optional RAR4
  ENDARC remains accepted), and RAR5 header EOF without a valid ENDARC.
* RAR4 CRC: the read callback's early `offset_seek >= unp_size` return bypasses the
  STORE reader's final checksum comparison. Compare calculated and declared CRC
  before that early EOF. No seeking is exposed by this adapter.
* RAR5 STORE CRC/BLAKE2: `last_write_ptr` describes compressed output while STORE
  advances `last_unstore_ptr`. Finalize the checksum at STORE EOF, copy the correct
  final offset for libarchive's generic stream reader, and mark EOF once. Keep a
  separate CRC-presence bit so a declared CRC of zero is verified too.

The original 23-method suite exposed the RAR4 CRC regression; additional real and
crafted RAR5 STORE fixtures exposed the RAR5 regression. Tests alter payload CRCs
and repair header CRCs to distinguish payload validation from header validation.
They also cover absent/present-zero CRC, encrypted metadata, solid selection,
raw names, duplicates, redirection types 1 through 6, corruption and truncation.

`config.h` remaps the pinned library's malloc/calloc/realloc/free/strdup calls to a
per-reader allocator. This is a compile-time adapter, not an upstream source edit.
The allocation list includes headers and decoder buffers. Reallocation uses
allocate/copy/free with both old and new storage charged before allocation, so
libc realloc cannot temporarily evade the budget. Budget exhaustion uses
setjmp/longjmp entirely within native C frames, then disposes the complete owned
allocation list without invoking partially updated library state. This also avoids
libarchive string setters' fatal process-exit-on-OOM paths. No JVM frame is crossed.
JNI callbacks return before any decoder allocation occurs. Thread-local budget
selection is set/restored on every native call; independent readers can run on
separate threads, while each reader remains synchronous and privately owned.
