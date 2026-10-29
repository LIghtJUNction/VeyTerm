#!/usr/bin/env bash
# Focused, offline JVM tests and optional Android-symbol source check; no Gradle/bootstrap.
set -euo pipefail
checks_dir="$(cd "$(dirname "$0")" && pwd)"
editor_root="$(git -C "$checks_dir" rev-parse --show-toplevel)"
editor_tmp="$(mktemp -d "$checks_dir/.editor-check.XXXXXX")"
trap 'rm -rf -- "$editor_tmp"' EXIT
junit="$(rg --files "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/junit/junit/4.13.2" | rg '/junit-4.13.2.jar$' | head -1)"
hamcrest="$(rg --files "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3" | rg '/hamcrest-core-1.3.jar$' | head -1)"
editor_src="$editor_root/app/src/main/java/com/termux/app/files/editor"
classpath="$junit:$hamcrest"
javac --release 8 -Xlint:all,-options -cp "$classpath" -d "$editor_tmp" \
    "$editor_src/EditorIo.java" "$editor_src/EditorLinks.java" "$editor_src/EditorCompare.java" "$editor_src/EditorHistory.java" "$checks_dir/"*Test.java
java -Djava.io.tmpdir="$editor_tmp" -cp "$editor_tmp:$classpath" org.junit.runner.JUnitCore \
    com.termux.app.files.editor.EditorIoTest com.termux.app.files.editor.EditorCompareTest com.termux.app.files.editor.EditorHistoryTest
if [[ -n "${EDITOR_ANDROID_JAR:-}" ]]; then
    # Generated IDs only type-check symbol references. This is NOT an Android resource/package build.
    python3 - "$editor_root" "$editor_tmp" <<'PY'
import pathlib,re,sys
root,out=map(pathlib.Path,sys.argv[1:])
colors=re.findall(r'<color name="([^"]+)"', (root/'app/src/main/res/values/colors.xml').read_text())
(out/'R.java').write_text('package com.termux; public final class R { public static final class color {\n'+''.join(f'public static final int {name}={i+1};\n' for i,name in enumerate(colors))+'}}\n')
PY
    javac --release 8 -Xlint:all,-options,-deprecation -cp "$EDITOR_ANDROID_JAR" -d "$editor_tmp" "$editor_tmp/R.java" "$editor_src/"*.java
    echo 'PASS: editor activity sources type-check against installed Android symbols (not an APK build).'
fi
