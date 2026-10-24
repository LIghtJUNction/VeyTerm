package com.termux.app.terminal

import java.util.Locale

/** UI-neutral state used by the session drawer and the compact session header. */
enum class SessionPresentationStatus {
    RUNNING,
    EXITED,
    ERROR,
}

data class SessionPresentation(
    val displayName: String,
    val detail: String,
    val status: SessionPresentationStatus,
)

/**
 * Keeps session display policy out of Android views while Java callers migrate
 * incrementally to Kotlin. It has no Android or terminal-core dependency.
 */
object SessionPresentationResolver {
    @JvmStatic
    fun resolve(
        position: Int,
        sessionName: String?,
        sessionTitle: String?,
        isRunning: Boolean,
        exitStatus: Int,
        localShellLabel: String,
        runningDetail: String,
        exitedDetailTemplate: String,
    ): SessionPresentation {
        val displayName = if (sessionName.isNullOrBlank()) {
            "$localShellLabel ${position + 1}"
        } else {
            sessionName
        }
        val detail = if (isRunning) {
            sessionTitle?.takeUnless { it.isBlank() } ?: runningDetail
        } else {
            String.format(Locale.ROOT, exitedDetailTemplate, exitStatus)
        }
        val status = when {
            isRunning -> SessionPresentationStatus.RUNNING
            exitStatus == 0 -> SessionPresentationStatus.EXITED
            else -> SessionPresentationStatus.ERROR
        }
        return SessionPresentation(displayName, detail, status)
    }
}
