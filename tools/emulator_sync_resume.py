#!/usr/bin/env python3
"""Check synchronized frames across background/resume on a selected emulator."""

import argparse
import io
import json
from pathlib import Path
import shlex
import subprocess
import time

from PIL import Image


FIXTURE = r'''
import os
from pathlib import Path
import select
import termios
import time
import tty

root = Path.home()
with open('/dev/tty', 'r+b', buffering=0) as terminal:
    settings = termios.tcgetattr(terminal)
    tty.setraw(terminal)

    def parsed():
        terminal.write(b'\033[6n')
        response = b''
        deadline = time.monotonic() + 5
        while not response.endswith(b'R'):
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([terminal], [], [], remaining)[0]:
                raise RuntimeError('Terminal did not acknowledge output')
            response += os.read(terminal.fileno(), 128)

    columns, rows = os.get_terminal_size(terminal.fileno())

    def paint(color):
        return (f'\033[48;5;{color}m\033[2J\033[H' + ' ' * columns).encode()

    terminal.write(b'\033[?1049h\033[?25l')
    try:
        if CASE == 'stale':
            terminal.write(paint(24))
            time.sleep(.2)
            terminal.write(b'\033[?2026h' + paint(29) + b'\033[?2026l')
            time.sleep(.2)
        terminal.write(paint(29))
        time.sleep(.2)
        (root / 'sync-resume-ready').touch()
        while not (root / 'sync-resume-go').exists():
            time.sleep(.01)
        terminal.write(b'\033[?2026h\033[0m\033[2J\033[H\033[41mHALF FRAME')
        parsed()
        (root / 'sync-resume-started').touch()
        while not (root / 'sync-resume-finish').exists():
            time.sleep(.01)
        terminal.write(paint(24) + b'\033[?2026l')
        parsed()
        (root / 'sync-resume-ended').touch()
        while not (root / 'sync-resume-exit').exists():
            time.sleep(.01)
    finally:
        terminal.write(b'\033[?2026l\033[0m\033[?25h\033[?1049l')
        termios.tcsetattr(terminal, termios.TCSANOW, settings)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*parts, data=None):
        return subprocess.run(['adb', '-s', args.serial, *parts], input=data,
                              capture_output=True, check=True, timeout=20).stdout

    def shell(*parts, data=None):
        return adb('shell', shlex.join(parts), data=data)

    def private(*parts, data=None):
        return shell('run-as', 'com.veyterm', *parts, data=data)

    def launch():
        shell('am', 'start', '-n', 'com.veyterm/com.termux.app.TermuxActivity')

    def wait(name):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if private('sh', '-c', f'test -f files/home/sync-resume-{name} && printf ready || true') == b'ready':
                return
            time.sleep(.01)
        raise RuntimeError(f'Timed out waiting for {name}')

    def pixel(name):
        image = adb('exec-out', 'screencap', '-p')
        (args.output / f'{name}.png').write_bytes(image)
        return list(Image.open(io.BytesIO(image)).convert('RGB').getpixel((180, 300)))

    if shell('getprop', 'ro.kernel.qemu').strip() != b'1':
        raise RuntimeError('Only an explicitly selected emulator is supported')
    report = {}
    for case in ('missing', 'stale'):
        shell('am', 'force-stop', 'com.veyterm')
        private('sh', '-c', 'rm -f files/home/sync-resume-*')
        private('sh', '-c', 'cat > files/home/sync-resume-fixture.py',
                data=(f'CASE = {case!r}\n' + FIXTURE).encode())
        launch()
        for attempt in range(10):
            shell('input', 'keyevent', 'KEYCODE_ENTER')
            command = 'echo READY > /data/data/com.veyterm/files/home/sync-resume-shell-ready'
            shell('input', 'text', command.replace(' ', '%s'))
            shell('input', 'keyevent', 'KEYCODE_ENTER')
            time.sleep(1)
            ready = private('sh', '-c', 'cat files/home/sync-resume-shell-ready 2>/dev/null || true')
            if ready.strip() == b'READY':
                break
        else:
            raise RuntimeError('The terminal did not complete a command round trip')
        command = 'python /data/data/com.veyterm/files/home/sync-resume-fixture.py'
        shell('input', 'text', command.replace(' ', '%s'))
        shell('input', 'keyevent', 'KEYCODE_ENTER')
        wait('ready')
        before = pixel(f'{case}-before')
        shell('input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(.3)
        private('touch', 'files/home/sync-resume-go')
        wait('started')
        started = time.monotonic()
        launch()
        # Allow the resumed window to draw while remaining below the 1s timeout.
        time.sleep(.12)
        resumed = pixel(f'{case}-resumed')
        elapsed = time.monotonic() - started
        private('touch', 'files/home/sync-resume-finish')
        wait('ended')
        deadline = time.monotonic() + 3
        while True:
            ended = pixel(f'{case}-ended')
            if ended == [0, 95, 135] or time.monotonic() >= deadline:
                break
            time.sleep(.05)
        private('touch', 'files/home/sync-resume-exit')
        report[case] = dict(before=before, resumed=resumed, ended=ended,
                            capture_seconds=round(elapsed, 3))
        if elapsed >= .9:
            raise RuntimeError(f'Resume capture missed the active batch: {elapsed:.3f}s')
    destination = args.output / 'result.json'
    destination.write_text(json.dumps(report, indent=2) + '\n')
    print(destination.read_text())
    for case, result in report.items():
        assert result['before'] == [0, 135, 95], (case, result)
        assert result['ended'] == [0, 95, 135], (case, result)
        assert result['resumed'] == result['before'], (case, result)


if __name__ == '__main__':
    main()
