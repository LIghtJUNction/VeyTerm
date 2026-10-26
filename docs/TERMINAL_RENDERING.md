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
`veyterm-native-20261026` APK.
Acceptance requires a reproduced baseline, no incomplete samples afterward,
at least 230 samples, more than 10 samples of each color, at least 50 color
transitions, and no single-color hold longer than 1.5 seconds. Merely showing
both colors cannot pass: a 240-sample capture with one color change is rejected,
as is a capture with enough transitions but a two-second stall.
These are 30 Hz video samples, not application frame-rate measurements.

Reanalysis of the retained videos with these stricter checks passed:
`flicker-release.mp4` has 240 samples, 60 transitions and a 1.03-second maximum
hold; `flicker-after-full.mp4` has 242 samples, 60 transitions and a 1.07-second
maximum hold. Both have zero incomplete samples. The original baseline remains
121 incomplete samples out of 245. These are retained recordings, not new
recordings of the helper-lifecycle debug APK.

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

## Background/resume regression

`emulator_sync_resume.py` checks two cases: starting the first synchronized
frame in the background, and doing so after a preceding synchronized frame.
It waits for a shell command round trip before starting the fixture. A cursor
position reply confirms parsing; screenshots separately confirm visible output.

```sh
python3 -I tools/emulator_sync_resume.py --serial emulator-5570 \
  --output build/verification/sync-resume-helper-upgrade
```

On the helper-lifecycle debug APK, both cases preserved the preceding green
screen while the frame was unfinished and displayed blue after the end marker.
Resume captures took 0.383 and 0.352 seconds, within the one-second recovery
window. Screenshots were inspected and pixel checks passed. Evidence is in the
output directory above. The preceding APK also passed both cases under
`build/verification/sync-resume-drawn-baseline`; no background rendering defect
was reproduced. The proposed Android lifecycle behavior change was therefore
withdrawn, and these results are regression coverage.
