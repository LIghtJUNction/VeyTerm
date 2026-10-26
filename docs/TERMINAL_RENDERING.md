# Synchronized terminal output

VeyTerm implements DEC private mode 2026. `CSI ? 2026 h` starts a frame and
`CSI ? 2026 l` publishes it. Parsing, cursor-position replies and mode queries
continue during the frame. The visible view retains a bitmap of the preceding
complete grid, including when cursor blinking or another view causes a draw.

A session releases an unfinished frame after 1000 ms. Repeated begin markers
inside the same frame do not postpone recovery. A new frame gets its own
deadline; a completed frame's deadline cannot interrupt it. Resize, terminal
reset and process exit also release the frame.

The protocol follows the [synchronized output specification](https://gist.github.com/christianparpart/d8a62cc1ab659194337d73e399004036).

## Verification

Local checks on 2026-10-26 used the API 33 x86_64 Android emulator, software
graphics and a 720 × 1280 display. `flicker_fixture.py` renders 60 alternating
colored frames, deliberately pausing for 40 ms after clearing and another
25 ms after writing half the screen. Screenrecord captures showed 121
incomplete samples out of 245 before the change and 0 out of 240 with the
published APK.
Both colors remained visible, so a permanently frozen screen cannot pass.
These are 30 Hz video samples, not application frame-rate measurements.

`synchronized_output_probe.py` exercises mode queries through the actual PTY,
including queries while rendering is held, timeout recovery and deadlines
across consecutive frames. Four emulator unit tests cover split control
sequences, state queries, continued parsing and reset.

Run the video comparison with:

```sh
python tools/analyze_flicker.py before.mp4 after.mp4 --output comparison.json
```

The analyzer samples two bands inside the terminal. It excludes command entry
and the final shell prompt by locating the first and last complete fixture
frames. Captures and runtime logs are retained in `build/verification`.
Physical devices and unsynchronized applications require separate acceptance.
