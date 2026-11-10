package br.com.flexclock.reports;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;

/**
 * Write access to the report tables: insertion only, through explicit SQL.
 *
 * <p>The class exposes no update and no delete statement, so no caller can rewrite a record through
 * the application layer. The insert names every column, which keeps the append only intent visible in
 * the code and independent from any entity mapping, and it asks SQLite for the generated identifier.
 *
 * <p>Decision 0049; requirements RF-046, RF-048.
 */
@Repository
public class ReportRecordRepository {

	private static final String INSERT_FAILURE = """
			insert into failure_event (record_type, failure_type, severity, occurred_at, agent_id, procedure_id,
				session_id, write_source, idempotency_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERT_ERROR_DETAIL = """
			insert into error_detail (failure_event_id, code, message, field_or_resource, failure_type, severity,
				agent_id, occurred_at)
			values (?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERT_HALLUCINATION = """
			insert into hallucination_event (occurred_at, agent_id, procedure_id, session_id, response_ref,
				record_type, write_source, idempotency_key)
			values (?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERT_QUALITY = """
			insert into quality_evaluation (occurred_at, agent_id, procedure_id, session_id, score, adherence_level,
				evidence_level, process_level, artifact_level, honesty_level, rationale, authority, write_source,
				idempotency_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERT_GRAPH_USAGE = """
			insert into graph_usage_event (occurred_at, agent_id, procedure_id, session_id, query_count,
				distinct_nodes, consulted_before_work, record_type, write_source, idempotency_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERT_TOKEN_ECONOMY = """
			insert into token_economy_event (occurred_at, agent_id, procedure_id, session_id, input_tokens,
				output_tokens, baseline_input_tokens, baseline_output_tokens, savings_tokens, measurable,
				not_measurable_reason, model_id, record_type, write_source, idempotency_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERT_SOURCE = """
			insert into source_event (occurred_at, agent_id, procedure_id, session_id, source, origin, task_context,
				purpose, result, cited, redacted, record_type, write_source, idempotency_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERT_DOWNLOAD = """
			insert into download_event (occurred_at, agent_id, procedure_id, session_id, source, item, item_type,
				size_bytes, result, destination, redacted, record_type, write_source, idempotency_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERT_UPLOAD = """
			insert into upload_event (occurred_at, agent_id, procedure_id, session_id, destination, item, item_type,
				size_bytes, result, sent, redacted, record_type, write_source, idempotency_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbcTemplate;

	public ReportRecordRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Inserts a failure record.
	 *
	 * @param event the record to insert
	 * @return the identifier assigned by the database
	 */
	public Long insertFailure(FailureEventRow event) {
		return insert(INSERT_FAILURE, event.recordType(), event.failureType(), event.severity(), event.occurredAt(),
				event.agentId(), event.procedureId(), event.sessionId(), event.writeSource(),
				event.idempotencyKey());
	}

	/**
	 * Inserts an error detail.
	 *
	 * @param detail the detail to insert
	 * @return the identifier assigned by the database
	 */
	public Long insertErrorDetail(ErrorDetailRow detail) {
		return insert(INSERT_ERROR_DETAIL, detail.failureEventId(), detail.code(), detail.message(),
				detail.fieldOrResource(), detail.failureType(), detail.severity(), detail.agentId(),
				detail.occurredAt());
	}

	/**
	 * Inserts a hallucination record.
	 *
	 * @param event the record to insert
	 * @return the identifier assigned by the database
	 */
	public Long insertHallucination(HallucinationRow event) {
		return insert(INSERT_HALLUCINATION, event.occurredAt(), event.agentId(), event.procedureId(),
				event.sessionId(), event.responseRef(), event.recordType(), event.writeSource(),
				event.idempotencyKey());
	}

	/**
	 * Inserts a quality evaluation.
	 *
	 * @param evaluation the evaluation to insert
	 * @return the identifier assigned by the database
	 */
	public Long insertQuality(QualityRow evaluation) {
		return insert(INSERT_QUALITY, evaluation.occurredAt(), evaluation.agentId(), evaluation.procedureId(),
				evaluation.sessionId(), evaluation.score(), evaluation.adherenceLevel(), evaluation.evidenceLevel(),
				evaluation.processLevel(), evaluation.artifactLevel(), evaluation.honestyLevel(),
				evaluation.rationale(), evaluation.authority(), evaluation.writeSource(), evaluation.idempotencyKey());
	}

	/**
	 * Inserts a graph usage record.
	 *
	 * @param event the record to insert
	 * @return the identifier assigned by the database
	 */
	public Long insertGraphUsage(GraphUsageRow event) {
		return insert(INSERT_GRAPH_USAGE, event.occurredAt(), event.agentId(), event.procedureId(), event.sessionId(),
				event.queryCount(), event.distinctNodes(), event.consultedBeforeWork(), event.recordType(),
				event.writeSource(), event.idempotencyKey());
	}

	/**
	 * Inserts a token economy record.
	 *
	 * @param event the record to insert
	 * @return the identifier assigned by the database
	 */
	public Long insertTokenEconomy(TokenEconomyRow event) {
		return insert(INSERT_TOKEN_ECONOMY, event.occurredAt(), event.agentId(), event.procedureId(),
				event.sessionId(), event.inputTokens(), event.outputTokens(), event.baselineInputTokens(),
				event.baselineOutputTokens(), event.savingsTokens(), event.measurable(),
				event.notMeasurableReason(), event.modelId(), event.recordType(), event.writeSource(),
				event.idempotencyKey());
	}

	/**
	 * Inserts a source access record.
	 *
	 * @param event the record to insert
	 * @return the identifier assigned by the database
	 */
	public Long insertSource(SourceRow event) {
		return insert(INSERT_SOURCE, event.occurredAt(), event.agentId(), event.procedureId(), event.sessionId(),
				event.source(), event.origin(), event.taskContext(), event.purpose(), event.result(), event.cited(),
				event.redacted(), event.recordType(), event.writeSource(), event.idempotencyKey());
	}

	/**
	 * Inserts a download record.
	 *
	 * @param event the record to insert
	 * @return the identifier assigned by the database
	 */
	public Long insertDownload(DownloadRow event) {
		return insert(INSERT_DOWNLOAD, event.occurredAt(), event.agentId(), event.procedureId(), event.sessionId(),
				event.source(), event.item(), event.itemType(), event.sizeBytes(), event.result(),
				event.destination(), event.redacted(), event.recordType(), event.writeSource(),
				event.idempotencyKey());
	}

	/**
	 * Inserts an upload record.
	 *
	 * @param event the record to insert
	 * @return the identifier assigned by the database
	 */
	public Long insertUpload(UploadRow event) {
		return insert(INSERT_UPLOAD, event.occurredAt(), event.agentId(), event.procedureId(), event.sessionId(),
				event.destination(), event.item(), event.itemType(), event.sizeBytes(), event.result(),
				event.sent(), event.redacted(), event.recordType(), event.writeSource(), event.idempotencyKey());
	}

	/**
	 * Finds the identifier of a record by its Idempotency-Key.
	 *
	 * <p>Used only to answer a repeated write with the original result after a unique violation, never
	 * to modify a record. The table comes from the closed family mapping, so no caller can hand a raw
	 * table name to the statement.
	 *
	 * @param family the report family that owns the record
	 * @param idempotencyKey the key presented by the client
	 * @return the identifier when the key is already recorded
	 * @throws br.com.flexclock.common.ApiException with {@code VALIDATION_ERROR} for the time family,
	 * which owns no append only table
	 */
	public Optional<Long> findIdByIdempotencyKey(ReportFamily family, String idempotencyKey) {
		return jdbcTemplate.queryForList("select id from " + tableOf(family) + " where idempotency_key = ?",
				Long.class, idempotencyKey).stream().findFirst();
	}

	private String tableOf(ReportFamily family) {
		return switch (family) {
			case FAILURES -> "failure_event";
			case HALLUCINATION -> "hallucination_event";
			case QUALITY -> "quality_evaluation";
			case GRAPH_USAGE -> "graph_usage_event";
			case TOKEN_ECONOMY -> "token_economy_event";
			case SOURCES -> "source_event";
			case DOWNLOADS -> "download_event";
			case UPLOADS -> "upload_event";
			case TIME -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "family",
					"The time family is derived from the work sessions and has no append only table.");
		};
	}

	private Long insert(String sql, Object... arguments) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			for (int index = 0; index < arguments.length; index++) {
				Object argument = arguments[index];
				// SQLite has no java.time type: an instant is bound with the same text shape Hibernate
				// writes and reads, so a value written here is readable by an entity query and the other
				// way round.
				statement.setObject(index + 1, argument instanceof Instant instant
						? java.sql.Timestamp.from(instant)
						: argument);
			}
			return statement;
		}, keyHolder);
		Number key = keyHolder.getKey();
		return key == null ? null : key.longValue();
	}

	/**
	 * Columns of a failure record.
	 *
	 * @param recordType the canonical record type token
	 * @param failureType the canonical failure type token
	 * @param severity the canonical severity token
	 * @param occurredAt the instant of the event
	 * @param agentId the agent that reported the event
	 * @param procedureId the procedure when known
	 * @param sessionId the OpenCode session when known
	 * @param writeSource the canonical write source token
	 * @param idempotencyKey the key of the write
	 */
	public record FailureEventRow(String recordType, String failureType, String severity, Instant occurredAt,
			String agentId, Long procedureId, String sessionId, String writeSource, String idempotencyKey) {
	}

	/**
	 * Columns of an error detail.
	 *
	 * @param failureEventId the failure record this detail belongs to
	 * @param code the stable error code
	 * @param message the English message
	 * @param fieldOrResource the field or resource, may be null
	 * @param failureType the canonical failure type token
	 * @param severity the canonical severity token
	 * @param agentId the agent that reported the event
	 * @param occurredAt the instant of the event
	 */
	public record ErrorDetailRow(Long failureEventId, String code, String message, String fieldOrResource,
			String failureType, String severity, String agentId, Instant occurredAt) {
	}

	/**
	 * Columns of a hallucination record.
	 *
	 * @param occurredAt the instant of the event
	 * @param agentId the agent that produced the invented information
	 * @param procedureId the procedure when known
	 * @param sessionId the OpenCode session when known
	 * @param responseRef the reference of the response
	 * @param recordType the canonical record type token
	 * @param writeSource the canonical write source token
	 * @param idempotencyKey the key of the write
	 */
	public record HallucinationRow(Instant occurredAt, String agentId, Long procedureId, String sessionId,
			String responseRef, String recordType, String writeSource, String idempotencyKey) {
	}

	/**
	 * Columns of a quality evaluation.
	 *
	 * @param occurredAt the instant of the evaluation
	 * @param agentId the agent that evaluated the session
	 * @param procedureId the procedure when known
	 * @param sessionId the OpenCode session when known
	 * @param score the session score
	 * @param adherenceLevel the adherence level
	 * @param evidenceLevel the evidence level
	 * @param processLevel the process level
	 * @param artifactLevel the artifact level
	 * @param honestyLevel the honesty level
	 * @param rationale the rationale
	 * @param authority the canonical authority token
	 * @param writeSource the canonical write source token
	 * @param idempotencyKey the key of the write
	 */
	public record QualityRow(Instant occurredAt, String agentId, Long procedureId, String sessionId, int score,
			int adherenceLevel, int evidenceLevel, int processLevel, int artifactLevel, int honestyLevel,
			String rationale, String authority, String writeSource, String idempotencyKey) {
	}

	/**
	 * Columns of a graph usage record.
	 *
	 * @param occurredAt the instant of the usage
	 * @param agentId the agent that used the graph
	 * @param procedureId the procedure when known
	 * @param sessionId the OpenCode session when known
	 * @param queryCount the number of queries
	 * @param distinctNodes the number of distinct nodes
	 * @param consultedBeforeWork whether the graph was consulted before the work
	 * @param recordType the canonical record type token
	 * @param writeSource the canonical write source token
	 * @param idempotencyKey the key of the write
	 */
	public record GraphUsageRow(Instant occurredAt, String agentId, Long procedureId, String sessionId,
			int queryCount, int distinctNodes, boolean consultedBeforeWork, String recordType, String writeSource,
			String idempotencyKey) {
	}

	/**
	 * Columns of a token economy record.
	 *
	 * @param occurredAt the instant of the record
	 * @param agentId the agent of the record
	 * @param procedureId the procedure when known
	 * @param sessionId the OpenCode session when known
	 * @param inputTokens the input tokens
	 * @param outputTokens the output tokens
	 * @param baselineInputTokens the baseline input tokens, may be null
	 * @param baselineOutputTokens the baseline output tokens, may be null
	 * @param savingsTokens the saving, may be null
	 * @param measurable whether the saving is measurable
	 * @param notMeasurableReason the reason when it is not measurable, may be null
	 * @param modelId the model identifier
	 * @param recordType the canonical record type token
	 * @param writeSource the canonical write source token
	 * @param idempotencyKey the key of the write
	 */
	public record TokenEconomyRow(Instant occurredAt, String agentId, Long procedureId, String sessionId,
			long inputTokens, long outputTokens, Long baselineInputTokens, Long baselineOutputTokens,
			Long savingsTokens, boolean measurable, String notMeasurableReason, String modelId, String recordType,
			String writeSource, String idempotencyKey) {
	}

	/**
	 * Columns of a source access record.
	 *
	 * @param occurredAt the instant of the access
	 * @param agentId the agent that consulted the source
	 * @param procedureId the procedure when known
	 * @param sessionId the OpenCode session when known
	 * @param source the redacted source
	 * @param origin the canonical origin token
	 * @param taskContext the redacted task context
	 * @param purpose the redacted purpose
	 * @param result the canonical result token
	 * @param cited whether the source was cited, may be null
	 * @param redacted whether a secret was removed
	 * @param recordType the canonical record type token
	 * @param writeSource the canonical write source token
	 * @param idempotencyKey the key of the write
	 */
	public record SourceRow(Instant occurredAt, String agentId, Long procedureId, String sessionId, String source,
			String origin, String taskContext, String purpose, String result, Boolean cited, boolean redacted,
			String recordType, String writeSource, String idempotencyKey) {
	}

	/**
	 * Columns of a download record.
	 *
	 * @param occurredAt the instant of the download
	 * @param agentId the agent that downloaded
	 * @param procedureId the procedure when known
	 * @param sessionId the OpenCode session when known
	 * @param source the redacted source
	 * @param item the redacted item
	 * @param itemType the item type
	 * @param sizeBytes the size in bytes
	 * @param result the canonical result token
	 * @param destination the redacted destination
	 * @param redacted whether a secret was removed
	 * @param recordType the canonical record type token
	 * @param writeSource the canonical write source token
	 * @param idempotencyKey the key of the write
	 */
	public record DownloadRow(Instant occurredAt, String agentId, Long procedureId, String sessionId, String source,
			String item, String itemType, long sizeBytes, String result, String destination, boolean redacted,
			String recordType, String writeSource, String idempotencyKey) {
	}

	/**
	 * Columns of an upload record.
	 *
	 * @param occurredAt the instant of the upload
	 * @param agentId the agent that uploaded
	 * @param procedureId the procedure when known
	 * @param sessionId the OpenCode session when known
	 * @param destination the redacted destination
	 * @param item the redacted item
	 * @param itemType the item type
	 * @param sizeBytes the size in bytes
	 * @param result the canonical result token
	 * @param sent whether anything was sent
	 * @param redacted whether a secret was removed
	 * @param recordType the canonical record type token
	 * @param writeSource the canonical write source token
	 * @param idempotencyKey the key of the write
	 */
	public record UploadRow(Instant occurredAt, String agentId, Long procedureId, String sessionId,
			String destination, String item, String itemType, long sizeBytes, String result, boolean sent,
			boolean redacted, String recordType, String writeSource, String idempotencyKey) {
	}
}
