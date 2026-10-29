#!/usr/bin/env python3
"""Focused Java-8 source/SDK-36 symbol compile and real Smali DEX -> Java tests.

No Gradle, bootstrap, APK build, D8/R8, device, CI, or push. All generated files
and optional bounded downloads stay in this worktree's build/ and are removed.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import urllib.request
import zipfile

# Exact Android-compatible graph, intentionally not an unconstrained Maven resolver.
# Original jars, including both independently namespaced Smali families, are audited.
DEPENDENCIES = [
    ("io.github.skylot", "jadx-core", "1.5.6", "81237e3cc6eff6265643a08fa24caabb1ce614e4ed05daf8cfc18c906229c048"),
    ("io.github.skylot", "jadx-input-api", "1.5.6", "3fbb2e70c6dcc4f02a39d9185560f779fd1042e053af903eb4b764baf58173ef"),
    ("io.github.skylot", "jadx-zip", "1.5.6", "5bd0967715c6502cd5c0a75d732358e9469796d402a928f8852b504cc16f9cef"),
    ("io.github.skylot", "jadx-dex-input", "1.5.6", "3b6adf6a38a9db2b480a28d8675124907073fb22d691556e96afde12a864c627"),
    ("org.slf4j", "slf4j-api", "2.0.17", "7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832"),
    ("com.google.code.gson", "gson", "2.14.0", "2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f"),
    ("com.android.tools.smali", "smali-baksmali", "3.0.9", "e7d0166945565a87f3997d1aea8779202dbb6cb0585a39ee20bab8a2f5ba87f6"),
    ("com.android.tools.smali", "smali-dexlib2", "3.0.9", "8b547506f62f91d74b70f4beb4989b99e1ff61fc50b2525e9796ca35f0a351dc"),
    ("com.android.tools.smali", "smali-util", "3.0.9", "4673762894ba4156081fc5c642f74d6e78aef8b1a9c7702d691e3171d8cc1153"),
    ("com.google.guava", "guava", "27.1-android", "686404f2d1d4d221911f96bd627ff60dac2226a5dfa6fb8ba517073eb97ec0ef"),
    ("com.google.guava", "failureaccess", "1.0.1", "a171ee4c734dd2da837e4b16be9df4661afab72a41adaf31eb84dfdaf936ca26"),
    ("org.smali", "dexlib2", "2.5.2", "5a5c8982d8bd7d6e3bb1a0713049e3c78b719ec32b20f6b619885cec30a0dd61"),
    ("org.smali", "smali", "2.5.2", "136c5c4653d6531bd7b6f10f35f8691cb96432e727d30b4d5579826ee01e9419"),
    ("org.smali", "baksmali", "2.5.2", "1ed236266d7dc4907aade0b19a34f77efac25342b63c8ace52e579039941b389"),
    ("org.smali", "util", "2.5.2", "4f580a9cff3ebb83cb3fd20bec88e37e4f183796ca652732992611620282daea"),
    ("org.antlr", "antlr-runtime", "3.5.2", "ce3fc8ecb10f39e9a3cddcbb2ce350d272d9cd3d0b1e18e6fe73c3b9389c8734"),
    ("com.beust", "jcommander", "1.64", "156be736199c990321d9ff77090b199629cfc9865e2d6c13f7cd291bb1641817"),
    ("com.android.tools", "desugar_jdk_libs_configuration_nio", "2.1.5", "7db51661cd07d1fd5cf12769a75ba201910624bb3801c09363dd3cb28e31c51b"),
]


def run(command):
    subprocess.run([str(s) for s in command], check=True)


def class_constants(data):
    """Only JVM constant-pool symbols, to audit selected-version member linkage."""
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    pos, i = 10, 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            pool[i] = (tag, data[pos:pos + length].decode("utf-8", errors="replace"))
            pos += length
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag, *struct.unpack_from(">HH", data, pos))
            pos += 4
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"Unexpected JVM constant tag {tag}")
        i += 1
    for item in pool:
        if item and item[0] in (9, 10, 11):
            owner = pool[pool[item[1]][1]][1]
            member = pool[item[2]]
            yield owner, pool[member[1]][1], pool[member[2]][1], "field" if item[0] == 9 else "method"


def audit(jars, directory):
    classes, duplicates, symbols, majors = {}, [], set(), {}
    for jar in jars:
        if "configuration_nio" in jar.name:
            continue
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if not name.endswith(".class") or name.startswith("META-INF/") or name == "module-info.class":
                    continue
                if name in classes:
                    duplicates.append(f"{name}: {classes[name]}, {jar.name}")
                classes[name] = jar.name
                data = archive.read(name)
                majors.setdefault(jar.name, set()).add(struct.unpack_from(">H", data, 6)[0])
                if jar.name.startswith(("jadx-", "smali-")):
                    symbols.update(s for s in class_constants(data) if s[0].startswith(("com/google/common/", "org/slf4j/")))
    if duplicates:
        raise RuntimeError("Duplicate classes:\n" + "\n".join(duplicates))
    for name, values in majors.items():
        if max(values) > 55:
            raise RuntimeError(f"Dependency bytecode newer than Java 11: {name}: {values}")
    (directory / "symbols.tsv").write_text("".join("\t".join(s) + "\n" for s in sorted(symbols)))
    print(f"PASS: {len(classes)} dependency classes, no duplicate definitions; JADX major 55 (Java 11), app sources compile at Java 8", flush=True)
    with zipfile.ZipFile(next(j for j in jars if "configuration_nio" in j.name)) as archive:
        supported = archive.read("META-INF/desugar/d8/lint/compile_api_level_30/desugared_apis_30_21.txt").decode()
        required = ["java/lang/String#repeat(I)", "java/io/File#toPath()", "java/nio/file/Files#readString(",
                    "java/nio/file/Files#writeString(", "java/nio/file/Path\n", "java/util/List#of(", "java/util/Set#of("]
        for symbol in required:
            if symbol not in supported:
                raise RuntimeError(f"NIO 2.1.5 min21 configuration lacks {symbol}")
    print("PASS: NIO 2.1.5 min21 API list includes the Java-11/NIO APIs used by this path; device execution remains untested", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependency-dir", type=Path, action="append", default=[])
    parser.add_argument("--download", action="store_true", help="Allow up to 32 MiB of pinned dependency downloads")
    parser.add_argument("--android-jar", type=Path, default=Path(os.environ.get("ANDROID_HOME", "/opt/android-sdk")) / "platforms/android-36/android.jar")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    repo = next(p for p in here.parents if (p / "settings.gradle").is_file())
    if not args.android_jar.is_file():
        parser.error("Missing installed SDK 36 android.jar; use --android-jar. No SDK is downloaded.")
    build = repo / "build"
    build.mkdir(exist_ok=True)
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    with tempfile.TemporaryDirectory(prefix="dec-validation-", dir=build) as name:
        temp = Path(name)
        jars, downloaded = [], 0
        for group, artifact, version, expected in DEPENDENCIES:
            leaf = f"{artifact}-{version}.jar"
            local = next((p for root in args.dependency_dir for p in root.rglob(leaf)), None)
            if local is None:
                local = next((cache / group / artifact / version).glob("*/*.jar"), None)
            if local is None:
                if not args.download:
                    parser.error(f"Missing {group}:{artifact}:{version}; pass --dependency-dir or --download")
                base = "https://dl.google.com/dl/android/maven2" if group.startswith("com.android.") else "https://repo.maven.apache.org/maven2"
                local = temp / leaf
                with urllib.request.urlopen(f"{base}/{group.replace('.', '/')}/{artifact}/{version}/{leaf}", timeout=30) as response, local.open("wb") as out:
                    while chunk := response.read(65536):
                        downloaded += len(chunk)
                        if downloaded > 32 * 1024 * 1024:
                            raise RuntimeError("Dependency download exceeded the 32 MiB task budget")
                        out.write(chunk)
            if hashlib.sha256(local.read_bytes()).hexdigest() != expected:
                raise RuntimeError(f"Pinned SHA-256 mismatch: {local}")
            jars.append(local.resolve())
        audit(jars, temp)
        runtime = [j for j in jars if "configuration_nio" not in j.name]
        cp = os.pathsep.join(map(str, runtime))
        classes = temp / "classes"
        # Generated R IDs only type-check actual names from these scoped resources.
        colors = re.findall(r'<color name="([^"]+)"', (repo / "app/src/main/res/values/dec_colors.xml").read_text())
        resource = temp / "R.java"
        resource.write_text("package com.termux; public final class R { public static final class color {" +
                            "".join(f"public static final int {color}={i + 1};" for i, color in enumerate(colors)) + "}}")
        source_dir = repo / "app/src/main/java/com/termux/app/files/decompile"
        run(["javac", "-source", "8", "-target", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", cp + os.pathsep + str(args.android_jar),
             "-d", classes, resource, *sorted(source_dir.glob("*.java"))])
        test_cp = str(classes) + os.pathsep + cp
        tests = [here / n for n in ("DecompilerRoundTripTest.java", "NoDesktopRunner.java", "DependencyLinkageTest.java")]
        run(["javac", "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", test_cp, "-d", classes, *tests])
        run(["java", "-cp", test_cp, "com.termux.app.files.decompile.DependencyLinkageTest", temp / "symbols.tsv"])
        run(["java", "-Xmx512m", f"-Djava.io.tmpdir={temp}", "-cp", test_cp,
             "com.termux.app.files.decompile.NoDesktopRunner", temp / "fixtures"])
        exported = temp / "exported"
        run(["javac", "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-d", exported,
             *sorted((temp / "fixtures/java/fixture").glob("*.java")), here / "ExportedJavaTest.java"])
        run(["java", "-cp", exported, "com.termux.app.files.decompile.ExportedJavaTest"])
        print(f"PASS: 3 production Java files compile against SDK symbols; downloaded {downloaded} bytes. All generated validation files removed on exit.")


if __name__ == "__main__":
    main()
