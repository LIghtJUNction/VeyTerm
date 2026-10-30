#!/usr/bin/env python3
"""Compile only FileStaging/smoke, using read-only parent classes and SDK; no Gradle/device."""
import argparse
import binascii
import hashlib
from pathlib import Path
import subprocess
import tempfile
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, default=Path('/opt/android-sdk/platforms/android-36/android.jar'))
    args = parser.parse_args()
    test = Path(__file__).resolve().parent
    worker = test.parents[8]
    parent = args.parent.resolve()
    classes = parent / 'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
    source = worker / 'app/src/main/java/com/termux/app/files/fs/FileStaging.java'
    fixture_source = parent / 'app/src/test/java/com/termux/app/files/archive/fixtures'
    dependencies = []
    gradle = Path.home() / '.gradle/caches/modules-2/files-2.1'
    for artifact, version in [('zip4j', '2.11.6'), ('commons-compress', '1.28.0'), ('xz', '1.12'),
                              ('commons-io', '2.22.0'), ('commons-lang3', '3.21.0'), ('commons-codec', '1.19.0')]:
        found = list(gradle.glob(f'*/{artifact}/{version}/*/{artifact}-{version}.jar'))
        if len(found) != 1:
            raise RuntimeError(f'Expected one existing read-only jar: {artifact}:{version}')
        dependencies.append(found[0])
    build = worker / 'build'
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='archive-volumes-host-', dir=build) as temporary:
        work = Path(temporary)
        real = work / 'android-compile'
        host = work / 'host-compile'
        real.mkdir(); host.mkdir()
        # First compile unchanged Android API references against the actual SDK, without stubs.
        subprocess.run(['javac', '-source', '8', '-target', '8', '-Xlint:all,-options',
                        '-cp', f'{args.sdk}:{classes}', '-d', str(real), str(source), str(worker / 'app/src/sharedTest/java/com/termux/app/files/fs/ArchiveVolumeSmoke.java')], check=True)
        print('SDK36 javac: PASS (real android.jar, parent classes read-only)', flush=True)
        url = 'https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar'
        data = urllib.request.urlopen(url, timeout=30).read(128 * 1024)
        if hashlib.sha256(data).hexdigest() != 'dfb7bae2f404cfe0b72b4d23944698cb716b7665171812a0a4d0f5926c0fac79':
            raise RuntimeError('Android JSON jar checksum mismatch')
        json_jar = work / 'android-json.jar'
        json_jar.write_bytes(data)
        # These are solely a cache-directory adapter for JVM startup. Production/test classes above
        # were compiled against the actual SDK. JSONObject/JSONArray use real Android-derived code.
        stub = work / 'android/content'
        stub.mkdir(parents=True)
        (stub / 'Context.java').write_text('package android.content; public abstract class Context { public abstract java.io.File getCacheDir(); }\n')
        (stub / 'ContextWrapper.java').write_text('package android.content; public class ContextWrapper extends Context { private final Context base; public ContextWrapper(Context base) { this.base=base; } public java.io.File getCacheDir() { return base.getCacheDir(); } }\n')
        main_java = work / 'VolumeHostMain.java'
        main_java.write_text('''
import java.io.File;
import android.content.Context;
import com.termux.app.files.fs.ArchiveVolumeSmoke;
public class VolumeHostMain {
  public static void main(String[] args) throws Exception {
    final File cache = new File(args[0]);
    if (!cache.mkdir()) throw new Exception("Cannot create own host cache");
    ArchiveVolumeSmoke.run(new Context() { public File getCacheDir() { return cache; } }, new File(args[1]));
    if (cache.list().length != 0) throw new Exception("Smoke left temporary cache files");
  }
}
''')
        subprocess.run(['javac', '--release', '8', '-Xlint:-options', '-cp', str(real), '-d', str(host),
                        str(stub / 'Context.java'), str(stub / 'ContextWrapper.java'), str(main_java)], check=True)
        decoded = work / 'fixtures'
        decoded.mkdir()
        sums = {}
        for line in (fixture_source / 'SHA256SUMS').read_text().splitlines():
            if line.strip():
                digest, name = line.split(maxsplit=1)
                sums[Path(name.lstrip('*')).name] = digest
        names = [f'test_rar_multivolume_single_file.part{i}.rar' for i in range(1, 4)]
        names += [f'test_read_format_rar5_multiarchive.part{i:02}.rar' for i in range(1, 9)]
        for name in names:
            encoded = fixture_source / (name + '.uu')
            raw = encoded.read_bytes()
            if hashlib.sha256(raw).hexdigest() != sums[encoded.name]:
                raise RuntimeError(f'Fixture checksum mismatch: {name}')
            lines = raw.splitlines()
            start = next(i for i, line in enumerate(lines) if line.startswith(b'begin '))
            payload = bytearray()
            for line in lines[start + 1:]:
                if line == b'end':
                    break
                payload.extend(binascii.a2b_uu(line))
            (decoded / name).write_bytes(payload)
        print(f'Fixtures: {len(names)} original uuencoded SHA-256 checks passed', flush=True)
        cp = ':'.join(map(str, [host, real, json_jar, classes] + dependencies))
        subprocess.run(['java', '-Xmx192m', '-cp', cp, 'VolumeHostMain', str(work / 'cache'), str(decoded)], check=True)
    print('Temporary JSON jar, decoded fixtures, classes and isolated caches removed', flush=True)


if __name__ == '__main__':
    main()
