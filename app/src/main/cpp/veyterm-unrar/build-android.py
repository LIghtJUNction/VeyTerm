#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-2-Clause
# Copyright (c) 2026 VeyTerm contributors. See LICENSE.guard.
"""Build only the four standalone commands with NDK r30, serially; no Gradle.
Output is build/android/<ABI>/libveyterm_unrar.so, an executable PIE, not a DSO.
"""
import argparse
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=HERE / 'build/android')
    parser.add_argument('--abis', nargs='+', choices=ABIS, default=ABIS)
    args = parser.parse_args()
    if 'Pkg.Revision = 30.' not in (args.ndk / 'source.properties').read_text():
        raise RuntimeError('This build is pinned to Android NDK r30')
    notice = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/NOTICE'
    if notice.read_bytes() != (HERE / 'NOTICE.NDK-r30-runtime').read_bytes():
        raise RuntimeError('NDK runtime notices changed; review and preserve the new license notices')
    sysroot_notice = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/NOTICE'
    if sysroot_notice.read_bytes() != (HERE / 'NOTICE.NDK-r30-sysroot').read_bytes():
        raise RuntimeError('NDK sysroot notices changed; review and preserve the new license notices')
    for abi in args.abis:
        output = args.output.resolve() / abi
        temporary = output / 'tmp'
        temporary.mkdir(parents=True, exist_ok=True)
        environment = os.environ.copy()
        environment['TMPDIR'] = str(temporary)
        subprocess.run(['cmake', '-S', str(HERE), '-B', str(output),
                        '-DCMAKE_BUILD_TYPE=Release',
                        f'-DCMAKE_TOOLCHAIN_FILE={args.ndk.resolve()}/build/cmake/android.toolchain.cmake',
                        f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-21',
                        '-DANDROID_STL=c++_static'], check=True, env=environment)
        subprocess.run(['cmake', '--build', str(output), '-j1'], check=True, env=environment)
        binary = output / 'libveyterm_unrar.so'
        readelf = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
        header = subprocess.check_output([str(readelf), '-h', '-l', '-d', str(binary)], text=True)
        if 'DYN (Shared object file)' not in header or 'INTERP' not in header or 'libc++_shared.so' in header:
            raise RuntimeError('Expected standalone PIE with static C++ runtime: ' + str(binary))
        print(binary, flush=True)


if __name__ == '__main__':
    main()
