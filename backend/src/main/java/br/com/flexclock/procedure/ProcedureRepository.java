package br.com.flexclock.procedure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.flexclock.enums.ProcedureStatus;
import br.com.flexclock.model.Procedure;

/**
 * Persistence of the procedure, one to one with an OpenCode session.
 *
 * <p>The procedure is mutable and owned by the product: unlike the report tables it accepts updates,
 * because its status and its end instant change once during the lifecycle (decision 0042).
 */
public interface ProcedureRepository extends JpaRepository<Procedure, Long> {

	/**
	 * Finds the procedure of a session.
	 *
	 * @param sessionId the OpenCode session identifier
	 * @return the procedure, or an empty optional when the session was never anchored
	 */
	Optional<Procedure> findBySessionId(String sessionId);

	/**
	 * Finds the procedures of several sessions at once.
	 *
	 * @param sessionIds the OpenCode session identifiers
	 * @return the procedures found, possibly empty
	 */
	List<Procedure> findBySessionIdIn(Collection<String> sessionIds);

	/**
	 * Lists the procedures with one status, oldest first.
	 *
	 * @param status the status to filter by
	 * @return the procedures, possibly empty
	 */
	List<Procedure> findByStatusOrderByStartedAtAsc(ProcedureStatus status);

	/**
	 * Lists every procedure, oldest first.
	 *
	 * @return the procedures, possibly empty
	 */
	List<Procedure> findAllByOrderByStartedAtAsc();
}
