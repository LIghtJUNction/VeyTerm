#!/usr/bin/env python3
"""Run only archive JVM tests; no Gradle/Android/bootstrap/device/CI work.
Downloads about 5 MiB of pinned jars beneath this test directory, verifies SHA-256,
and removes downloads/classes on exit. Requires python3 and JDK >= 9 on PATH.
"""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ARTIFACTS = [
    ("net/lingala/zip4j/zip4j", "2.11.6", "5f1eb1e9d67cfee200a695e4079751b49e73a226b154193e13f7c602efbe72fb"),
    ("org/apache/commons/commons-compress", "1.28.0", "e1522945218456f3649a39bc4afd70ce4bd466221519dba7d378f2141a4642ca"),
    ("org/tukaani/xz", "1.12", "3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b"),
    ("com/github/junrar/junrar", "8.1.2", "6f8204a7fa257af3fb127d0d71176bc5f55d9a572fc576d02d67bf79def19b15"),
    ("commons-io/commons-io", "2.22.0", "2b9a7b1f726fb86216dbd2c8321eabe0221dbd5b1be81c18e1cb53811b104758"),
    ("org/apache/commons/commons-lang3", "3.21.0", "a2af247ec4db6eb8947e18e23e0433e4871a7c5d0a40e1f92e46ede54ae1defe"),
    ("commons-codec/commons-codec", "1.19.0", "5c3881e4f556855e9c532927ee0c9dfde94cc66760d5805c031a59887070af5f"),
    ("org/slf4j/slf4j-api", "2.0.17", "7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832"),
    ("junit/junit", "4.13.2", "8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3"),
    ("org/hamcrest/hamcrest-core", "1.3", "66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9"),
]


def main():
    test_dir = Path(__file__).resolve().parent
    root = test_dir.parents[8]
    relative = Path("com/termux/app/files/archive")
    with tempfile.TemporaryDirectory(prefix=".jvm-archives-", dir=test_dir) as work:
        work = Path(work)
        for artifact, version, expected in ARTIFACTS:
            name = artifact.rsplit("/", 1)[1]
            url = f"https://repo.maven.apache.org/maven2/{artifact}/{version}/{name}-{version}.jar"
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read(16 * 1024 * 1024)
            if hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeError(f"Dependency checksum mismatch: {artifact}:{version}")
            (work / (name + ".jar")).write_bytes(data)
        classes = work / "classes"
        classes.mkdir()
        sources = sorted((root / "app/src/main/java" / relative).glob("*.java"))
        sources += sorted(test_dir.glob("*Test.java"))
        subprocess.run(["javac", "--release", "8", "-Xlint:all,-options", "-cp", str(work / "*"),
                        "-d", str(classes), *map(str, sources)], check=True, cwd=root)
        subprocess.run(["java", "-Xmx192m", "-cp", str(work / "*") + ":" + str(classes),
                        "org.junit.runner.JUnitCore", "com.termux.app.files.archive.ArchiveToolsTest"],
                       check=True, cwd=root)


if __name__ == "__main__":
    main()
