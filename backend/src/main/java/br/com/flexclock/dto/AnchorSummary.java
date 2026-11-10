package br.com.flexclock.dto;

import java.time.Instant;

import br.com.flexclock.enums.AnchorStatus;
import br.com.flexclock.enums.PauseStatus;
import br.com.flexclock.enums.ProcedureStatus;

/**
 * One anchored session as the tray menu and the dashboard read it.
 *
 * <p>The record carries the cached title, the anchor status, the procedure status and the current
 * pause state with the elapsed pause time (requirement RF-019). The pause fields are a read only
 * projection of the pause record; the pause write path belongs to the time workstream.
 *
 * <p>Architecture contract, sections 6.1 and 6.2.
 *
 * @param anchorId the anchor identifier
 * @param sessionId the OpenCode session identifier
 * @param title the cached session title
 * @param directory the working directory of the session
 * @param anchorStatus active or stale
 * @param anchoredAt the instant when the window anchored the session
 * @param updatedAt the instant of the last heartbeat
 * @param procedureId the procedure linked to the session, null when it was not created
 * @param procedureStatus the procedure status, null when there is no procedure
 * @param pauseStatus the latest pause status, null when the procedure was never paused
 * @param pauseStart the instant when the latest pause started, null when there is none
 * @param pauseElapsedMs the pause time elapsed so far, null when the procedure is not paused
 */
public record AnchorSummary(Long anchorId, String sessionId, String title, String directory,
		AnchorStatus anchorStatus, Instant anchoredAt, Instant updatedAt, Long procedureId,
		ProcedureStatus procedureStatus, PauseStatus pauseStatus, Instant pauseStart, Long pauseElapsedMs) {
}
