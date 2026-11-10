package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.EventResult;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.SourceOrigin;
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
 * Consulted source: origin (domain, corpus or graph), task context, purpose, result and whether it
 * was cited. Append only.
 *
 * <p>Decision 0033.
 */
@Entity
@Table(name = "source_event")
@Getter
@Setter
public class SourceEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "agent_id", nullable = false, length = 128)
	private String agentId;

	@Column(name = "procedure_id")
	private Long procedureId;

	@Column(name = "session_id", length = 128)
	private String sessionId;

	@Column(name = "source", nullable = false, length = 1000)
	private String source;

	@Column(name = "origin", nullable = false, length = 32)
	private SourceOrigin origin;

	@Column(name = "task_context", nullable = false, length = 1000)
	private String taskContext;

	@Column(name = "purpose", nullable = false, length = 1000)
	private String purpose;

	@Column(name = "result", nullable = false, length = 16)
	private EventResult result;

	@Column(name = "cited")
	private Boolean cited;

	@Column(name = "redacted", nullable = false)
	private boolean redacted;

	@Column(name = "record_type", nullable = false, length = 32)
	private ReportRecordType recordType;

	@Column(name = "write_source", nullable = false, length = 32)
	private WriteSource writeSource;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
	private String idempotencyKey;

	protected SourceEvent() {
		// Required by JPA.
	}
}
