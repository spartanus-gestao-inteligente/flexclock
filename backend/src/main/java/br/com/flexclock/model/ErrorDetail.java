package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.FailureType;
import br.com.flexclock.enums.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Error detail of a failure record: stable code, English message, field or resource, type, severity,
 * agent and instant. Append only.
 *
 * <p>Decision 0056.
 */
@Entity
@Table(name = "error_detail")
@Getter
@Setter
public class ErrorDetail {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "failure_event_id", nullable = false)
	private Long failureEventId;

	@Column(name = "code", nullable = false, length = 64)
	private String code;

	@Column(name = "message", nullable = false, length = 2000)
	private String message;

	@Column(name = "field_or_resource", length = 255)
	private String fieldOrResource;

	@Column(name = "failure_type", nullable = false, length = 32)
	private FailureType failureType;

	@Column(name = "severity", nullable = false, length = 16)
	private Severity severity;

	@Column(name = "agent_id", nullable = false, length = 128)
	private String agentId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	protected ErrorDetail() {
		// Required by JPA.
	}
}
