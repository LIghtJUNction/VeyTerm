#!/usr/bin/env python3
"""Fetch a hash-pinned release; materialize only the RAR reader sources/headers.
No build-time network access: run explicitly before ndk-build. See BUILD.md.
"""
import argparse
import hashlib
from pathlib import Path
import tarfile
import tempfile
import urllib.request

VERSION = '3.8.10'
URL = f'https://www.libarchive.org/downloads/libarchive-{VERSION}.tar.xz'
SHA256 = '56cad765459d5ee25a3b761f90c47d99379d28297fbfb72d908880d6b875fb96'
HERE = Path(__file__).resolve().parent


def replace(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError('Pinned source patch anchor mismatch: ' + old[:80])
    return text.replace(old, new)


def patch(name, text):
    # Policy checks must precede libarchive's filename normalization and loss of
    # private flags. CRC finalization fixes are also required; see patches.md.
    if name == 'archive_read_support_format_rar.c':
        text = replace(text, 'rar->main_flags = archive_le16dec(p + 3);',
            '''rar->main_flags = archive_le16dec(p + 3);
      if (rar->main_flags & MHD_VOLUME) {
        archive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
          "Multi-volume RAR is unsupported");
        return ARCHIVE_FATAL;
      }''')
        text = replace(text, 'rar->file_flags = archive_le16dec(rar_header.flags);',
            '''rar->file_flags = archive_le16dec(rar_header.flags);
  if (rar->file_flags & (FHD_SPLIT_BEFORE | FHD_SPLIT_AFTER)) {
    archive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
      "Split RAR member is unsupported");
    return ARCHIVE_FATAL;
  }''')
        anchor = "  if ((h = __archive_read_ahead(a, 7, NULL)) == NULL)\n    return (ARCHIVE_EOF);"
        text = replace(text, anchor, """  {
    ssize_t available = 0;
    h = __archive_read_ahead(a, 7, &available);
    if (h == NULL) {
      if (available > 0) {
        archive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
          "Truncated RAR header");
        return ARCHIVE_FATAL;
      }
      return ARCHIVE_EOF;
    }
  }""")
        anchor = '  if (rar->entry_eof || rar->offset_seek >= rar->unp_size) {'
        text = replace(text, anchor, """  if (rar->offset_seek >= rar->unp_size &&
      rar->file_crc != rar->crc_calculated) {
    archive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT, "File CRC error");
    return ARCHIVE_FAILED;
  }
""" + anchor)
        text = replace(text, '  if (rar->filename_save &&',
            '  if ((rar->file_flags & FHD_SPLIT_BEFORE) && rar->filename_save &&')
        text = replace(text, '  char *strp;\n', '')
        # Reject embedded NULs except the specified RAR4 encoded Unicode separator.
        text = replace(text, "filename[filename_size] = '\\0';\n  if (rar->file_flags & FHD_UNICODE)",
            '''filename[filename_size] = '\\0';
  if (!(rar->file_flags & FHD_UNICODE) && memchr(filename, 0, filename_size)) {
    archive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT, "NUL in RAR name");
    return ARCHIVE_FATAL;
  }
  if (rar->file_flags & FHD_UNICODE)''')
        anchor = "      filename[filename_size++] = '\\0';\n      /*"
        text = replace(text, anchor, """      {
        unsigned j;
        for (j = 0; j + 1 < filename_size; j += 2) {
          if (filename[j] == 0 && filename[j + 1] == 0) {
            archive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
              "NUL in RAR Unicode name");
            return ARCHIVE_FATAL;
          }
        }
      }
""" + anchor)
        # Preserve backslashes so ArchiveTools.checkedName rejects them.
        text = replace(text, '''      strp = filename;
      while (memcmp(strp, "\\x00\\x00", 2))
      {
        if (!memcmp(strp, "\\x00\\\\", 2))
          *(strp + 1) = '/';
        strp += 2;
      }''', '')
        text = replace(text, '''      strp = filename;
      while ((strp = strchr(strp, '\\\\')) != NULL)
        *strp++ = '/';''', '')
        text = replace(text, '''    strp = filename;
    while ((strp = strchr(strp, '\\\\')) != NULL)
      *strp++ = '/';''', '')
        # Reject Windows reparse attributes before Unix mode mapping loses them.
        anchor = '  filename_size = archive_le16dec(file_header.name_size);'
        text = replace(text, anchor, '''  if ((file_header.host_os == OS_MSDOS ||
       file_header.host_os == OS_OS2 || file_header.host_os == OS_WIN32) &&
      (archive_le32dec(file_header.file_attr) & 0x400)) {
    archive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
      "RAR reparse member rejected");
    return ARCHIVE_FATAL;
  }
''' + anchor)
    elif name == 'archive_read_support_format_rar5.c':
        anchor = '\trar5->main.volume = (archive_flags & VOLUME) > 0;'
        text = replace(text, anchor, '''\tif (archive_flags & (VOLUME | VOLUME_NUMBER)) {
\t\tarchive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
\t\t    "Multi-volume RAR5 is unsupported");
\t\treturn ARCHIVE_FATAL;
\t}
''' + anchor)
        anchor = '\tarchive_entry_clear(entry);'
        text = replace(text, anchor, """\tif (block_flags & (HFL_SPLIT_BEFORE | HFL_SPLIT_AFTER)) {
\t\tarchive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
\t\t    "Split RAR5 member is unsupported");
\t\treturn ARCHIVE_FATAL;
\t}
""" + anchor)
        anchor = '\tmemcpy(name_utf8_buf, p, name_size);'
        text = replace(text, anchor, """\tif (memchr(p, 0, name_size)) {
\t\tarchive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
\t\t    "NUL in RAR5 member name");
\t\treturn ARCHIVE_FATAL;
\t}
""" + anchor)
        anchor = '\tif(host_os == HOST_WINDOWS) {'
        text = replace(text, anchor, """\tif (host_os == HOST_WINDOWS && (file_attr & 0x400)) {
\t\tarchive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
\t\t    "RAR5 reparse member rejected");
\t\treturn ARCHIVE_FATAL;
\t}
""" + anchor)
        anchor = "\treturn ret;\n}\n\nstatic int init_unpack(struct rar5 *rar5)"
        text = replace(text, anchor, """\tif (ret == ARCHIVE_EOF && !rar5->main.endarc) {
\t\tarchive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
\t\t    "Truncated RAR5 header or missing end marker");
\t\treturn ARCHIVE_FATAL;
\t}
""" + anchor)
        # STORE finalization used last_write_ptr (compressed output only), so
        # verify explicitly when the STORE reader reaches its own end pointer.
        anchor = '\tif(to_read == 0) {\n\t\treturn ARCHIVE_EOF;\n\t}'
        text = replace(text, anchor, """\tif(to_read == 0) {
\t\tint result = verify_global_checksums(a);
\t\trar5->cstate.last_write_ptr = rar5->cstate.last_unstore_ptr;
\t\trar5->file.eof = 1;
\t\treturn result == ARCHIVE_OK ? ARCHIVE_EOF : result;
\t}""")
        text = replace(text, '\tuint32_t stored_crc32;',
            '\tuint32_t stored_crc32;\n\tint has_crc32;')
        text = replace(text, '\t\trar5->file.stored_crc32 = crc;',
            '\t\trar5->file.stored_crc32 = crc;\n\t\trar5->file.has_crc32 = 1;')
        if text.count('if(rar5->file.stored_crc32 > 0)') != 2:
            raise RuntimeError('CRC32 presence patch anchor mismatch')
        text = text.replace('if(rar5->file.stored_crc32 > 0)', 'if(rar5->file.has_crc32)')
        # All redirection types, including junction/file-copy/unknown, are unsafe.
        anchor = '\tswitch(rar5->file.redir_type) {'
        text = replace(text, anchor, '''\tarchive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT,
\t    "RAR5 link/redirection member rejected");
\treturn ARCHIVE_FATAL;
''' + anchor)
    return text


def prepare(output, tarball=None):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / '.prepared').unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='.fetch-', dir=output) as work:
        download = Path(work) / 'source.tar.xz'
        if tarball:
            download.write_bytes(Path(tarball).read_bytes())
        else:
            with urllib.request.urlopen(URL, timeout=60) as response, download.open('wb') as target:
                total = 0
                while chunk := response.read(65536):
                    total += len(chunk)
                    if total > 16 * 1024 * 1024:
                        raise RuntimeError('Source download exceeded 16 MiB')
                    target.write(chunk)
        if hashlib.sha256(download.read_bytes()).hexdigest() != SHA256:
            raise RuntimeError('libarchive source SHA-256 mismatch')
        sources = set((HERE / 'sources.txt').read_text().splitlines())
        with tarfile.open(download, 'r:xz') as tar:
            prefix = f'libarchive-{VERSION}/'
            for member in tar:
                relative = member.name.removeprefix(prefix)
                if not member.isfile():
                    continue
                parts = Path(relative).parts
                wanted = relative in ('COPYING', 'contrib/android/include/android_lf.h') or (len(parts) == 2 and parts[0] == 'libarchive'
                    and (parts[1] in sources or parts[1].endswith('.h')))
                if wanted:
                    name = Path(relative).name
                    (output / name).write_text(patch(name, tar.extractfile(member).read().decode()))
        missing = [s for s in sources if not (output / s).is_file()]
        if missing:
            raise RuntimeError('Missing pinned sources: ' + str(missing))
    (output / '.prepared').write_text(SHA256 + '\n')
    print(f'Prepared libarchive {VERSION}: {len(sources)} C sources in {output}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=HERE / 'build/source')
    parser.add_argument('--tarball', type=Path, help='verified offline release tarball')
    args = parser.parse_args()
    prepare(args.output, args.tarball)
