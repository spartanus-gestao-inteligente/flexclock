package br.com.flexclock.pause;

import java.time.Instant;

import br.com.flexclock.enums.PauseReason;
import br.com.flexclock.enums.PauseStatus;

/**
 * Current pause state of an OpenCode session.
 *
 * <p>Additive to the frozen contract: the pause state and the pause history are required by
 * requirement RF-040, which the endpoint table of section 6.1 does not spell out. The record carries the
 * paused time already elapsed, the instant the 60 minute limit is due and the reason when the pause
 * ended without a resume, so the clock window and the dashboard render the state without calculating
 * time locally.
 *
 * @param pauseId the pause identifier
 * @param procedureId the procedure identifier
 * @param sessionId the OpenCode session identifier
 * @param status the pause status wire token
 * @param pauseStart the instant the pause started, in UTC
 * @param resumeAt the instant the pause ended, null while it is unresolved
 * @param durationMs the stored duration, null while the pause is unresolved
 * @param pausedElapsedMs the paused time elapsed so far, live for an unresolved pause
 * @param expiresAt the instant the 60 minute limit is due
 * @param expiryDue true when the limit is already due but the job has not marked the pause yet
 * @param expired true when the stored status is expired
 * @param reason the reason recorded when the pause ended without a resume, null otherwise
 */
public record PauseStateResponse(Long pauseId, Long procedureId, String sessionId, PauseStatus status,
		Instant pauseStart, Instant resumeAt, Long durationMs, long pausedElapsedMs, Instant expiresAt,
		boolean expiryDue, boolean expired, PauseReason reason) {
}
