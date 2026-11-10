package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.PauseReason;
import br.com.flexclock.enums.PauseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Pause record of a procedure. Mutable only by transition between the closed pause statuses, with
 * the reason recorded when the pause ends without a resume.
 *
 * <p>Decisions 0035, 0045.
 */
@Entity
@Table(name = "agent_pause")
@Getter
@Setter
public class AgentPause {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "procedure_id", nullable = false)
	private Long procedureId;

	@Column(name = "session_id", nullable = false, length = 128)
	private String sessionId;

	@Column(name = "status", nullable = false, length = 32)
	private PauseStatus status;

	@Column(name = "pause_start", nullable = false)
	private Instant pauseStart;

	@Column(name = "resume_at")
	private Instant resumeAt;

	@Column(name = "duration_ms")
	private Long durationMs;

	@Column(name = "reason", length = 32)
	private PauseReason reason;

	protected AgentPause() {
		// Required by JPA.
	}

	/**
	 * Creates a pause in the paused state, with no end instant and no duration yet.
	 *
	 * @param procedureId the procedure identifier
	 * @param sessionId the OpenCode session identifier
	 * @param pauseStart the instant the pause started, in UTC
	 * @return the new pause, ready to persist
	 */
	public static AgentPause open(Long procedureId, String sessionId, Instant pauseStart) {
		AgentPause pause = new AgentPause();
		pause.procedureId = procedureId;
		pause.sessionId = sessionId;
		pause.status = PauseStatus.PAUSED;
		pause.pauseStart = pauseStart;
		return pause;
	}
}
