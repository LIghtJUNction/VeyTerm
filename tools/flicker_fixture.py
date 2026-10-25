#!/usr/bin/env python3
"""Deliberately split synchronized TUI frames across slow PTY writes."""

import os
import time


with open("/dev/tty", "wb", buffering=0) as terminal:
    columns, rows = os.get_terminal_size(terminal.fileno())

    def paint(frame):
        color = 24 if frame % 2 else 29
        line = (f" FRAME {frame:03d} ".ljust(columns))[:columns]
        return (f"\033[48;5;{color}m\033[97m" + "".join(
            f"\033[{row + 1};1H{line}" for row in range(rows))).encode()

    terminal.write(b"\033[?1049h\033[?25l")
    try:
        terminal.write(paint(0))
        time.sleep(1)
        for frame in range(1, 61):
            terminal.write(b"\033[?2026h\033[0m\033[2J\033[H")
            time.sleep(0.04)
            data = paint(frame)
            middle = len(data) // 2
            terminal.write(data[:middle])
            time.sleep(0.025)
            terminal.write(data[middle:])
            terminal.write(b"\033[?2026l")
            time.sleep(0.035)
        time.sleep(1)
    finally:
        terminal.write(b"\033[?2026l\033[0m\033[?25h\033[?1049l")
print("FLICKER_FIXTURE_COMPLETED")
