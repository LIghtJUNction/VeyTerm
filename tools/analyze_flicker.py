#!/usr/bin/env python3
"""Compare screenrecord captures of flicker_fixture.py at 720x1280.

The two sample bands lie inside the terminal, above the keyboard. Analyze
from the first complete colored frame through the last, excluding command
entry and shell prompts. A complete frame must have the same color in both
bands; checking both colors also prevents a permanently frozen view passing.
"""

import argparse
import json
from pathlib import Path
import subprocess


def analyze(video):
    samples = []
    for y in (145, 500):
        raw = subprocess.run([
            "ffmpeg", "-v", "error", "-i", str(video), "-vf",
            f"fps=30,crop=160:35:100:{y},scale=1:1", "-pix_fmt", "rgb24",
            "-f", "rawvideo", "-"
        ], check=True, capture_output=True).stdout
        samples.append(list(zip(raw[0::3], raw[1::3], raw[2::3])))
    frames = list(zip(*samples))

    def complete(frame):
        return (all(max(p) - min(p) > 40 and p[1] > 60 for p in frame)
                and max(abs(a - b) for a, b in zip(*frame)) < 12)

    colored = [i for i, frame in enumerate(frames) if complete(frame)]
    if not colored:
        raise ValueError(f"No complete fixture frame in {video}")
    first, last = colored[0], colored[-1]
    chosen = frames[first:last + 1]
    return {
        "start_seconds": round(first / 30, 2), "end_seconds": round(last / 30, 2),
        "sampled_frames": len(chosen),
        "incomplete_frames": sum(not complete(frame) for frame in chosen),
        "blue_frames": sum(complete(f) and f[0][2] > f[0][1] + 20 for f in chosen),
        "green_frames": sum(complete(f) and f[0][1] > f[0][2] + 20 for f in chosen),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = {"before": analyze(args.before), "after": analyze(args.after)}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    assert report["before"]["incomplete_frames"] > 0, "Baseline did not reproduce the problem"
    assert report["after"]["incomplete_frames"] == 0, "Partial or blank synchronized frame"
    assert report["after"]["sampled_frames"] >= 230, "Capture missed part of the fixture"
    assert min(report["after"]["blue_frames"], report["after"]["green_frames"]) > 10, "Frozen display"


if __name__ == "__main__":
    main()
