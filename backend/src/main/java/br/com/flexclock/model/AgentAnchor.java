package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.AnchorStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Anchor of an OpenCode session: one row per session, with the cached title, the status and the
 * heartbeat used to detect a stale anchor (30 second heartbeat, 2 minute time to live).
 *
 * <p>Mutable. Decisions 0041, 0057.
 */
@Entity
@Table(name = "agent_anchor")
@Getter
@Setter
public class AgentAnchor {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "session_id", nullable = false, unique = true, length = 128)
	private String sessionId;

	@Column(name = "directory", nullable = false, length = 1000)
	private String directory;

	@Column(name = "title", nullable = false, length = 500)
	private String title;

	@Column(name = "status", nullable = false, length = 32)
	private AnchorStatus status;

	@Column(name = "anchored_at", nullable = false)
	private Instant anchoredAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Creates an empty row. Required by JPA and by the service that writes the row in a
	 * transaction; the fields are set from the lifecycle rule, never from the client payload.
	 */
	public AgentAnchor() {
		// Required by JPA.
	}
}
