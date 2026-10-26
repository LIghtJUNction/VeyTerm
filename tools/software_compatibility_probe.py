#!/usr/bin/env python3
"""Install and exercise development software through VeyTerm's real shell."""

import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile


def host(serial, output):
    adb = ["adb", "-s", serial]
    if subprocess.check_output(adb + ["shell", "getprop", "ro.kernel.qemu"]).strip() != b"1":
        raise RuntimeError("Select an emulator explicitly")
    source = "/data/data/com.veyterm/files/home/veyterm-software-probe.py"
    subprocess.run(adb + ["shell", "run-as", "com.veyterm", "/system/bin/sh", "-c",
                         shlex.quote("cat > " + source)],
                   input=Path(__file__).read_bytes(), check=True)
    subprocess.run(adb + ["shell", "am", "start", "-n", "com.veyterm/com.termux.app.TermuxActivity"],
                   check=True, capture_output=True)
    result = subprocess.run([
        sys.executable, str(Path(__file__).with_name("emulator_command.py")),
        "--serial", serial, "--name", "software-compatibility",
        "--command", "python3 " + source, "--timeout", "1200", "--output", str(output),
    ])
    report = subprocess.check_output(adb + ["shell", "run-as", "com.veyterm", "cat",
                                            "files/home/veyterm-software-compatibility/result.json"])
    output.mkdir(parents=True, exist_ok=True)
    (output / "result.json").write_bytes(report)
    cases = json.loads(report)
    for case in cases:
        data = subprocess.check_output(adb + ["shell", "run-as", "com.veyterm", "cat",
                                               "files/home/veyterm-software-compatibility/" + case["log"]])
        (output / case["log"]).write_bytes(data)
        print(f"{case['environment']}: {case['name']}: {case['status']}")
    return result.returncode


def device():
    if os.getuid() == 0 or os.environ.get("PREFIX") != "/data/data/com.veyterm/files/usr":
        raise RuntimeError("Run inside VeyTerm as its ordinary app UID")
    output = Path.home() / "veyterm-software-compatibility"
    output.mkdir(exist_ok=True)
    cases = []

    def check(name, environment, script, *, wheel_limit=False, timeout=240):
        command = ["bash", "-s"]
        if environment == "linux":
            command = ["veyterm-agents", "/bin/bash", "-s"]
        try:
            result = subprocess.run(command, input="set -euo pipefail\n" + script,
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    timeout=timeout)
            text, code = result.stdout, result.returncode
        except subprocess.TimeoutExpired as error:
            text, code = (error.stdout or b"").decode(errors="replace"), 124
        status = "passed" if code == 0 else "failed"
        network_errors = ("Retrying", "SSLError", "ConnectionError", "Could not fetch URL", "ReadTimeout")
        if (wheel_limit and code == 1 and "No matching distribution found for ruff" in text
                and not any(error in text for error in network_errors)):
            status = "unsupported-wheel"
        log = name + ".log"
        (output / log).write_text(text)
        cases.append(dict(name=name, environment=environment, status=status, exit_code=code, log=log))
        (output / "result.json").write_text(json.dumps(cases, indent=2) + "\n")
        print(f"{environment}: {name}: {status}", flush=True)
        return code == 0

    with tempfile.TemporaryDirectory(prefix="veyterm-software-", dir=os.environ["TMPDIR"]) as work:
        os.chdir(work)
        native_ready = check("native-packages", "native", r"""
pacman -S --noconfirm --needed python-pip git make nano
python3 --version
python3 -m pip --version
git --version
make --version
nano --version
""")
        if native_ready:
            check("native-python", "native", r"""
python3 -m venv --without-pip --system-site-packages native-venv
native-venv/bin/python -m pip install --no-cache-dir requests httpx rich
native-venv/bin/python -m pip check
native-venv/bin/python -m pip list --format=json
native-venv/bin/python - <<'PY'
import httpx, requests
from rich.console import Console
response = httpx.get('https://pypi.org/pypi/httpx/json', timeout=30)
response.raise_for_status()
assert response.json()['info']['name'] == 'httpx'
assert requests.Request('GET', 'https://example.com').prepare().method == 'GET'
Console(force_terminal=True).print('[green]NATIVE_PYTHON_INSTALL_AND_TLS_OK[/green]')
PY
""")
            check("native-ruff-wheel", "native", r"""
python3 -m pip install --no-cache-dir --no-deps --only-binary=:all: --target ruff-probe ruff
PYTHONPATH="$PWD/ruff-probe" python3 -m ruff --version
""", wheel_limit=True)

        linux_ready = check("linux-packages", "linux", r"""
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends python3-venv cmake ninja-build ripgrep fd-find sqlite3
python3 --version
cmake --version
ninja --version
rg --version
fdfind --version
sqlite3 --version
""", timeout=420)
        if linux_ready:
            check("linux-python-wheels", "linux", r"""
python3 -m venv linux-venv
linux-venv/bin/python -m pip install --no-cache-dir requests httpx rich ruff uv
linux-venv/bin/python -m pip check
linux-venv/bin/python -m pip list --format=json
linux-venv/bin/ruff --version
linux-venv/bin/uv --version
printf 'answer = 42\n' > good.py
linux-venv/bin/ruff check --select F good.py
printf 'print(missing_name)\n' > bad.py
if linux-venv/bin/ruff check --select F bad.py > ruff-rejection.log 2>&1; then exit 1; fi
grep -q F821 ruff-rejection.log
linux-venv/bin/uv venv --python /usr/bin/python3 uv-venv
linux-venv/bin/uv pip install --python uv-venv/bin/python packaging
uv-venv/bin/python -c 'from packaging.version import Version; assert Version("2.0") > Version("1.0"); print("LINUX_BINARY_WHEELS_AND_UV_OK")'
""")
            check("linux-cmake-ninja", "linux", r"""
mkdir -p cpp
cat > cpp/CMakeLists.txt <<'CMAKE'
cmake_minimum_required(VERSION 3.20)
project(VeyTermCompatibility LANGUAGES CXX)
add_executable(hello main.cpp)
target_compile_features(hello PRIVATE cxx_std_17)
CMAKE
cat > cpp/main.cpp <<'CPP'
#include <iostream>
#include <numeric>
#include <vector>
int main() {
    const std::vector<int> values{2, 3, 5};
    std::cout << "CMAKE_NINJA_CPP_OK=" << std::accumulate(values.begin(), values.end(), 0) << '\n';
}
CPP
cmake -S cpp -B cpp/build -G Ninja
cmake --build cpp/build --parallel 2
cpp/build/hello | grep -x CMAKE_NINJA_CPP_OK=10
""")
            check("linux-search-sqlite", "linux", r"""
rg --files cpp | grep -x cpp/main.cpp
fdfind --type f '^main.cpp$' cpp | grep -x cpp/main.cpp
sqlite3 sample.db 'CREATE TABLE values_test(n); INSERT INTO values_test VALUES(2),(3),(5); SELECT SUM(n) FROM values_test;' | grep -x 10
echo SEARCH_AND_SQLITE_OK
""")

        check("linux-node-build", "linux", r"""
mkdir -p node-project
cd node-project
npm install --no-audit --no-fund --save-exact --cache ../npm-cache typescript esbuild tsx
node --version
npm --version
npm ls --depth=0
cat > main.ts <<'TS'
const values: number[] = [2, 3, 5];
console.log('TYPESCRIPT_EXECUTION_OK=' + values.reduce((sum, value) => sum + value, 0));
TS
node_modules/.bin/tsc main.ts --target ES2022 --module commonjs --outDir dist
node dist/main.js | grep -x TYPESCRIPT_EXECUTION_OK=10
node_modules/.bin/esbuild main.ts --bundle --platform=node --outfile=bundle.js
node bundle.js | grep -x TYPESCRIPT_EXECUTION_OK=10
node_modules/.bin/tsx main.ts | grep -x TYPESCRIPT_EXECUTION_OK=10
echo NPM_TYPESCRIPT_ESBUILD_TSX_OK
""")
        check("official-agents", "linux", r"""
for agent in claude codex pi agy cursor-agent grok; do
    printf 'AGENT_VERSION[%s]=' "$agent"
    "$agent" --version
done
""")
    failures = [case for case in cases if case["status"] == "failed"]
    print(f"SOFTWARE_COMPATIBILITY_FAILURES={len(failures)}", flush=True)
    return bool(failures)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path, default=Path("build/verification/software-compatibility"))
    args = parser.parse_args()
    return host(args.serial, args.output) if args.serial else device()


if __name__ == "__main__":
    raise SystemExit(main())
