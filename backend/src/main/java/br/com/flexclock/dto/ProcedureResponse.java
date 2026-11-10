package br.com.flexclock.dto;

import java.time.Instant;

import br.com.flexclock.enums.ProcedureStatus;

/**
 * One procedure of a session, as the dashboard and the history read it.
 *
 * <p>Architecture contract, sections 6.1 and 6.2; decision 0042.
 *
 * @param procedureId the procedure identifier
 * @param sessionId the OpenCode session identifier, one to one with the procedure
 * @param title the procedure title, copied from the session
 * @param directory the working directory of the session
 * @param status active, completed or aborted
 * @param startedAt the instant when the procedure started
 * @param endedAt the instant when the procedure ended, null while it is active
 * @param createdAt the instant when the row was created
 * @param updatedAt the instant of the last change
 */
public record ProcedureResponse(Long procedureId, String sessionId, String title, String directory,
		ProcedureStatus status, Instant startedAt, Instant endedAt, Instant createdAt, Instant updatedAt) {
}
