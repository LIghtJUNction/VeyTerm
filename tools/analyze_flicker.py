#!/usr/bin/env python3
"""Compare screenrecord captures of flicker_fixture.py at 720x1280.

The two sample bands lie inside the terminal, above the keyboard. Analyze
from the first complete colored frame through the last, excluding command
entry and shell prompts. A complete frame must have the same color in both
bands; requiring repeated color changes and bounded holds also rejects frozen
or stalled playback.
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
    return analyze_frames(list(zip(*samples)))


def analyze_frames(frames):
    """Summarize paired RGB band samples taken at 30 frames per second."""
    def complete(frame):
        return (all(max(p) - min(p) > 40 and p[1] > 60 for p in frame)
                and max(abs(a - b) for a, b in zip(*frame)) < 12)

    colored = [i for i, frame in enumerate(frames) if complete(frame)]
    if not colored:
        raise ValueError("No complete fixture frame")
    first, last = colored[0], colored[-1]
    chosen = frames[first:last + 1]
    colors = [
        ("blue" if f[0][2] > f[0][1] + 20 else "green") if complete(f) else None
        for f in chosen
    ]
    transitions = 0
    longest_hold = hold = 0
    previous = None
    for color in colors:
        if color is None:
            previous = None
            hold = 0
            continue
        if previous is not None and color != previous:
            transitions += 1
        hold = hold + 1 if color == previous else 1
        longest_hold = max(longest_hold, hold)
        previous = color
    return {
        "start_seconds": round(first / 30, 2), "end_seconds": round(last / 30, 2),
        "sampled_frames": len(chosen),
        "color_transitions": transitions,
        "longest_hold_seconds": round(longest_hold / 30, 2),
        "incomplete_frames": sum(not complete(frame) for frame in chosen),
        "blue_frames": sum(complete(f) and f[0][2] > f[0][1] + 20 for f in chosen),
        "green_frames": sum(complete(f) and f[0][1] > f[0][2] + 20 for f in chosen),
    }


def validate_report(report):
    """Require a reproduced baseline and a complete, advancing fixture capture."""
    assert report["before"]["incomplete_frames"] > 0, "Baseline did not reproduce the problem"
    assert report["after"]["incomplete_frames"] == 0, "Partial or blank synchronized frame"
    assert report["after"]["sampled_frames"] >= 230, "Capture missed part of the fixture"
    assert min(report["after"]["blue_frames"], report["after"]["green_frames"]) > 10, "Frozen display"
    assert report["after"]["color_transitions"] >= 50, "Fixture stopped advancing"
    assert report["after"]["longest_hold_seconds"] <= 1.5, "Fixture stalled"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = {"before": analyze(args.before), "after": analyze(args.after)}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    validate_report(report)


if __name__ == "__main__":
    main()
