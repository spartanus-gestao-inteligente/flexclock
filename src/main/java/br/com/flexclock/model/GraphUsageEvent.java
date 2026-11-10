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
 * Usage of the Spartangraphify knowledge graph by an agent: number of queries, distinct nodes and
 * whether the graph was consulted before the work. Append only.
 *
 * <p>Decisions 0019, 0037.
 */
@Entity
@Table(name = "graph_usage_event")
@Getter
@Setter
public class GraphUsageEvent {

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

	@Column(name = "query_count", nullable = false)
	private Integer queryCount;

	@Column(name = "distinct_nodes", nullable = false)
	private Integer distinctNodes;

	@Column(name = "consulted_before_work", nullable = false)
	private boolean consultedBeforeWork;

	@Column(name = "record_type", nullable = false, length = 32)
	private ReportRecordType recordType;

	@Column(name = "write_source", nullable = false, length = 32)
	private WriteSource writeSource;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
	private String idempotencyKey;

	protected GraphUsageEvent() {
		// Required by JPA.
	}
}
