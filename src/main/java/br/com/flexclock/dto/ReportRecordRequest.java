package br.com.flexclock.dto;

import java.time.Instant;

import br.com.flexclock.enums.EvaluationAuthority;
import br.com.flexclock.enums.EventResult;
import br.com.flexclock.enums.FailureType;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.Severity;
import br.com.flexclock.enums.SourceOrigin;

/**
 * One appended record of a report family.
 *
 * <p>One request shape serves the eight ingestible families: the family in the path decides which
 * fields are required, and a field that belongs to another family is refused instead of being
 * silently dropped. The shape is the frozen contract shape of architecture contract section 6.2, so
 * the MCP sidecar and the event service can forward a record without remapping it.
 *
 * <p>Decisions 0014, 0033, 0056; requirements RF-045, RF-047, RF-053.
 *
 * @param recordType the closed record type, including {@code errors}
 * @param failureType the failure type of a failure or error record
 * @param severity the severity of a failure or error record
 * @param occurredAt the instant the event happened, in UTC
 * @param agentId the agent that produced the event
 * @param procedureId the procedure when known
 * @param sessionId the OpenCode session when known
 * @param source the consulted source or the origin of a download
 * @param origin the source origin: domain, corpus or graph
 * @param taskContext the task context of a consulted source
 * @param purpose the purpose of a consulted source
 * @param result the result of a source, download or upload
 * @param cited whether the source was cited in the answer or code
 * @param sent whether an upload actually sent anything
 * @param sizeBytes the size of a downloaded or uploaded item
 * @param destination the destination of a download or upload
 * @param item the name of the downloaded or uploaded item
 * @param itemType the type of the downloaded or uploaded item
 * @param score the session quality score, 0 to 10
 * @param adherenceLevel the adherence criterion level, 0 to 4
 * @param evidenceLevel the evidence criterion level, 0 to 4
 * @param processLevel the process criterion level, 0 to 4
 * @param artifactLevel the artifact criterion level, 0 to 4
 * @param honestyLevel the honesty criterion level, 0 to 4
 * @param rationale the rationale of the quality evaluation
 * @param authority the authority of the quality evaluation
 * @param queryCount the number of graph queries
 * @param distinctNodes the number of distinct graph nodes consulted
 * @param consultedBeforeWork whether the graph was consulted before answering or coding
 * @param inputTokens the input tokens captured by the instrument
 * @param outputTokens the output tokens captured by the instrument
 * @param baselineInputTokens the baseline input tokens when a control measurement exists
 * @param baselineOutputTokens the baseline output tokens when a control measurement exists
 * @param savingsTokens the saving, only when measurable
 * @param measurable whether the token saving is measurable
 * @param notMeasurableReason the reason when the token saving is not measurable
 * @param modelId the model identifier of the token economy record
 * @param responseRef the reference of the response that carried the invented information
 * @param code the stable error code of an error record
 * @param message the English message of an error record
 * @param fieldOrResource the field or resource of an error record
 */
public record ReportRecordRequest(
		ReportRecordType recordType,
		FailureType failureType,
		Severity severity,
		Instant occurredAt,
		String agentId,
		Long procedureId,
		String sessionId,
		String source,
		SourceOrigin origin,
		String taskContext,
		String purpose,
		EventResult result,
		Boolean cited,
		Boolean sent,
		Boolean redacted,
		Long sizeBytes,
		String destination,
		String item,
		String itemType,
		Integer score,
		Integer adherenceLevel,
		Integer evidenceLevel,
		Integer processLevel,
		Integer artifactLevel,
		Integer honestyLevel,
		String rationale,
		EvaluationAuthority authority,
		Integer queryCount,
		Integer distinctNodes,
		Boolean consultedBeforeWork,
		Long inputTokens,
		Long outputTokens,
		Long baselineInputTokens,
		Long baselineOutputTokens,
		Long savingsTokens,
		Boolean measurable,
		String notMeasurableReason,
		String modelId,
		String responseRef,
		String code,
		String message,
		String fieldOrResource) {
}
