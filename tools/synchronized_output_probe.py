#!/usr/bin/env python3
"""Check live terminal query replies and recovery from an unfinished frame."""

import os
import select
import termios
import time
import tty


descriptor = os.open("/dev/tty", os.O_RDWR)
old = termios.tcgetattr(descriptor)
try:
    tty.setraw(descriptor)

    def query():
        os.write(descriptor, b"\033[?2026$p")
        response = b""
        deadline = time.monotonic() + 0.8
        while not response.endswith(b"$y") and time.monotonic() < deadline:
            if select.select([descriptor], [], [], max(0, deadline - time.monotonic()))[0]:
                response += os.read(descriptor, 1024)
        return response

    assert query() == b"\033[?2026;2$y"
    os.write(descriptor, b"\033[?2026h")
    assert query() == b"\033[?2026;1$y", "Queries must be answered during a frame"
    time.sleep(0.6)
    os.write(descriptor, b"\033[?2026h")
    time.sleep(0.6)
    assert query() == b"\033[?2026;2$y", "A repeated begin must not extend the timeout"
    os.write(descriptor, b"\033[?2026h\033[?2026l")
    assert query() == b"\033[?2026;2$y"
    os.write(descriptor, b"\033[?2026h")
    time.sleep(0.6)
    os.write(descriptor, b"\033[?2026l\033[?2026h")
    time.sleep(0.6)
    assert query() == b"\033[?2026;1$y", "A finished frame's deadline must not end a new frame"
    time.sleep(0.5)
    assert query() == b"\033[?2026;2$y"
finally:
    os.write(descriptor, b"\033[?2026l")
    termios.tcsetattr(descriptor, termios.TCSANOW, old)
    os.close(descriptor)
print("LIVE_SYNCHRONIZED_OUTPUT_AND_TIMEOUT_OK")
