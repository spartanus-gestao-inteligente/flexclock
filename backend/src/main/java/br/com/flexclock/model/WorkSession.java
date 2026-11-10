package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.TimeMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Work session of the clock: mode, target, instants in UTC, total, paused and active milliseconds
 * and the session timezone. Mutable while open.
 *
 * <p>Decisions 0020, 0042, 0057.
 */
@Entity
@Table(name = "work_session")
@Getter
@Setter
public class WorkSession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "procedure_id")
	private Long procedureId;

	@Column(name = "mode", nullable = false, length = 32)
	private TimeMode mode;

	@Column(name = "target_seconds")
	private Integer targetSeconds;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "total_ms", nullable = false)
	private Long totalMs;

	@Column(name = "paused_ms", nullable = false)
	private Long pausedMs;

	@Column(name = "active_ms", nullable = false)
	private Long activeMs;

	@Column(name = "timezone", nullable = false, length = 64)
	private String timezone;

	@Column(name = "override_author", length = 128)
	private String overrideAuthor;

	@Column(name = "override_at")
	private Instant overrideAt;

	/**
	 * Creates an empty row. Required by JPA and by the service that writes the row in a
	 * transaction; the fields are set from the lifecycle rule, never from the client payload.
	 */
	public WorkSession() {
		// Required by JPA.
	}

	/**
	 * Creates an open work session, with the derived durations at zero until the stop instant is known.
	 *
	 * @param mode the time mode
	 * @param targetSeconds the countdown target, null for a stopwatch
	 * @param procedureId the procedure that owns the session, may be null
	 * @param startedAt the start instant, in UTC
	 * @param timezone the timezone used by the midnight split
	 * @return the new session, ready to persist
	 */
	public static WorkSession start(TimeMode mode, Integer targetSeconds, Long procedureId, Instant startedAt,
			String timezone) {
		WorkSession session = new WorkSession();
		session.mode = mode;
		session.targetSeconds = targetSeconds;
		session.procedureId = procedureId;
		session.startedAt = startedAt;
		session.totalMs = 0L;
		session.pausedMs = 0L;
		session.activeMs = 0L;
		session.timezone = timezone;
		return session;
	}
}
