#!/usr/bin/env python3
"""Run a fixture through VeyTerm's visible PTY, keeping its output on the host."""

import argparse
from pathlib import Path
import re
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--name", required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--script", type=Path)
    source.add_argument("--command")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--output", type=Path, default=Path("build/verification"))
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]+", args.name):
        parser.error("Use a lowercase fixture name with letters, numbers and hyphens")

    def shell(*arguments, data=None, check=True):
        return subprocess.run(["adb", "-s", args.serial, "shell", shlex.join(arguments)],
                              input=data, capture_output=True, check=check, timeout=30)

    if shell("getprop", "ro.kernel.qemu").stdout.strip() != b"1":
        raise RuntimeError("An explicitly selected emulator is required")
    home = "/data/data/com.veyterm/files/home"
    stem = "veyterm-" + args.name
    log = f"files/home/{stem}.log"
    shell("run-as", "com.veyterm", "rm", "-f", log)
    body = args.script.read_text() if args.script else args.command + "\n"
    script = (f'exec > "{home}/{stem}.log" 2>&1\n'
              'trap \'printf "\\nVEYTERM_COMMAND_RESULT=%s\\n" "$?"\' EXIT\n'
              'set -e\n(\n' + body + '\n)\n')
    shell("run-as", "com.veyterm", "/system/bin/sh", "-c",
          f"cat > files/home/{stem}.sh", data=script.encode())
    ready_file = f"files/home/{stem}.ready"
    shell("run-as", "com.veyterm", "rm", "-f", ready_file)
    for attempt in range(10):
        ready_command = f"echo READY > {home}/{stem}.ready"
        shell("input", "text", ready_command.replace(" ", "%s"))
        shell("input", "keyevent", "KEYCODE_ENTER")
        time.sleep(2)
        if shell("run-as", "com.veyterm", "cat", ready_file, check=False).stdout.strip() == b"READY":
            break
    else:
        raise RuntimeError("The terminal did not complete a command round trip")
    command = f"bash {home}/{stem}.sh"
    shell("input", "text", command.replace(" ", "%s"))
    shell("input", "keyevent", "KEYCODE_ENTER")
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output / f"{stem}.log"
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        data = shell("run-as", "com.veyterm", "cat", log, check=False).stdout
        destination.write_bytes(data)
        result = re.search(rb"^VEYTERM_COMMAND_RESULT=(\d+)$", data, re.M)
        if result:
            code = int(result[1])
            print(f"{args.name}: exit {code}; log {destination}", flush=True)
            return code
        time.sleep(2)
    print(f"{args.name}: still running after {args.timeout}s; log {destination}", flush=True)
    return 124


if __name__ == "__main__":
    raise SystemExit(main())
