#!/usr/bin/env python3
"""Install a debug APK and exercise its actual terminal on an explicit emulator."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time


PACKAGE = "com.veyterm"
ACTIVITY = PACKAGE + "/com.termux.app.TermuxActivity"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--serial", required=True)
    freshness = parser.add_mutually_exclusive_group()
    freshness.add_argument("--reset", action="store_true", help="Clear this emulator's VeyTerm app data for a fresh-install test")
    freshness.add_argument("--replace-prefix", action="store_true", help="Retain the existing prefix as a fixture and extract a fresh one; keep home files")
    parser.add_argument("--repository-url", help="Use a temporary test mirror without changing the installed pacman.conf")
    parser.add_argument("--output", type=Path, default=Path("build/verification") / datetime.now(timezone.utc).strftime("emulator-%Y%m%dT%H%M%SZ"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*arguments, check=True, data=None, timeout=60):
        result = subprocess.run(["adb", "-s", args.serial, *arguments], input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace") + result.stdout.decode(errors="replace"))
        return result

    def shell(*arguments, **kwargs):
        return adb("shell", shlex.join(arguments), **kwargs)

    def read_private(name):
        result = shell("run-as", PACKAGE, "cat", "files/home/" + name, check=False)
        return result.stdout if result.returncode == 0 else b""

    def wait_for(predicate, seconds, description):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(2)
        raise TimeoutError(description)

    def launch():
        shell("am", "start", "-n", ACTIVITY)
        wait_for(lambda: re.search(rb"mCurrentFocus=.*com\.veyterm/", shell("dumpsys", "window").stdout),
                 60, "VeyTerm window did not appear")

    def type_command(command):
        shell("input", "text", command.replace(" ", "%s"))
        shell("input", "keyevent", "KEYCODE_ENTER")

    def wait_for_terminal():
        shell("run-as", PACKAGE, "rm", "-f", "files/home/veyterm-ready.log")
        for attempt in range(10):
            type_command("echo READY > /data/data/com.veyterm/files/home/veyterm-ready.log")
            try:
                wait_for(lambda: read_private("veyterm-ready.log").strip() == b"READY", 6,
                         "The terminal is not ready for input")
                return
            except TimeoutError:
                if attempt == 9:
                    raise

    def screenshot(name):
        (args.output / name).write_bytes(adb("exec-out", "screencap", "-p").stdout)

    if shell("getprop", "ro.kernel.qemu").stdout.strip() != b"1" and shell("getprop", "ro.boot.qemu").stdout.strip() != b"1":
        raise RuntimeError("The selected ADB target is not an Android emulator")
    print(adb("install", "-r", str(args.apk.resolve()), timeout=180).stdout.decode(), flush=True)
    if args.reset:
        shell("pm", "clear", PACKAGE)
    elif args.replace_prefix:
        shell("am", "force-stop", PACKAGE)
        for source, target in (("usr", "veyterm-prefix-fixture"),
                               ("usr.previous", "veyterm-prefix-previous-fixture")):
            if shell("run-as", PACKAGE, "/system/bin/sh", "-c", f"test -e files/{target}", check=False).returncode == 0:
                raise RuntimeError("A retained prefix fixture already exists: " + target)
            if shell("run-as", PACKAGE, "/system/bin/sh", "-c", f"test -e files/{source}", check=False).returncode == 0:
                shell("run-as", PACKAGE, "mv", "files/" + source, "files/" + target)
    launch()
    wait_for(lambda: shell("run-as", PACKAGE, "/system/bin/sh", "-c", "test -f files/usr/.veyterm-native-bootstrap-v2", check=False).returncode == 0,
             300, "Bootstrap validation marker did not appear")
    shell("run-as", PACKAGE, "rm", "-f", "files/home/veyterm-smoke.log", "files/home/veyterm-restart.log", "files/home/veyterm-ready.log")
    script = Path(__file__).with_name("smoke_terminal.sh").read_bytes()
    shell("run-as", PACKAGE, "/system/bin/sh", "-c", "cat > files/home/veyterm-smoke.sh", data=script)
    # The verified prefix can appear before the UI has attached its PTY.
    # Require an actual command round trip before sending the full test.
    wait_for_terminal()
    screenshot("first-launch.png")
    command = "bash /data/data/com.veyterm/files/home/veyterm-smoke.sh"
    if args.repository_url:
        command = "VEYTERM_TEST_REPOSITORY=" + shlex.quote(args.repository_url) + " " + command
    type_command(command)

    def smoke_result():
        log = read_private("veyterm-smoke.log")
        (args.output / "terminal.log").write_bytes(log)
        return re.search(rb"VEYTERM_SMOKE_RESULT=(\d+)", log)

    result = wait_for(smoke_result, 900, "Terminal smoke test did not finish")
    screenshot("terminal-result.png")
    if result[1] != b"0":
        raise RuntimeError("Terminal smoke test failed; inspect " + str(args.output / "terminal.log"))

    config = read_private(".zshrc")
    shell("am", "force-stop", PACKAGE)
    launch()
    wait_for_terminal()
    type_command("echo RESTART_OK > /data/data/com.veyterm/files/home/veyterm-restart.log")
    wait_for(lambda: read_private("veyterm-restart.log").strip() == b"RESTART_OK", 90,
             "The terminal did not accept commands after restart")
    if read_private(".zshrc") != config:
        raise RuntimeError("Application restart changed the user's zsh configuration")
    screenshot("restart.png")
    evidence = {
        "serial": args.serial, "apk": str(args.apk.resolve()),
        "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "fresh_app_data": args.reset, "terminal_smoke": "passed",
        "fresh_prefix": args.reset or args.replace_prefix,
        "retained_prefix": "files/veyterm-prefix-fixture" if args.replace_prefix else None,
        "test_repository": args.repository_url,
        "interactive_restart": "passed", "zshrc_preserved": True,
    }
    (args.output / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, indent=2), flush=True)


if __name__ == "__main__":
    main()
