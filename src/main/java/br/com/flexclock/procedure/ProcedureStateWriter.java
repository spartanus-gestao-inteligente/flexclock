package br.com.flexclock.procedure;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Narrow write path over the procedure table used by the time and pause flows.
 *
 * <p>The procedure lifecycle is owned by the sessions workstream. The pause flow still has to end the
 * procedure when a pause is aborted by user cancel or by a session that disappeared, and has to keep it
 * active when the pause itself failed (decision 0045; requirement RF-038). This component writes only
 * those three facts, with the canonical wire tokens of the closed enumerator, so the two flows never
 * disagree about the row.
 *
 * <p>The end instant is written once and never rewritten (requirement RF-029).
 */
@Component
public class ProcedureStateWriter {

	private static final String EXISTS_SQL = "select count(*) from procedure where id = ?";

	private static final String ABORT_SQL = """
			update procedure set status = 'aborted', ended_at = ?, updated_at = ?
			where id = ? and ended_at is null
			""";

	private static final String TOUCH_ACTIVE_SQL = """
			update procedure set updated_at = ?
			where id = ? and status = 'active'
			""";

	private final JdbcTemplate jdbcTemplate;

	public ProcedureStateWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Reports whether a procedure exists.
	 *
	 * @param procedureId the procedure identifier
	 * @return true when the row exists
	 */
	public boolean exists(Long procedureId) {
		if (procedureId == null) {
			return false;
		}
		Integer count = jdbcTemplate.queryForObject(EXISTS_SQL, Integer.class, procedureId);
		return count != null && count > 0;
	}

	/**
	 * Ends a procedure as aborted, writing the end instant once.
	 *
	 * @param procedureId the procedure identifier
	 * @param endedAt the instant the procedure ended
	 * @return true when this call wrote the end instant
	 */
	public boolean abort(Long procedureId, Instant endedAt) {
		long epochMilli = endedAt.toEpochMilli();
		return jdbcTemplate.update(ABORT_SQL, epochMilli, epochMilli, procedureId) > 0;
	}

	/**
	 * Refreshes the update instant of a procedure that stayed active during the pause.
	 *
	 * <p>A resumable pause always belongs to an active procedure, so a resume never rewrites the status:
	 * the status is final after completed or aborted and the database guard of the sessions workstream
	 * refuses a rewrite (requirements RF-029, RF-036).
	 *
	 * @param procedureId the procedure identifier
	 * @param updatedAt the instant of the transition
	 */
	public void touchActive(Long procedureId, Instant updatedAt) {
		jdbcTemplate.update(TOUCH_ACTIVE_SQL, updatedAt.toEpochMilli(), procedureId);
	}
}
