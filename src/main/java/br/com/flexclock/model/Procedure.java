package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.ProcedureStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Procedure: one to one with an OpenCode session, root that links the anchor, the pauses and the
 * reports. Status is active, completed or aborted and the end instant is written once.
 *
 * <p>Mutable. Decisions 0042, 0057.
 */
@Entity
@Table(name = "procedure")
@Getter
@Setter
public class Procedure {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "session_id", nullable = false, unique = true, length = 128)
	private String sessionId;

	@Column(name = "title", nullable = false, length = 500)
	private String title;

	@Column(name = "directory", nullable = false, length = 1000)
	private String directory;

	@Column(name = "status", nullable = false, length = 32)
	private ProcedureStatus status;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Creates an empty row. Required by JPA and by the service that writes the row in a
	 * transaction; the fields are set from the lifecycle rule, never from the client payload.
	 */
	public Procedure() {
		// Required by JPA.
	}
}
