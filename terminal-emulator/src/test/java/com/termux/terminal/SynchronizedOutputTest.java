package com.termux.terminal;

public class SynchronizedOutputTest extends TerminalTestCase {

    public void testModeQueryAndSplitMarkers() {
        withTerminalSized(8, 3);
        assertEnteringStringGivesResponse("\033[?2026$p", "\033[?2026;2$y");
        enterString("\033[?20");
        assertFalse(mTerminal.isSynchronizedOutputEnabled());
        enterString("26h");
        assertTrue(mTerminal.isSynchronizedOutputEnabled());
        assertEnteringStringGivesResponse("\033[?2026$p", "\033[?2026;1$y");
        enterString("\033[?2026l");
        assertEnteringStringGivesResponse("\033[?2026$p", "\033[?2026;2$y");
    }

    public void testGridAndQueriesContinueDuringFrame() {
        withTerminalSized(8, 3).enterString("old\033[?2026h\r\033[2Knew");
        assertTrue(mTerminal.isSynchronizedOutputEnabled());
        assertLinesAre("new     ", "        ", "        ");
        assertEnteringStringGivesResponse("\033[6n", "\033[1;4R");
        enterString("\033[?2026l");
        assertFalse(mTerminal.isSynchronizedOutputEnabled());
        assertLinesAre("new     ", "        ", "        ");
    }

    public void testRepeatedBeginDoesNotRestartFrame() {
        withTerminalSized(8, 3);
        enterString("\033[?2026h\033[?2026h");
        assertEquals(1, mOutput.synchronizedFramesStarted);
        enterString("\033[?2026l\033[?2026h");
        assertEquals(2, mOutput.synchronizedFramesStarted);
    }

    public void testResetAndForcedEndReleaseFrame() {
        withTerminalSized(8, 3).enterString("\033[?2026h");
        mTerminal.finishSynchronizedOutput();
        assertEnteringStringGivesResponse("\033[?2026$p", "\033[?2026;2$y");
        enterString("\033[?2026h\033c");
        assertFalse(mTerminal.isSynchronizedOutputEnabled());
        enterString("visible").assertLinesAre("visible ", "        ", "        ");
    }
}
