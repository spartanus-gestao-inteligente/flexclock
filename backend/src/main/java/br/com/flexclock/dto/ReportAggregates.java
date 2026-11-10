package br.com.flexclock.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregates of one report family for one resolved period.
 *
 * <p>Every family has its own shape because the metrics differ, so the dashboard receives the numbers
 * it plots instead of a generic map it would have to interpret. Error records always sit in their own
 * series and the error count is always explicit: no aggregate ever adds valid and error records
 * together. A metric that cannot be computed is {@code null} and its reason is listed in
 * {@link ReportResponse#notMeasurable()}, never replaced by a zero.
 *
 * <p>Decisions 0019, 0053, 0054, 0056; requirements RF-051, RF-055 to RF-064.
 */
public sealed interface ReportAggregates {

	/**
	 * Time report aggregates.
	 *
	 * @param totalMs the total duration of the sessions in the period
	 * @param pausedMs the paused time of the period
	 * @param activeMs the active coding time, pauses subtracted
	 * @param sessionCount the number of work sessions in the period
	 * @param days the number of local days with activity
	 * @param rows the per session and per local day rows, split at midnight
	 */
	record Time(long totalMs, long pausedMs, long activeMs, int sessionCount, int days, List<TimeRow> rows)
			implements ReportAggregates {
	}

	/**
	 * One row of the time report: a session, split at midnight when it crosses a local day.
	 *
	 * @param start the inclusive start of the row
	 * @param end the exclusive end of the row
	 * @param durationMs the duration of the row
	 * @param pausedMs the paused time inside the row
	 * @param activeMs the active time inside the row
	 * @param procedureTitle the procedure title when known
	 * @param sessionId the OpenCode session
	 * @param dayLabel the local day of the row, ISO 8601
	 */
	public record TimeRow(Instant start, Instant end, long durationMs, long pausedMs, long activeMs,
			String procedureTitle, String sessionId, String dayLabel) {
	}

	/**
	 * Failures aggregates. The failures report is the error report.
	 *
	 * @param total the number of failure records in the period
	 * @param validCount the records that are not error records
	 * @param errorCount the records whose type is errors
	 * @param byType the count by failure type
	 * @param bySeverity the count by severity
	 * @param rows the records of the period, error records included and flagged
	 */
	record Failures(int total, int validCount, int errorCount, Map<String, Integer> byType,
			Map<String, Integer> bySeverity, List<FailureRow> rows) implements ReportAggregates {
	}

	/**
	 * One failure or error record.
	 *
	 * @param occurredAt the instant the event happened
	 * @param failureType the failure type token
	 * @param severity the severity token
	 * @param agentId the agent that reported the event
	 * @param sessionId the OpenCode session when known
	 * @param error true when the record type is errors, so the dashboard flags it without hiding it
	 * @param code the stable error code, when an error detail exists
	 * @param message the English error message, when an error detail exists
	 * @param fieldOrResource the field or resource of the error detail, when available
	 */
	public record FailureRow(Instant occurredAt, String failureType, String severity, String agentId,
			String sessionId, boolean error, String code, String message, String fieldOrResource) {
	}

	/**
	 * Hallucination aggregates.
	 *
	 * @param confirmed the number of confirmed hallucination events
	 * @param responses the number of agent responses used as denominator, null when it is unavailable
	 * @param rate the confirmed events divided by the responses, null when the denominator is unavailable
	 * @param rows the confirmed events of the period
	 */
	record Hallucination(int confirmed, Long responses, Double rate, List<HallucinationRow> rows)
			implements ReportAggregates {
	}

	/**
	 * One confirmed hallucination event.
	 *
	 * @param occurredAt the instant of the event
	 * @param agentId the agent that produced the invented information
	 * @param sessionId the OpenCode session when known
	 * @param responseRef the reference of the response
	 */
	public record HallucinationRow(Instant occurredAt, String agentId, String sessionId, String responseRef) {
	}

	/**
	 * Quality aggregates.
	 *
	 * @param averageScore the average of the session scores, null when the period has no evaluation
	 * @param evaluations the number of evaluations in the period
	 * @param averageLevels the average level of each of the five criteria
	 * @param bands the number of evaluations by score band
	 * @param rows the evaluations of the period
	 */
	record Quality(Double averageScore, int evaluations, Map<String, Double> averageLevels,
			Map<String, Integer> bands, List<QualityRow> rows) implements ReportAggregates {
	}

	/**
	 * One quality evaluation.
	 *
	 * @param occurredAt the instant of the evaluation
	 * @param sessionId the OpenCode session when known
	 * @param score the session score, 0 to 10
	 * @param adherenceLevel the adherence level, 0 to 4
	 * @param evidenceLevel the evidence level, 0 to 4
	 * @param processLevel the process level, 0 to 4
	 * @param artifactLevel the artifact level, 0 to 4
	 * @param honestyLevel the honesty level, 0 to 4
	 * @param authority the authority: SELF or OWNER
	 * @param rationale the rationale of the evaluation
	 */
	public record QualityRow(Instant occurredAt, String sessionId, int score, int adherenceLevel,
			int evidenceLevel, int processLevel, int artifactLevel, int honestyLevel, String authority,
			String rationale) {
	}

	/**
	 * Graph usage aggregates.
	 *
	 * @param queries the number of graph queries in the period
	 * @param distinctNodes the sum of the distinct nodes consulted
	 * @param sessions the number of sessions that recorded graph usage
	 * @param sessionsBeforeWork the number of sessions that consulted the graph before answering or coding
	 * @param beforeWorkShare the share of sessions that consulted the graph before the work, null when
	 * there is no graph session
	 * @param rows the graph usage records of the period
	 */
	record GraphUsage(long queries, long distinctNodes, int sessions, int sessionsBeforeWork,
			Double beforeWorkShare, List<GraphUsageRow> rows) implements ReportAggregates {
	}

	/**
	 * One graph usage record.
	 *
	 * @param occurredAt the instant of the usage
	 * @param agentId the agent that used the graph
	 * @param sessionId the OpenCode session when known
	 * @param queryCount the number of queries
	 * @param distinctNodes the number of distinct nodes
	 * @param consultedBeforeWork whether the graph was consulted before the work
	 */
	public record GraphUsageRow(Instant occurredAt, String agentId, String sessionId, int queryCount,
			int distinctNodes, boolean consultedBeforeWork) {
	}

	/**
	 * Token economy aggregates.
	 *
	 * @param inputTokens the input tokens spent in the period
	 * @param outputTokens the output tokens spent in the period
	 * @param savingsTokens the saving over the baseline, null when nothing in the period is measurable
	 * @param measurable true when at least one record is measurable
	 * @param measurableRecords the number of measurable records
	 * @param notMeasurableRecords the number of records declared not measurable
	 * @param notMeasurableReasons the distinct reasons of the not measurable records
	 * @param rows the token economy records of the period
	 */
	record TokenEconomy(long inputTokens, long outputTokens, Long savingsTokens, boolean measurable,
			int measurableRecords, int notMeasurableRecords, List<String> notMeasurableReasons,
			List<TokenEconomyRow> rows) implements ReportAggregates {
	}

	/**
	 * One token economy record.
	 *
	 * @param occurredAt the instant of the record
	 * @param modelId the model identifier
	 * @param inputTokens the input tokens captured by the instrument
	 * @param outputTokens the output tokens captured by the instrument
	 * @param baselineInputTokens the baseline input tokens, null when there is no control measurement
	 * @param baselineOutputTokens the baseline output tokens, null when there is no control measurement
	 * @param savingsTokens the saving, null when the metric is not measurable
	 * @param measurable whether the saving is measurable
	 * @param notMeasurableReason the reason when it is not measurable
	 */
	public record TokenEconomyRow(Instant occurredAt, String modelId, long inputTokens, long outputTokens,
			Long baselineInputTokens, Long baselineOutputTokens, Long savingsTokens, boolean measurable,
			String notMeasurableReason) {
	}

	/**
	 * Sources aggregates.
	 *
	 * @param accesses the number of source accesses in the period
	 * @param distinctSources the number of distinct sources
	 * @param byOrigin the count by origin: domain, corpus or graph
	 * @param byAgent the count by agent
	 * @param cited the number of accesses whose source was cited
	 * @param failures the number of accesses that ended in failure
	 * @param rows the source accesses of the period
	 */
	record Sources(int accesses, int distinctSources, Map<String, Integer> byOrigin,
			Map<String, Integer> byAgent, int cited, int failures, List<SourceRow> rows)
			implements ReportAggregates {
	}

	/**
	 * One consulted source.
	 *
	 * @param occurredAt the instant of the access
	 * @param agentId the agent that consulted the source
	 * @param sessionId the OpenCode session when known
	 * @param origin the origin token
	 * @param source the redacted source
	 * @param purpose the purpose of the access
	 * @param result the result token
	 * @param cited whether the source was cited
	 * @param redacted whether a secret was removed before the record was persisted
	 */
	public record SourceRow(Instant occurredAt, String agentId, String sessionId, String origin, String source,
			String purpose, String result, Boolean cited, boolean redacted) {
	}

	/**
	 * Downloads aggregates.
	 *
	 * @param count the number of downloads in the period
	 * @param volumeBytes the total downloaded bytes
	 * @param successes the downloads that succeeded
	 * @param failures the downloads that failed
	 * @param successRate the success rate, null when the period has no download
	 * @param throughputBytesPerSecond the throughput, null when it cannot be measured
	 * @param byAgent the count by agent
	 * @param bySource the count by source
	 * @param byType the count by item type
	 * @param rows the downloads of the period
	 */
	record Downloads(int count, long volumeBytes, int successes, int failures, Double successRate,
			Double throughputBytesPerSecond, Map<String, Integer> byAgent, Map<String, Integer> bySource,
			Map<String, Integer> byType, List<TransferRow> rows) implements ReportAggregates {
	}

	/**
	 * One download or upload record.
	 *
	 * @param occurredAt the instant of the transfer
	 * @param agentId the agent that performed the transfer
	 * @param sessionId the OpenCode session when known
	 * @param source the origin of a download, null for an upload
	 * @param destination the destination of an upload, null for a download
	 * @param item the item name
	 * @param itemType the item type
	 * @param sizeBytes the size in bytes
	 * @param result the result token
	 * @param sent whether an upload sent anything, null for a download
	 * @param redacted whether a secret was removed before the record was persisted
	 */
	public record TransferRow(Instant occurredAt, String agentId, String sessionId, String source,
			String destination, String item, String itemType, long sizeBytes, String result, Boolean sent,
			boolean redacted) {
	}

	/**
	 * Uploads aggregates.
	 *
	 * @param count the number of uploads in the period, zero when the period has none
	 * @param volumeBytes the total uploaded bytes
	 * @param successes the uploads that succeeded
	 * @param failures the uploads that failed
	 * @param successRate the success rate, null when the period has no upload
	 * @param sent the uploads that actually sent something
	 * @param byDestination the count by destination
	 * @param rows the uploads of the period
	 */
	record Uploads(int count, long volumeBytes, int successes, int failures, Double successRate, int sent,
			Map<String, Integer> byDestination, List<TransferRow> rows) implements ReportAggregates {
	}
}
