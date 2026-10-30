#!/usr/bin/env python3
"""Build the pinned host JNI reader and verify/decode the small RAR test corpus."""
import binascii
import hashlib
from pathlib import Path
import sys
import subprocess
import argparse

root = Path(__file__).resolve().parents[1]
adapter = root / 'app/src/main/cpp/veyterm-archive'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('output', type=Path)
parser.add_argument('--fixtures-only', action='store_true')
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
native = output / 'native'
if not args.fixtures_only:
    subprocess.run(['cmake', '-S', str(adapter), '-B', str(native), '-DCMAKE_BUILD_TYPE=Release',
                    f'-DARCHIVE_SOURCE_DIR={adapter / "build/source"}'], check=True)
    subprocess.run(['cmake', '--build', str(native), '-j', '1'], check=True)
    command = root / 'app/src/main/cpp/veyterm-unrar'
    subprocess.run(['cmake', '-S', str(command), '-B', str(output / 'unrar'), '-DCMAKE_BUILD_TYPE=Release',
                    f'-DUNRAR_SOURCE_DIR={command / "build/source"}'], check=True)
    subprocess.run(['cmake', '--build', str(output / 'unrar'), '-j', '1'], check=True)
    subprocess.run(['cc', '-O0', str(root / 'app/src/test/java/com/termux/app/files/archive/command-fixture.c'), '-o', str(output / 'command-fixture')], check=True)
fixtures = root / 'app/src/test/java/com/termux/app/files/archive/fixtures'
decoded = output / 'fixtures'
decoded.mkdir(parents=True, exist_ok=True)
for line in (fixtures / 'SHA256SUMS').read_text().splitlines():
    if not line.strip() or line.startswith('#'):
        continue
    digest, name = line.split()
    data = (fixtures / name).read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise RuntimeError(f'RAR corpus checksum mismatch: {name}')
    lines = data.splitlines()
    start = next(i for i, value in enumerate(lines) if value.startswith(b'begin ')) + 1
    content = b''.join(binascii.a2b_uu(value) for value in lines[start:] if value not in (b'end', b''))
    (decoded / name.removesuffix('.uu')).write_bytes(content)
(output / 'no-native').mkdir(exist_ok=True)
