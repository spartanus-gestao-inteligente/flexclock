package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.ReportRecordType;
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
 * Confirmed invented information attributed to an agent, with the reference of the response that
 * contained it. Append only.
 *
 * <p>Decisions 0013, 0019.
 */
@Entity
@Table(name = "hallucination_event")
@Getter
@Setter
public class HallucinationEvent {

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

	@Column(name = "response_ref", nullable = false, length = 500)
	private String responseRef;

	@Column(name = "record_type", nullable = false, length = 32)
	private ReportRecordType recordType;

	@Column(name = "write_source", nullable = false, length = 32)
	private WriteSource writeSource;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
	private String idempotencyKey;

	protected HallucinationEvent() {
		// Required by JPA.
	}
}
