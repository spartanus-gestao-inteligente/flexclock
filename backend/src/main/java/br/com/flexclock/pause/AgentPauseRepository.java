package br.com.flexclock.pause;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import br.com.flexclock.enums.PauseStatus;
import br.com.flexclock.model.AgentPause;

/**
 * Persistence of the agent pause.
 *
 * <p>An open pause is one whose status is {@code paused} or {@code expired}: both are unresolved, so a
 * procedure can never hold two at the same time.
 */
public interface AgentPauseRepository extends JpaRepository<AgentPause, Long> {

	/**
	 * Finds the open pause of a procedure.
	 *
	 * @param procedureId the procedure identifier
	 * @param statuses the unresolved statuses
	 * @return the open pause when one exists
	 */
	Optional<AgentPause> findFirstByProcedureIdAndStatusInOrderByPauseStartDesc(Long procedureId,
			Collection<PauseStatus> statuses);

	/**
	 * Finds the open pause of an OpenCode session.
	 *
	 * @param sessionId the OpenCode session identifier
	 * @param statuses the unresolved statuses
	 * @return the open pause when one exists
	 */
	Optional<AgentPause> findFirstBySessionIdAndStatusInOrderByPauseStartDesc(String sessionId,
			Collection<PauseStatus> statuses);

	/**
	 * Lists the pause history of a procedure, newest first.
	 *
	 * @param procedureId the procedure identifier
	 * @param pageable the page request that carries the limit
	 * @return the pauses of the procedure
	 */
	List<AgentPause> findByProcedureIdOrderByPauseStartDesc(Long procedureId, Pageable pageable);

	/**
	 * Lists every pause of a procedure that started before an instant, used to discount the paused time
	 * of a work session.
	 *
	 * @param procedureId the procedure identifier
	 * @param instant the inclusive upper bound of the pause start
	 * @return the pauses ordered by start
	 */
	List<AgentPause> findByProcedureIdAndPauseStartLessThanEqualOrderByPauseStartAsc(Long procedureId,
			Instant instant);

	/**
	 * Lists the pauses in one status that started at or before an instant, used by the expiry job.
	 *
	 * @param status the status to select
	 * @param instant the inclusive upper bound of the pause start
	 * @return the matching pauses
	 */
	List<AgentPause> findByStatusAndPauseStartLessThanEqual(PauseStatus status, Instant instant);

	/**
	 * Lists every pause in one status.
	 *
	 * @param status the status to select
	 * @return the matching pauses
	 */
	List<AgentPause> findByStatus(PauseStatus status);

	/**
	 * Lists every pause in the given statuses.
	 *
	 * @param statuses the statuses to select
	 * @return the matching pauses
	 */
	List<AgentPause> findByStatusIn(Collection<PauseStatus> statuses);
}
