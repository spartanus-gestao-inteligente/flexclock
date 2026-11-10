package br.com.flexclock.time;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.flexclock.model.WorkSession;

/**
 * Persistence of the work session.
 *
 * <p>The queries are the only way the service reads a session, so the "open session" rule stays in one
 * place: a session is open while {@code ended_at} is null.
 */
public interface WorkSessionRepository extends JpaRepository<WorkSession, Long> {

	/**
	 * Finds the open session of a procedure.
	 *
	 * @param procedureId the procedure identifier
	 * @return the open session when the procedure is already counting time
	 */
	Optional<WorkSession> findFirstByProcedureIdAndEndedAtIsNullOrderByStartedAtDesc(Long procedureId);

	/**
	 * Finds the most recently started open session, used by the current state read when the caller does
	 * not name a procedure.
	 *
	 * @return the most recent open session
	 */
	Optional<WorkSession> findFirstByEndedAtIsNullOrderByStartedAtDesc();

	/**
	 * Lists the sessions of a procedure, newest first.
	 *
	 * @param procedureId the procedure identifier
	 * @return the sessions of the procedure
	 */
	List<WorkSession> findByProcedureIdOrderByStartedAtDesc(Long procedureId);
}
