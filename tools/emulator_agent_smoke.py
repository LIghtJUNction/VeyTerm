#!/usr/bin/env python3
"""Capture official Agent TUIs on an explicitly selected VeyTerm emulator."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time


AGENTS = ("claude", "codex", "pi", "agy", "cursor-agent", "grok")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--agent", choices=AGENTS, action="append")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--seconds", type=float, default=30,
                        help="Startup window per Agent, including cold filesystem reads")
    args = parser.parse_args()
    if not 5 <= args.seconds <= 120:
        parser.error("Use a startup window between 5 and 120 seconds")
    agents = args.agent or list(AGENTS)
    output = args.output or Path("build/verification") / datetime.now(timezone.utc).strftime("agents-%Y%m%dT%H%M%SZ")
    output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]

    def adb(*arguments, data=None, check=True):
        return subprocess.run(base + list(arguments), input=data, capture_output=True, check=check, timeout=30)

    def shell(*arguments, **kwargs):
        return adb("shell", shlex.join(arguments), **kwargs)

    def private(name):
        result = shell("run-as", "com.veyterm", "cat", "files/home/veyterm-agent-tui/" + name, check=False)
        return result.stdout if result.returncode == 0 else b""

    if shell("getprop", "ro.kernel.qemu").stdout.strip() != b"1":
        raise RuntimeError("An explicitly selected emulator is required")
    shell("run-as", "com.veyterm", "rm", "-f", "files/home/veyterm-agent-tui/status.json")
    shell("run-as", "com.veyterm", "/system/bin/sh", "-c",
          "cat > files/home/veyterm-agent-tui-probe.py",
          data=Path(__file__).with_name("agent_tui_probe.py").read_bytes())
    command = ["python3", "/data/data/com.veyterm/files/home/veyterm-agent-tui-probe.py"]
    command += ["--seconds", str(args.seconds)]
    for agent in agents:
        command += ["--agent", agent]
    driver = subprocess.Popen([
        sys.executable, str(Path(__file__).with_name("emulator_command.py")),
        "--serial", args.serial, "--name", "agents-tui", "--command", shlex.join(command),
        "--timeout", str(int(len(agents) * (args.seconds + 10) + 45)), "--output", str(output),
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    captured = set()
    while driver.poll() is None:
        try:
            status = json.loads(private("status.json"))
        except ValueError:
            status = {}
        agent = status.get("agent")
        if status.get("phase") == "capture" and agent not in captured:
            (output / (agent + ".png")).write_bytes(adb("exec-out", "screencap", "-p").stdout)
            (output / (agent + ".ansi")).write_bytes(private(agent + ".ansi"))
            captured.add(agent)
            print("Captured " + agent, flush=True)
        time.sleep(0.25)
    stdout, stderr = driver.communicate(timeout=5)
    if driver.returncode:
        raise RuntimeError(stdout.decode() + stderr.decode())
    result = private("result.json")
    (output / "result.json").write_bytes(result)
    evidence = json.loads(result)
    if captured != set(agents) or {item["agent"] for item in evidence} != set(agents):
        raise RuntimeError("Missing Agent captures")
    for item in evidence:
        if not item["alive_at_capture"] or item["bytes"] < 128:
            raise RuntimeError("Agent failed before its UI was captured: " + item["agent"])
    print("Agent PTY startup checks passed; inspect screenshots for UI acceptance", flush=True)


if __name__ == "__main__":
    main()
