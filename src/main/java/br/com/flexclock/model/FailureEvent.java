package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.FailureType;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.Severity;
import br.com.flexclock.enums.WriteSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent failure record. Append only: the repository exposes no update or delete and SQLite triggers
 * reject both. The details of an error record live in {@link ErrorDetail}.
 *
 * <p>Decisions 0021, 0014, 0056.
 */
@Entity
@Table(name = "failure_event")
@Getter
@Setter
public class FailureEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "record_type", nullable = false, length = 32)
	private ReportRecordType recordType;

	@Column(name = "failure_type", nullable = false, length = 32)
	private FailureType failureType;

	@Column(name = "severity", nullable = false, length = 16)
	private Severity severity;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "agent_id", nullable = false, length = 128)
	private String agentId;

	@Column(name = "procedure_id")
	private Long procedureId;

	@Column(name = "session_id", length = 128)
	private String sessionId;

	@Column(name = "write_source", nullable = false, length = 32)
	private WriteSource writeSource;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
	private String idempotencyKey;

	protected FailureEvent() {
		// Required by JPA.
	}
}
