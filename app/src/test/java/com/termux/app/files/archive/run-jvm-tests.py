#!/usr/bin/env python3
"""Run only archive JVM tests; no Gradle/Android/bootstrap/device/CI work.
Builds the pinned native RAR reader, verifies source/corpus/jar SHA-256, and
removes all task downloads/classes/native outputs under ignored build/ on exit.
Requires Python >= 3.9, JDK >= 9, CMake, C compiler and zlib development files.
"""
import binascii
import argparse
import hashlib
import sys
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import time

sys.dont_write_bytecode = True

ARTIFACTS = [
    ("net/lingala/zip4j/zip4j", "2.11.6", "5f1eb1e9d67cfee200a695e4079751b49e73a226b154193e13f7c602efbe72fb"),
    ("org/apache/commons/commons-compress", "1.28.0", "e1522945218456f3649a39bc4afd70ce4bd466221519dba7d378f2141a4642ca"),
    ("org/tukaani/xz", "1.12", "3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b"),
    ("commons-io/commons-io", "2.22.0", "2b9a7b1f726fb86216dbd2c8321eabe0221dbd5b1be81c18e1cb53811b104758"),
    ("org/apache/commons/commons-lang3", "3.21.0", "a2af247ec4db6eb8947e18e23e0433e4871a7c5d0a40e1f92e46ede54ae1defe"),
    ("commons-codec/commons-codec", "1.19.0", "5c3881e4f556855e9c532927ee0c9dfde94cc66760d5805c031a59887070af5f"),
    ("junit/junit", "4.13.2", "8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3"),
    ("org/hamcrest/hamcrest-core", "1.3", "66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9"),
]


def verify_command_limits(executable, fixtures):
    """Observe kernel-enforced child bounds while its ordinary -p awaits stdin."""
    import resource
    archive = fixtures / 'test_read_format_rar5_solid_encrypted_filenames.rar'
    command = [str(executable), 'lt', '-cfg-', '-c-', '-idcp', '-mdx32m', '-p', '--', str(archive)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        deadline = time.monotonic() + 5
        while resource.prlimit(process.pid, resource.RLIMIT_DATA)[0] != 64 * 1024 * 1024:
            if process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Native command did not establish the allocation limit')
            time.sleep(.01)
        for key, expected in [(resource.RLIMIT_DATA, 64 * 1024 * 1024),
                              (resource.RLIMIT_AS, 192 * 1024 * 1024),
                              (resource.RLIMIT_CPU, 120), (resource.RLIMIT_NOFILE, 32),
                              (resource.RLIMIT_CORE, 0), (resource.RLIMIT_FSIZE, 0)]:
            if resource.prlimit(process.pid, key) != (expected, expected):
                raise RuntimeError('Native command kernel limit mismatch')
        process.communicate(b'password\n', timeout=5)
        if process.returncode:
            raise RuntimeError('Bounded native command failed listing')
    finally:
        if process.poll() is None:
            process.kill()
        process.communicate()
    small = subprocess.run([str(executable), 't', '-cfg-', '-inul', '-mdx512k', '-limm1m', '-p', '--', str(archive)],
                           input=b'password\n', stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
    if small.returncode == 0:
        raise RuntimeError('Native low-memory invocation unexpectedly succeeded')
    print('Native command: six kernel limits verified; 1 MiB budget fails closed', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--full-helper', action='store_true', help='Build/test the independent full UnRAR command in a separate JVM')
    args = parser.parse_args()
    test_dir = Path(__file__).resolve().parent
    root = test_dir.parents[8]
    relative = Path("com/termux/app/files/archive")
    build_dir = root / "build"
    build_dir.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="rar-jvm-", dir=build_dir) as work:
        work = Path(work)
        temporary = work / 'tmp'
        temporary.mkdir()
        os.environ['TMPDIR'] = str(temporary)
        for artifact, version, expected in ARTIFACTS:
            name = artifact.rsplit("/", 1)[1]
            url = f"https://repo.maven.apache.org/maven2/{artifact}/{version}/{name}-{version}.jar"
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read(16 * 1024 * 1024)
            if hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeError(f"Dependency checksum mismatch: {artifact}:{version}")
            (work / (name + ".jar")).write_bytes(data)
        adapter = root / "app/src/main/cpp/veyterm-archive"
        spec = importlib.util.spec_from_file_location("rar_prepare", adapter / "prepare.py")
        prepare = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(prepare)
        prepare.prepare(work / "source")
        native = work / "native"
        for cmd in (["cmake", "-S", str(adapter), "-B", str(native),
                     "-DCMAKE_BUILD_TYPE=Release", f"-DARCHIVE_SOURCE_DIR={work / 'source'}"],
                    ["cmake", "--build", str(native), "-j", "1"]):
            result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if result.returncode:
                raise RuntimeError(result.stdout)
        fixtures = work / "fixtures"
        fixtures.mkdir()
        corpus = test_dir / "fixtures"
        for line in (corpus / "SHA256SUMS").read_text().splitlines():
            expected, name = line.split()
            data = (corpus / name).read_bytes()
            if hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeError("RAR corpus checksum mismatch: " + name)
            lines = data.splitlines()
            start = next(i for i, v in enumerate(lines) if v.startswith(b"begin ")) + 1
            decoded = b"".join(binascii.a2b_uu(v) for v in lines[start:] if v not in (b"end", b""))
            (fixtures / name.removesuffix(".uu")).write_bytes(decoded)
        classes = work / "classes"
        classes.mkdir()
        sources = sorted((root / "app/src/main/java" / relative).glob("*.java"))
        sources += sorted(test_dir.glob("*Test.java"))
        subprocess.run(["javac", f"-J-Djava.io.tmpdir={temporary}", "--release", "8", "-Xlint:all,-options", "-cp", str(work / "*"),
                        "-d", str(classes), *map(str, sources)], check=True, cwd=root)
        subprocess.run(["java", "-Xmx192m", f"-Djava.io.tmpdir={temporary}", "-Xcheck:jni", f"-Djava.library.path={native}",
                        f"-Dveyterm.rar.fixtures={fixtures}", "-cp", str(work / "*") + ":" + str(classes),
                        "org.junit.runner.JUnitCore", "com.termux.app.files.archive.ArchiveToolsTest",
                        "com.termux.app.files.archive.RarArchiveTest"],
                       check=True, cwd=root)
        empty_native = work / "no-native"
        empty_native.mkdir()
        subprocess.run(["java", "-Xmx192m", f"-Djava.io.tmpdir={temporary}", f"-Djava.library.path={empty_native}",
                        "-cp", str(work / "*") + ":" + str(classes),
                        "org.junit.runner.JUnitCore", "com.termux.app.files.archive.RarUnavailableTest"],
                       check=True, cwd=root)
        if args.full_helper:
            command = root / 'app/src/main/cpp/veyterm-unrar'
            spec = importlib.util.spec_from_file_location('unrar_prepare', command / 'prepare.py')
            unrar_prepare = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(unrar_prepare)
            unrar_prepare.prepare(work / 'unrar-source')
            helper_build = work / 'unrar-command'
            for cmd in (["cmake", "-S", str(command), "-B", str(helper_build),
                         "-DCMAKE_BUILD_TYPE=Release", f"-DUNRAR_SOURCE_DIR={work / 'unrar-source'}"],
                        ["cmake", "--build", str(helper_build), "-j1"]):
                result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                if result.returncode:
                    raise RuntimeError(result.stdout)
            process_fixture = work / 'command-fixture'
            subprocess.run(['cc', '-O0', str(test_dir / 'command-fixture.c'), '-o', str(process_fixture)], check=True)
            verify_command_limits(helper_build / 'libveyterm_unrar.so', fixtures)
            subprocess.run(["java", "-Xmx192m", f"-Djava.io.tmpdir={temporary}", f"-Djava.library.path={empty_native}",
                            f"-Dveyterm.rar.fixtures={fixtures}",
                            f"-Dveyterm.rar.command={helper_build / 'libveyterm_unrar.so'}",
                            f"-Dveyterm.rar.commandFixture={process_fixture}",
                            "-cp", str(work / "*") + ":" + str(classes), "org.junit.runner.JUnitCore",
                            "com.termux.app.files.archive.FullRarTest"], check=True, cwd=root)



if __name__ == "__main__":
    main()
