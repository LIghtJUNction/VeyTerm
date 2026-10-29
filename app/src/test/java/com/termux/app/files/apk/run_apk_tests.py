#!/usr/bin/env python3
"""Compile only the APK worker's Java and run real format tests. Never invokes Gradle or adb.

Resolve jars from --dependency-dir and the ordinary Gradle cache. --download is an
explicit, bounded Maven-Central fallback for smali/ARSCLib and their small runtime
libraries. All test keys, fixtures, classes and downloads are removed on exit.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

COORDINATES = [
    ("io.github.reandroid", "ARSCLib", "1.4.0"),
    ("org.smali", "dexlib2", "2.5.2"),
    ("org.smali", "smali", "2.5.2"),
    ("org.smali", "baksmali", "2.5.2"),
    ("org.smali", "util", "2.5.2"),
    ("org.antlr", "antlr-runtime", "3.5.2"),
    ("com.beust", "jcommander", "1.64"),
    ("com.google.guava", "guava", "27.1-android"),
    ("com.google.guava", "failureaccess", "1.0.1"),
    ("com.android.tools.build", "apksig", "8.13.2"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependency-dir", type=Path, action="append", default=[])
    parser.add_argument("--gradle-cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    parser.add_argument("--android-jar", type=Path, default=Path(os.environ.get("ANDROID_HOME", "/opt/android-sdk")) / "platforms/android-36/android.jar")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    repo = next(p for p in here.parents if (p / "settings.gradle").is_file())
    sources = sorted((repo / "app/src/main/java/com/termux/app/files/apk").glob("*.java"))
    sources.append(here / "ApkRoundTripTest.java")
    if not args.android_jar.is_file():
        parser.error("Missing Android compile API jar; pass --android-jar (no SDK is downloaded)")
    (repo / "build/apk-advanced-check").mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".apk-validation-", dir=repo / "build/apk-advanced-check") as name:
        temp = Path(name)
        jars = []
        downloaded = 0
        for group, artifact, version in COORDINATES:
            leaf = f"{artifact}-{version}.jar"
            local = next((p for root in args.dependency_dir for p in root.rglob(leaf)), None)
            if local is None:
                local = next((args.gradle_cache / group / artifact / version).glob("*/*.jar"), None)
            if local is None:
                if not args.download:
                    parser.error(f"Missing {group}:{artifact}:{version}; use --dependency-dir or --download")
                base = "https://dl.google.com/dl/android/maven2" if group == "com.android.tools.build" else "https://repo.maven.apache.org/maven2"
                url = f"{base}/{group.replace('.', '/')}/{artifact}/{version}/{leaf}"
                local = temp / leaf
                with urllib.request.urlopen(url, timeout=30) as response, local.open("wb") as out:
                    while chunk := response.read(65536):
                        downloaded += len(chunk)
                        if downloaded > 32 * 1024 * 1024:
                            raise RuntimeError("Dependency download exceeds the 32 MiB task budget")
                        out.write(chunk)
            jars.append(local.resolve())
        aar = next((args.gradle_cache / "androidx.core/core/1.13.1").glob("*/*.aar"), None)
        core = next((p for root in args.dependency_dir for p in root.rglob("androidx-core.jar")), None)
        if core is None:
            if aar is None:
                parser.error("Missing cached androidx.core:core:1.13.1 AAR / androidx-core.jar; parent Android build resolves this existing dependency")
            core = temp / "androidx-core.jar"
            with zipfile.ZipFile(aar) as archive:
                core.write_bytes(archive.read("classes.jar"))
        jars += [core.resolve()]
        # ARSCLib bundles host stubs for android.* and XmlPull. Android must use its own APIs.
        arsc = next(p for p in jars if p.name == "ARSCLib-1.4.0.jar")
        android_arsc = temp / "ARSCLib-1.4.0-android.jar"
        with zipfile.ZipFile(arsc) as source, zipfile.ZipFile(android_arsc, "w", zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                if not item.filename.startswith(("android/", "org/xmlpull/")):
                    target.writestr(item, source.read(item))
        classes = temp / "classes"
        classpath = os.pathsep.join(map(str, jars))
        android_cp = os.pathsep.join(str(android_arsc if p == arsc else p) for p in jars) + os.pathsep + str(args.android_jar.resolve())
        subprocess.run(["javac", "-J-Xmx256m", "-J-Djava.io.tmpdir=" + str(temp), "-source", "8", "-target", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", android_cp, "-d", str(classes), *map(str, sources[:-1])], check=True)
        subprocess.run(["javac", "-J-Xmx256m", "-J-Djava.io.tmpdir=" + str(temp), "-source", "8", "-target", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", str(classes) + os.pathsep + classpath, "-d", str(classes), str(sources[-1])], check=True)
        store = temp / "test.p12"
        for alias in ("test-key", "test-other"):
            subprocess.run(["keytool", "-J-Xmx128m", "-J-Djava.io.tmpdir=" + str(temp), "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-dname", "CN=Ephemeral APK test", "-storetype", "PKCS12", "-keystore", str(store), "-storepass", "test-password", "-keypass", "test-password"], check=True)
        subprocess.run(["java", "-Xmx256m", "-Djava.io.tmpdir=" + str(temp), "-cp", str(classes) + os.pathsep + classpath, "com.termux.app.files.apk.ApkRoundTripTest", str(temp / "fixtures"), str(store)], check=True)
        print(f"Standalone compile passed for {len(sources) - 1} production Java files; downloaded {downloaded} bytes; ephemeral data cleaned on exit.")


if __name__ == "__main__":
    main()
