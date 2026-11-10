package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.EvaluationAuthority;
import br.com.flexclock.enums.WriteSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * Quality evaluation of a session: score from 0 to 10, the five anchored levels from 0 to 4, the
 * rationale and the authority of the evaluation. Append only.
 *
 * <p>The score is the weighted sum of the five levels rounded half up once at the end (decision
 * 0053). The record is append only under decision 0014.
 */
@Entity
@Table(name = "quality_evaluation")
@Getter
@Setter
public class QualityEvaluation {

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

	@Min(0)
	@Max(10)
	@Column(name = "score", nullable = false)
	private Integer score;

	@Min(0)
	@Max(4)
	@Column(name = "adherence_level", nullable = false)
	private Integer adherenceLevel;

	@Min(0)
	@Max(4)
	@Column(name = "evidence_level", nullable = false)
	private Integer evidenceLevel;

	@Min(0)
	@Max(4)
	@Column(name = "process_level", nullable = false)
	private Integer processLevel;

	@Min(0)
	@Max(4)
	@Column(name = "artifact_level", nullable = false)
	private Integer artifactLevel;

	@Min(0)
	@Max(4)
	@Column(name = "honesty_level", nullable = false)
	private Integer honestyLevel;

	@Column(name = "rationale", nullable = false, length = 4000)
	private String rationale;

	@Column(name = "authority", nullable = false, length = 16)
	private EvaluationAuthority authority;

	@Column(name = "write_source", nullable = false, length = 32)
	private WriteSource writeSource;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
	private String idempotencyKey;

	protected QualityEvaluation() {
		// Required by JPA.
	}
}
