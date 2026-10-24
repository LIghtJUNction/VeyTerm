package com.termux.app.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SessionPresentationTest {

    @Test
    public void runningSessionUsesTitleAndRunningState() {
        SessionPresentation presentation = SessionPresentationResolver.resolve(
            0, null, "bash", true, 0, "Shell", "Active shell", "Exited with status %1$d");

        assertEquals("Shell 1", presentation.getDisplayName());
        assertEquals("bash", presentation.getDetail());
        assertEquals(SessionPresentationStatus.RUNNING, presentation.getStatus());
    }

    @Test
    public void failedSessionUsesExitCodeAndErrorState() {
        SessionPresentation presentation = SessionPresentationResolver.resolve(
            1, "build", null, false, 127, "Shell", "Active shell", "Exited with status %1$d");

        assertEquals("build", presentation.getDisplayName());
        assertEquals("Exited with status 127", presentation.getDetail());
        assertEquals(SessionPresentationStatus.ERROR, presentation.getStatus());
    }
}
