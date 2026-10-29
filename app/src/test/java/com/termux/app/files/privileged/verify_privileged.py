#!/usr/bin/env python3
"""Run host tests; optionally AIDL + javac type-check only (no Gradle/bootstrap/APK).

All downloaded artifacts and compiler outputs live under this owned test directory
and are removed on success or failure. Each download is capped at 8 MiB.
"""
import argparse
import io
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[8]
PACKAGE = Path("com/termux/app/files/privileged")


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def artifact(url, destination, aar=False):
    request = urllib.request.Request(url, headers={"User-Agent": "VeyTerm-privileged-verification"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError("Verification download exceeded 8 MiB: " + url)
    if aar:
        data = zipfile.ZipFile(io.BytesIO(data)).read("classes.jar")
    destination.write_bytes(data)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-source", type=Path, default=REPO / "app/src/main/java")
    parser.add_argument("--typecheck", action="store_true")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", "/opt/android-sdk")))
    args = parser.parse_args()
    sources = REPO / "app/src/main/java" / PACKAGE
    core = args.core_source / "com/termux/app/files/fs"
    with tempfile.TemporaryDirectory(prefix=".priv-check-", dir=HERE) as temporary:
        scratch = Path(temporary)
        central = "https://repo.maven.apache.org/maven2"
        junit = artifact(central + "/junit/junit/4.13.2/junit-4.13.2.jar", scratch / "junit.jar")
        hamcrest = artifact(central + "/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar", scratch / "hamcrest.jar")
        classes = scratch / "classes"
        classes.mkdir()
        classpath = os.pathsep.join(map(str, [junit, hamcrest, classes]))
        run("javac", "--release", "8", "-Xlint:-options", "-cp", classpath, "-d", classes,
            sources / "PrivilegedPaths.java", sources / "KernelIdentity.java", sources / "MountRoots.java",
            core / "FileLocation.java", HERE / "PrivilegedCoreTest.java")
        run("java", "-Djava.io.tmpdir=" + str(scratch), "-cp", classpath,
            "org.junit.runner.JUnitCore", "com.termux.app.files.privileged.PrivilegedCoreTest")
        if args.typecheck:
            jars = []
            for module in ("core", "service"):
                url = f"https://jitpack.io/com/github/topjohnwu/libsu/{module}/6.0.0/{module}-6.0.0.aar"
                jars.append(artifact(url, scratch / f"libsu-{module}.jar", aar=True))
            for module in ("api", "aidl", "shared", "provider"):
                url = f"{central}/dev/rikka/shizuku/{module}/13.1.5/{module}-13.1.5.aar"
                jars.append(artifact(url, scratch / f"shizuku-{module}.jar", aar=True))
            jars.append(artifact("https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar",
                scratch / "annotations.jar"))
            platform = args.sdk / "platforms/android-36"
            generated = scratch / "generated"
            generated.mkdir()
            run(args.sdk / "build-tools/36.0.0/aidl", "--lang=java", "--min_sdk_version=21",
                "-p", platform / "framework.aidl", "-I", REPO / "app/src/main/aidl",
                "-o", generated, REPO / "app/src/main/aidl" / PACKAGE / "IPrivilegedFiles.aidl")
            jars.append(platform / "android.jar")
            run("javac", "--release", "8", "-Xlint:unchecked", "-Xlint:-options", "-cp", os.pathsep.join(map(str, jars)),
                "-d", classes, *sorted(sources.glob("*.java")),
                generated / PACKAGE / "IPrivilegedFiles.java",
                *(core / name for name in ("FileAccess.java", "FileLocation.java", "FileEntry.java", "LocalFileAccess.java")))
            print("PASS: generated AIDL and isolated Java type-check against real libsu 6.0.0 / Shizuku 13.1.5 AARs")


if __name__ == "__main__":
    main()
