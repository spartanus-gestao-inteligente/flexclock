package br.com.flexclock.pause;

import java.time.Instant;

import br.com.flexclock.enums.PauseReason;
import br.com.flexclock.enums.PauseStatus;

/**
 * One entry of the pause history of a procedure.
 *
 * <p>The history is ordered newest first and bounded, so a long procedure can never return an unbounded
 * list (requirement RF-040, RF-164).
 *
 * @param pauseId the pause identifier
 * @param procedureId the procedure identifier
 * @param sessionId the OpenCode session identifier
 * @param status the pause status wire token
 * @param pauseStart the instant the pause started, in UTC
 * @param resumeAt the instant the pause ended, null when it never ended
 * @param durationMs the stored duration, null when it never ended
 * @param reason the reason recorded when the pause ended without a resume, null otherwise
 */
public record PauseHistoryItem(Long pauseId, Long procedureId, String sessionId, PauseStatus status,
		Instant pauseStart, Instant resumeAt, Long durationMs, PauseReason reason) {
}
