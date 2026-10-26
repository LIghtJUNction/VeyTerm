import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from analyze_flicker import analyze_frames, validate_report


BLUE = ((0, 95, 135), (0, 95, 135))
GREEN = ((0, 135, 95), (0, 135, 95))
BLANK = ((0, 0, 0), (0, 0, 0))
PARTIAL = (BLUE[0], GREEN[0])


class AnalyzeFlickerTests(unittest.TestCase):
    @staticmethod
    def fixture_frames():
        # The fixture holds the initial/final image for a second, with 60
        # alternating updates lasting roughly a tenth of a second each.
        return [GREEN] * 30 + ([BLUE] * 3 + [GREEN] * 3) * 30 + [GREEN] * 30

    @staticmethod
    def report(frames, before=None):
        if before is None:
            before = [GREEN, BLANK, PARTIAL, BLUE]
        return {"before": analyze_frames(before), "after": analyze_frames(frames)}

    def test_complete_fixture_advances_through_both_colors(self):
        report = self.report(self.fixture_frames())
        self.assertEqual({
            "start_seconds": 0.0,
            "end_seconds": 7.97,
            "sampled_frames": 240,
            "color_transitions": 60,
            "longest_hold_seconds": 1.1,
            "incomplete_frames": 0,
            "blue_frames": 90,
            "green_frames": 150,
        }, report["after"])
        validate_report(report)

    def test_one_color_change_cannot_pass_as_a_running_fixture(self):
        report = self.report([BLUE] * 120 + [GREEN] * 120)
        after = report["after"]
        # This passed the original duration/completeness/two-color checks.
        self.assertEqual(240, after["sampled_frames"])
        self.assertEqual(0, after["incomplete_frames"])
        self.assertEqual(120, after["blue_frames"])
        self.assertEqual(120, after["green_frames"])
        self.assertEqual(1, after["color_transitions"])
        self.assertEqual(4.0, after["longest_hold_seconds"])
        with self.assertRaisesRegex(AssertionError, "Fixture stopped advancing"):
            validate_report(report)

    def test_many_transitions_do_not_hide_a_long_stall(self):
        report = self.report([GREEN] * 60 + ([BLUE] * 3 + [GREEN] * 3) * 30)
        self.assertEqual(60, report["after"]["color_transitions"])
        self.assertEqual(2.0, report["after"]["longest_hold_seconds"])
        with self.assertRaisesRegex(AssertionError, "Fixture stalled"):
            validate_report(report)

    def test_transition_and_hold_limits_are_inclusive(self):
        # 51 alternating runs give exactly 50 transitions. Both end holds
        # are 45 samples (1.5 seconds), and the capture has 237 samples.
        frames = [GREEN] * 45 + ([BLUE] * 3 + [GREEN] * 3) * 24
        frames += [BLUE] * 3 + [GREEN] * 45
        report = self.report(frames)
        self.assertEqual(50, report["after"]["color_transitions"])
        self.assertEqual(1.5, report["after"]["longest_hold_seconds"])
        validate_report(report)

    def test_49_transitions_are_insufficient_even_without_a_stall(self):
        report = self.report(([BLUE] * 5 + [GREEN] * 5) * 25)
        self.assertEqual(49, report["after"]["color_transitions"])
        with self.assertRaisesRegex(AssertionError, "Fixture stopped advancing"):
            validate_report(report)

    def test_a_hold_one_sample_over_the_limit_fails(self):
        frames = [GREEN] * 46 + ([BLUE] * 3 + [GREEN] * 3) * 30 + [GREEN] * 30
        report = self.report(frames)
        self.assertEqual(1.53, report["after"]["longest_hold_seconds"])
        with self.assertRaisesRegex(AssertionError, "Fixture stalled"):
            validate_report(report)

    def test_command_entry_and_prompts_are_trimmed_from_both_ends(self):
        stats = analyze_frames([BLANK] * 30 + self.fixture_frames() + [BLANK] * 15)
        self.assertEqual(1.0, stats["start_seconds"])
        self.assertEqual(8.97, stats["end_seconds"])
        self.assertEqual(240, stats["sampled_frames"])
        self.assertEqual(0, stats["incomplete_frames"])
        self.assertEqual(60, stats["color_transitions"])

    def test_blank_and_split_frames_break_contiguous_color_runs(self):
        for resumed in (BLUE, GREEN):
            with self.subTest(resumed=resumed):
                stats = analyze_frames([BLUE] * 3 + [BLANK, PARTIAL] + [resumed] * 4)
                self.assertEqual(9, stats["sampled_frames"])
                self.assertEqual(2, stats["incomplete_frames"])
                self.assertEqual(7 if resumed == BLUE else 3, stats["blue_frames"])
                self.assertEqual(0 if resumed == BLUE else 4, stats["green_frames"])
                self.assertEqual(0, stats["color_transitions"])
                self.assertEqual(0.13, stats["longest_hold_seconds"])

    def test_no_complete_fixture_frame_is_an_error(self):
        for frames in ([], [BLANK] * 240, [PARTIAL] * 240):
            with self.subTest(samples=len(frames)):
                with self.assertRaisesRegex(ValueError, "No complete fixture frame"):
                    analyze_frames(frames)

    def test_clean_baseline_does_not_demonstrate_a_fix(self):
        report = self.report(self.fixture_frames(), before=self.fixture_frames())
        with self.assertRaisesRegex(AssertionError, "Baseline did not reproduce"):
            validate_report(report)

    def test_incomplete_frame_inside_capture_is_rejected(self):
        for incomplete in (BLANK, PARTIAL):
            with self.subTest(frame=incomplete):
                frames = self.fixture_frames()
                frames[120] = incomplete
                with self.assertRaisesRegex(AssertionError, "Partial or blank"):
                    validate_report(self.report(frames))

    def test_short_capture_fails_despite_enough_transitions(self):
        report = self.report(([BLUE] * 3 + [GREEN] * 3) * 30)
        self.assertEqual(59, report["after"]["color_transitions"])
        with self.assertRaisesRegex(AssertionError, "Capture missed part"):
            validate_report(report)

    def test_minimum_capture_length_passes(self):
        report = self.report(([BLUE] * 3 + [GREEN] * 3) * 38 + [BLUE, GREEN])
        self.assertEqual(230, report["after"]["sampled_frames"])
        validate_report(report)

    def test_permanently_frozen_capture_fails(self):
        with self.assertRaisesRegex(AssertionError, "Frozen display"):
            validate_report(self.report([BLUE] * 240))


if __name__ == "__main__":
    unittest.main()
