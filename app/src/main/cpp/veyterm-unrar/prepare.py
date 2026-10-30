#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-2-Clause
# Copyright (c) 2026 VeyTerm contributors. See LICENSE.guard.
"""Prepare the complete, pinned, independently licensed UnRAR command sources.

UnRAR source code may be used in any software to handle
RAR archives without limitations free of charge, but cannot be
used to develop RAR (WinRAR) compatible archiver and to
re-create RAR compression algorithm, which is proprietary.
Distribution of modified UnRAR source code in separate form
or as a part of other software is permitted, provided that
full text of this paragraph, starting from "UnRAR source code"
words, is included in license, or in documentation if license
is not available, and in source code comments of resulting package.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request

VERSION = '7.3.2'
URL = f'https://www.rarlab.com/rar/unrarsrc-{VERSION}.tar.gz'
SHA256 = 'f61a1160d6e2ebbda2ad353c8e82f62ab4312f97fde23c6c2c982d25e0014f99'
HERE = Path(__file__).resolve().parent


def replace(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError('Pinned UnRAR patch anchor mismatch: ' + old[:80])
    return text.replace(old, new)


def patch(text):
    # These are input-policy checks, not decompression algorithm changes.
    # lt is a human-readable public CLI: reject names it cannot represent
    # faithfully BEFORE UnRAR normalizes/truncates them, including decrypted ones.
    anchor = '        size_t ReadNameSize=Min(NameSize,MAXPATHSIZE);'
    if text.count(anchor) != 2:
        raise RuntimeError('Pinned raw-name anchors changed')
    text = text.replace(anchor, '''        if (FileBlock && (NameSize==0 || NameSize>4096))
          ErrHandler.Exit(RARX_FATAL);
''' + anchor)
    text = replace(text, '    FileHeader *hd=CurHeaderType==HEAD_FILE ? &FileHead:&SubHead;', '''    FileHeader *hd=CurHeaderType==HEAD_FILE ? &FileHead:&SubHead;
    for (wchar C: hd->FileName)
      if (C<32 || C==127 || (C>=128 && C<=159) || C==0xfffd ||
          (C>=0x2028 && C<=0x202e) || (C>=0x2066 && C<=0x2069))
        ErrHandler.Exit(RARX_FATAL);''')
    text = replace(text, '          hd->FileName.clear();', '''          if ((hd->Flags & LHD_UNICODE)==0 && FileName.find('\\0')!=std::string::npos)
            ErrHandler.Exit(RARX_FATAL);
          hd->FileName.clear();''')
    text = replace(text, '        UtfToWide(FileName.data(),hd->FileName);', '''        if (FileBlock && FileName.find('\\0')!=std::string::npos)
          ErrHandler.Exit(RARX_FATAL);
        UtfToWide(FileName.data(),hd->FileName);
        if (FileBlock)
        {
          std::string RoundTrip;
          WideToUtf(hd->FileName,RoundTrip);
          if (RoundTrip!=FileName)
            ErrHandler.Exit(RARX_FATAL);
        }''')
    text = replace(text, 'void Archive::ConvertFileHeader(FileHeader *hd)\n{', '''void Archive::ConvertFileHeader(FileHeader *hd)
{
  // Reject lossy public-CLI metadata, links and special files. This applies
  // to all file headers, including headers decrypted using ordinary -p stdin.
  if (hd->FileName.empty() || hd->FileName.size()>4096 || hd->RedirType!=FSREDIR_NONE)
    ErrHandler.Exit(RARX_FATAL);
  for (wchar C: hd->FileName)
    if (C<32 || C==127 || (C>=128 && C<=159) || C==0xfffd ||
        (C>=0x2028 && C<=0x202e) || (C>=0x2066 && C<=0x2069) ||
        C==L'\\\\' && Format==RARFMT50 || C==L':')
      ErrHandler.Exit(RARX_FATAL);
  uint Type=hd->FileAttr & 0170000;
  if (hd->HSType==HSYS_UNIX && Type!=0100000 && Type!=0040000 ||
      hd->HSType==HSYS_WINDOWS && (hd->FileAttr & 0x400)!=0 || hd->HSType==HSYS_UNKNOWN)
    ErrHandler.Exit(RARX_FATAL);''')
    return text


def prepare(destination=HERE / 'build/source', archive=None):
    destination = Path(destination).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    stamp = destination / '.prepared'
    stamp.unlink(missing_ok=True)
    cache = HERE / 'build' / f'unrarsrc-{VERSION}.tar.gz'
    cache.parent.mkdir(parents=True, exist_ok=True)
    if archive is None:
        if not cache.exists():
            with urllib.request.urlopen(URL, timeout=60) as response:
                data = response.read(2 * 1024 * 1024 + 1)
            if len(data) > 2 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != SHA256:
                raise RuntimeError('UnRAR source SHA-256 mismatch')
            cache.write_bytes(data)
        archive = cache
    if hashlib.sha256(Path(archive).read_bytes()).hexdigest() != SHA256:
        raise RuntimeError('UnRAR source SHA-256 mismatch')
    with tempfile.TemporaryDirectory(prefix='prepare-', dir=destination.parent) as temp:
        stage = Path(temp)
        with tarfile.open(archive) as tar:
            for member in tar.getmembers():
                parts = Path(member.name).parts
                if not parts or parts[0] != 'unrar' or '..' in parts or member.issym() or member.islnk():
                    raise RuntimeError('Unsafe upstream source tar member')
                if member.isdir():
                    continue
                if not member.isfile() or member.size > 4 * 1024 * 1024:
                    raise RuntimeError('Unexpected upstream source tar member')
                output = stage.joinpath(*parts[1:])
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(tar.extractfile(member).read())
        # Preserve ALL original source and notices in original/, not just a reader subset.
        original = stage / 'original'
        original.mkdir()
        for path in list(stage.iterdir()):
            if path != original:
                shutil.move(str(path), original)
        for path in original.iterdir():
            if path.is_file():
                shutil.copy2(path, stage / path.name)
        for notice, vendored in [('license.txt', 'LICENSE.UnRAR'), ('acknow.txt', 'ACKNOWLEDGEMENTS.UnRAR')]:
            if (stage / notice).read_bytes().replace(b'\r\n', b'\n') != (HERE / vendored).read_bytes().replace(b'\r\n', b'\n'):
                raise RuntimeError('Upstream license notice changed')
        makefile = (stage / 'makefile').read_text().replace(chr(92) + chr(10), ' ')
        expected_sources = []
        for variable in ('OBJECTS=', 'UNRAR_OBJ='):
            values = next(line[len(variable):] for line in makefile.splitlines() if line.startswith(variable))
            expected_sources.extend(value.removesuffix('.o') + '.cpp' for value in values.split())
        if (HERE / 'sources.txt').read_text().splitlines() != expected_sources:
            raise RuntimeError('Command source manifest differs from the official makefile')
        path = stage / 'arcread.cpp'
        path.write_text('/*\n' + __doc__ + '\n*/\n' + patch(path.read_text()))
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(stage, destination)
        stamp.write_text(SHA256 + '\n')
    return destination


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=HERE / 'build/source')
    parser.add_argument('--archive', type=Path)
    args = parser.parse_args()
    print(prepare(args.output, args.archive))
