#!/usr/bin/env python3
"""Launch official Agents through a PTY mirrored to the real Android terminal.

No model prompt or account credentials are supplied. Terminal query replies
travel back from VeyTerm to the child, so this exercises the actual renderer.
"""

import argparse
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import signal
import termios
import time
import tty


def write_all(descriptor, data):
    remaining = memoryview(data)
    while remaining:
        remaining = remaining[os.write(descriptor, remaining):]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent", choices=("claude", "codex", "pi", "agy", "cursor-agent", "grok"), action="append")
    parser.add_argument("--seconds", type=float, default=14)
    args = parser.parse_args()
    root = Path.home() / "veyterm-agent-tui"
    root.mkdir(exist_ok=True, mode=0o700)
    terminal = os.open("/dev/tty", os.O_RDWR)
    original = termios.tcgetattr(terminal)
    results = []
    try:
        tty.setraw(terminal)
        for agent in args.agent or ("claude", "codex", "pi", "agy", "cursor-agent", "grok"):
            workspace = root / (agent + "-workspace")
            workspace.mkdir(exist_ok=True)
            os.write(terminal, b"\033[?2026l\033[0m\033[2J\033[H")
            pid, master = pty.fork()
            if pid == 0:
                os.chdir(workspace)
                os.environ["PWD"] = str(workspace)
                os.execvp("veyterm-agents", ["veyterm-agents", agent])
            fcntl.ioctl(master, termios.TIOCSWINSZ, fcntl.ioctl(terminal, termios.TIOCGWINSZ, b"\0" * 8))
            started = time.monotonic()
            deadline = started + args.seconds
            raw = bytearray()
            status = None
            action_sent = False
            (root / "status.json").write_text(json.dumps({"agent": agent, "phase": "running"}))
            try:
                while time.monotonic() < deadline:
                    ready, _, _ = select.select([master, terminal], [], [], 0.1)
                    if master in ready:
                        try:
                            data = os.read(master, 65536)
                        except OSError:
                            break
                        if not data:
                            break
                        raw.extend(data)
                        write_all(terminal, data)
                    if terminal in ready:
                        write_all(master, os.read(terminal, 4096))
                    if not action_sent and time.monotonic() - started > 8:
                        if agent == "pi":
                            os.write(master, b"/hotkeys\r")
                        elif agent == "claude" and b"theme" in raw.lower() and b"log in" not in raw.lower():
                            os.write(master, b"\r")
                        action_sent = True
                    waited, child_status = os.waitpid(pid, os.WNOHANG)
                    if waited:
                        status = os.waitstatus_to_exitcode(child_status)
                        break
                if status is None:
                    waited, child_status = os.waitpid(pid, os.WNOHANG)
                    if waited:
                        status = os.waitstatus_to_exitcode(child_status)
                alive_at_capture = status is None
                (root / (agent + ".ansi")).write_bytes(raw)
                (root / "status.json").write_text(json.dumps({"agent": agent, "phase": "capture", "bytes": len(raw)}))
                time.sleep(3)
            finally:
                if status is None:
                    try:
                        os.write(master, b"\x03\x03")
                        time.sleep(0.3)
                        os.killpg(pid, signal.SIGTERM)
                    except (OSError, ProcessLookupError):
                        pass
                    for attempt in range(30):
                        waited, child_status = os.waitpid(pid, os.WNOHANG)
                        if waited:
                            status = os.waitstatus_to_exitcode(child_status)
                            break
                        time.sleep(0.1)
                    if status is None:
                        try:
                            os.killpg(pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                        _, child_status = os.waitpid(pid, 0)
                        status = os.waitstatus_to_exitcode(child_status)
                os.close(master)
                os.write(terminal, b"\033[?2026l\033[?1049l\033[0m\033[?25h")
            results.append({"agent": agent, "bytes": len(raw), "exit_status": status,
                            "alive_at_capture": alive_at_capture,
                            "synchronized_frames": raw.count(b"\033[?2026h")})
        (root / "result.json").write_text(json.dumps(results, indent=2) + "\n")
        (root / "status.json").write_text(json.dumps({"phase": "finished"}))
    finally:
        termios.tcsetattr(terminal, termios.TCSANOW, original)
        os.close(terminal)
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
