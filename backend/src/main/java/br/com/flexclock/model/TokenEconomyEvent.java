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
 * Token economy of an agent session: input and output tokens, the baseline when one exists, the
 * saving only when it is measurable and the reason when it is not. Append only.
 *
 * <p>A saving is never estimated. When the metric is not measurable the record says so and leaves
 * the saving empty (decision 0054).
 */
@Entity
@Table(name = "token_economy_event")
@Getter
@Setter
public class TokenEconomyEvent {

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

	@Column(name = "input_tokens", nullable = false)
	private Long inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private Long outputTokens;

	@Column(name = "baseline_input_tokens")
	private Long baselineInputTokens;

	@Column(name = "baseline_output_tokens")
	private Long baselineOutputTokens;

	@Column(name = "savings_tokens")
	private Long savingsTokens;

	@Column(name = "measurable", nullable = false)
	private boolean measurable;

	@Column(name = "not_measurable_reason", length = 500)
	private String notMeasurableReason;

	@Column(name = "model_id", nullable = false, length = 128)
	private String modelId;

	@Column(name = "record_type", nullable = false, length = 32)
	private ReportRecordType recordType;

	@Column(name = "write_source", nullable = false, length = 32)
	private WriteSource writeSource;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
	private String idempotencyKey;

	protected TokenEconomyEvent() {
		// Required by JPA.
	}
}
