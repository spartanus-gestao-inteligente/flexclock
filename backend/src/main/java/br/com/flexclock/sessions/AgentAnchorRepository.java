package br.com.flexclock.sessions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.flexclock.model.AgentAnchor;

/**
 * Persistence of the anchor, one row per anchored OpenCode session.
 *
 * <p>The anchor is mutable while its window is alive: the heartbeat updates the instant and the
 * maintenance moves the status between active and stale. Closing the window or losing the heartbeat
 * deletes the anchor, never the procedure, the pauses or the report data (decision 0041; requirements
 * RF-016, RF-017, RF-018 and RN-010).
 */
public interface AgentAnchorRepository extends JpaRepository<AgentAnchor, Long> {

	/**
	 * Finds the anchor of a session.
	 *
	 * @param sessionId the OpenCode session identifier
	 * @return the anchor, or an empty optional when the session is not anchored
	 */
	Optional<AgentAnchor> findBySessionId(String sessionId);

	/**
	 * Lists every anchor, oldest first.
	 *
	 * @return the anchors, possibly empty
	 */
	List<AgentAnchor> findAllByOrderByAnchoredAtAsc();

	/**
	 * Deletes the anchors whose last heartbeat is older than the given instant.
	 *
	 * <p>The cleanup frees a session whose window crashed, so a broken window can never block a session
	 * forever (requirement RF-016, RN-009).
	 *
	 * @param threshold the oldest accepted heartbeat instant
	 * @return the number of deleted anchors
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from AgentAnchor a where a.updatedAt < :threshold")
	int deleteByUpdatedAtBefore(@Param("threshold") Instant threshold);
}
