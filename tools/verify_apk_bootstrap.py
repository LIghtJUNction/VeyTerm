#!/usr/bin/env python3
"""Compare the bootstrap bytes embedded in each APK ABI with the checked archive."""

import argparse
import hashlib
from pathlib import Path
import struct
import sys
import zipfile


ARCHITECTURES = {"arm64-v8a": "aarch64", "armeabi-v7a": "arm", "x86": "i686", "x86_64": "x86_64"}


def embedded_archive(data):
    if data[:4] != b"\x7fELF":
        raise ValueError("bootstrap library is not ELF")
    endian = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        section_offset = struct.unpack_from(endian + "Q", data, 40)[0]
        section_size, section_count = struct.unpack_from(endian + "HH", data, 58)
        section_format, symbol_format = "IIQQQQIIQQ", "IBBHQQ"
        value_index = 4
    elif data[4] == 1:
        section_offset = struct.unpack_from(endian + "I", data, 32)[0]
        section_size, section_count = struct.unpack_from(endian + "HH", data, 46)
        section_format, symbol_format = "IIIIIIIIII", "IIIBBH"
        value_index = 1
    else:
        raise ValueError("unsupported ELF class")
    sections = [struct.unpack_from(endian + section_format, data, section_offset + i * section_size)
                for i in range(section_count)]
    symbols = {}
    for section in sections:
        if section[1] != 11:  # SHT_DYNSYM
            continue
        strings_section = sections[section[6]]
        strings = data[strings_section[4]:strings_section[4] + strings_section[5]]
        for offset in range(section[4], section[4] + section[5], section[9]):
            symbol = struct.unpack_from(endian + symbol_format, data, offset)
            name = strings[symbol[0]:].split(b"\0", 1)[0]
            if name in (b"blob", b"blob_size"):
                symbols[name] = symbol[value_index]

    def file_offset(address):
        for section in sections:
            if section[2] & 2 and section[1] != 8 and section[3] <= address < section[3] + section[5]:
                return section[4] + address - section[3]
        raise ValueError("bootstrap symbol has no file-backed section")

    start = file_offset(symbols[b"blob"])
    size = struct.unpack_from(endian + "I", data, file_offset(symbols[b"blob_size"]))[0]
    if size == 0 or start + size > len(data):
        raise ValueError("invalid embedded bootstrap size")
    return data[start:start + size]


def verify(apk, bootstrap_directory):
    with zipfile.ZipFile(apk) as archive:
        referenced = sum(entry.compress_size for entry in archive.infolist())
        if apk.stat().st_size > referenced * 1.1 + 1024 * 1024:
            raise ValueError(f"{apk.name}: excessive unused ZIP space; repackage the APK before distributing it")
        native_abis = {name.split("/")[1] for name in archive.namelist()
                       if name.startswith("lib/") and name.endswith(".so")}
        if not native_abis:
            raise ValueError(f"{apk.name}: no native ABIs")
        for abi in sorted(native_abis):
            bootstrap = bootstrap_directory / f"bootstrap-{ARCHITECTURES[abi]}.zip"
            expected = hashlib.sha256(bootstrap.read_bytes()).hexdigest()
            data = embedded_archive(archive.read(f"lib/{abi}/libtermux-bootstrap.so"))
            actual = hashlib.sha256(data).hexdigest()
            if actual != expected:
                raise ValueError(f"{apk.name}: {abi}: stale bootstrap: embedded={actual}, expected={expected}")
            print(f"{apk.name}: {abi}: bootstrap sha256={actual}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bootstrap-directory", type=Path, default=Path("app/src/main/cpp"))
    parser.add_argument("apks", type=Path, nargs="+")
    args = parser.parse_args()
    for apk in args.apks:
        verify(apk, args.bootstrap_directory)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, struct.error) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
