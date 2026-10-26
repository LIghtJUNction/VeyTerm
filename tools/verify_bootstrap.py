#!/usr/bin/env python3
"""Reject bootstrap archives that cannot run in VeyTerm's private prefix."""

import argparse
import hashlib
import posixpath
from pathlib import Path, PurePosixPath
import struct
import re
import sys
import zipfile


REQUIRED = (
    "bin/bash", "bin/zsh", "bin/pacman", "bin/paru", "bin/git",
    "bin/proot", "bin/proot-distro", "bin/makepkg", "bin/bsdtar", "bin/python3", "bin/pip", "etc/pacman.conf", "etc/makepkg.conf", "etc/paru.conf",
    "share/pacman/keyrings/veyterm.gpg", "share/pacman/keyrings/veyterm-trusted",
    "SYMLINKS.txt",
)
OLD_PREFIX = b"/data/data/com.termux"


def safe_relative(name):
    path = PurePosixPath(name)
    return not path.is_absolute() and ".." not in path.parts


def resolves_to_member(name, names, links):
    visited = set()
    prefix = "/data/data/com.veyterm/files/usr/"
    while name in links and name not in visited:
        visited.add(name)
        target = links[name]
        if target.startswith(prefix):
            name = target.removeprefix(prefix)
        elif target.startswith("/"):
            return False
        else:
            name = posixpath.normpath(posixpath.join(posixpath.dirname(name), target))
    return name in names


def runtime_environment_errors(name, data):
    errors = []
    if not data.startswith(b"#!"):
        return errors
    for line in data.splitlines():
        if re.match(rb"\s*(?:export\s+)?PATH=", line):
            if b"/home/builder" in line or b"/.termux-build/" in line:
                errors.append(f"build-host PATH in {name}")
            if name == "bin/makepkg" and b"makepkg-binutils" in line:
                if b"$PATH" not in line and b"${PATH}" not in line:
                    errors.append("makepkg discards the invoking environment's PATH")
    return errors


def verify(path):
    errors = []
    architecture = path.stem.removeprefix("bootstrap-")
    expected_elf = {"aarch64": (2, 183), "arm": (1, 40), "i686": (1, 3), "x86_64": (2, 62)}.get(architecture)
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        links = {}
        if "SYMLINKS.txt" in names:
            for line in archive.read("SYMLINKS.txt").decode().splitlines():
                parts = line.split("←")
                if len(parts) == 2:
                    links[parts[1].removeprefix("./")] = parts[0]
        if len(names) != len(set(names)):
            errors.append("duplicate archive entries")
        for name in REQUIRED:
            if not resolves_to_member(name, names, links):
                errors.append(f"missing {name}")
        for entry in archive.infolist():
            if not safe_relative(entry.filename):
                errors.append(f"unsafe path: {entry.filename}")
            data = archive.read(entry)
            errors.extend(runtime_environment_errors(entry.filename, data))
            if entry.filename.startswith("etc/pacman.d/gnupg"):
                errors.append("GnuPG state must be initialized on the device, not precreated in the archive")
            if OLD_PREFIX in data:
                errors.append(f"upstream runtime path: {entry.filename}")
            if data.startswith(b"\x7fELF") and expected_elf:
                machine = struct.unpack_from("<H" if data[5] == 1 else ">H", data, 18)[0]
                actual_elf = (data[4], machine)
                loader32 = {"aarch64": (1, 40), "x86_64": (1, 3)}.get(architecture)
                if actual_elf != expected_elf and not (entry.filename == "libexec/proot/loader32" and actual_elf == loader32):
                    errors.append(f"foreign ELF architecture: {entry.filename}: {actual_elf}")
            if entry.filename == "SYMLINKS.txt":
                for line in data.decode().splitlines():
                    parts = line.split("←")
                    if len(parts) != 2 or not safe_relative(parts[1]):
                        errors.append(f"invalid symlink: {line}")
        for name in ("bin/pacman", "bin/zsh", "bin/paru"):
            if name in names and not archive.read(name).startswith(b"\x7fELF"):
                errors.append(f"not a native executable: {name}")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    print(f"{path.name}: sha256={digest}, {len(errors)} errors")
    for error in errors[:25]:
        print(f"  {error}")
    if len(errors) > 25:
        print(f"  ... {len(errors) - 25} additional errors")
    return not errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", type=Path, nargs="+")
    args = parser.parse_args()
    return 0 if all([verify(path) for path in args.archives]) else 1


if __name__ == "__main__":
    sys.exit(main())
