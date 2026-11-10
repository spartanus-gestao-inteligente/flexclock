package br.com.flexclock.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.dto.ReportAggregates;
import br.com.flexclock.dto.ReportRecordRequest;
import br.com.flexclock.dto.ReportRecordResponse;
import br.com.flexclock.dto.ReportResponse;
import br.com.flexclock.enums.EvaluationAuthority;
import br.com.flexclock.enums.EventResult;
import br.com.flexclock.enums.FailureType;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.Severity;
import br.com.flexclock.enums.SourceOrigin;
import br.com.flexclock.support.ReportRequestBuilder;
import br.com.flexclock.support.SqliteIntegrationTest;

/**
 * Proves the aggregation of the nine families over records written through the real ingestion path.
 *
 * <p>Each test owns its own local day, so the assertions describe only the records it wrote. The tests
 * prove the metric definitions of decision 0019: the time report counts overlapping sessions once and
 * subtracts the paused time, the quality average is not available without an evaluation, the token
 * saving is null when it is not measurable, an empty upload period is an explicit zero, and valid and
 * error records never share a series.
 *
 * <p>Requirements RF-051 to RF-066.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportAggregationTest extends SqliteIntegrationTest {

	private static final String TIMEZONE = "UTC";

	private static final String LOCALE = "en";

	@Autowired
	private ReportService reportService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void theTimeReportSubtractsThePauseAndCountsOverlappingSessionsOnce() {
		Long procedureId = insertProcedure("ses_time", "Report surface", "2026-03-02T08:00:00Z");
		insertWorkSession(procedureId, "2026-03-02T09:00:00Z", "2026-03-02T12:00:00Z", 1_800_000L);
		insertWorkSession(procedureId, "2026-03-02T11:00:00Z", "2026-03-02T13:00:00Z", 0L);

		ReportResponse report = reportService.report("time", "custom", "2026-03-02", "2026-03-03", TIMEZONE,
				LOCALE, null);
		ReportAggregates.Time time = (ReportAggregates.Time) report.aggregates();

		// The union of 09:00-12:00 and 11:00-13:00 is four hours, so the overlap is counted once.
		assertThat(time.totalMs()).isEqualTo(4 * 3_600_000L);
		assertThat(time.pausedMs()).isEqualTo(1_800_000L);
		assertThat(time.activeMs()).isEqualTo(4 * 3_600_000L - 1_800_000L);
		assertThat(time.sessionCount()).isEqualTo(2);
		assertThat(time.days()).isEqualTo(1);
		assertThat(time.rows()).hasSize(2);
		assertThat(time.rows().getFirst().procedureTitle()).isEqualTo("Report surface");
		assertThat(time.rows().getFirst().sessionId()).isEqualTo("ses_time");
		assertThat(report.chartSeries()).hasSize(2);
		assertThat(report.empty()).isFalse();
	}

	@Test
	void aSessionThatCrossesMidnightOccupiesTwoRows() {
		Long procedureId = insertProcedure("ses_midnight", "Overnight session", "2026-03-03T22:00:00Z");
		insertWorkSession(procedureId, "2026-03-03T23:00:00Z", "2026-03-04T01:00:00Z", 0L);

		ReportResponse report = reportService.report("time", "custom", "2026-03-03", "2026-03-05", TIMEZONE,
				LOCALE, null);
		ReportAggregates.Time time = (ReportAggregates.Time) report.aggregates();

		assertThat(time.rows()).hasSize(2);
		assertThat(time.rows().get(0).dayLabel()).isEqualTo("2026-03-03");
		assertThat(time.rows().get(1).dayLabel()).isEqualTo("2026-03-04");
		assertThat(time.rows().get(0).activeMs()).isEqualTo(3_600_000L);
		assertThat(time.rows().get(1).activeMs()).isEqualTo(3_600_000L);
		assertThat(time.days()).isEqualTo(2);
		assertThat(time.sessionCount()).isEqualTo(1);
	}

	@Test
	void theFailuresReportKeepsErrorsInTheirOwnSeriesAndReadsTheErrorDetail() {
		append(ReportRequestBuilder.of(ReportRecordType.FAILURE)
				.failureType(FailureType.TOOL_ERROR)
				.severity(Severity.MEDIUM)
				.occurredAt(Instant.parse("2026-03-05T10:00:00Z"))
				.agentId("agent-1")
				.sessionId("ses_failures")
				.build(), "agg-failure-1");
		append(ReportRequestBuilder.of(ReportRecordType.ERRORS)
				.failureType(FailureType.DB_WRITE_FAILURE)
				.severity(Severity.CRITICAL)
				.occurredAt(Instant.parse("2026-03-05T11:00:00Z"))
				.agentId("agent-1")
				.sessionId("ses_failures")
				.code("DB_WRITE_FAILURE")
				.message("The database write failed.")
				.fieldOrResource("failure_event")
				.build(), "agg-failure-2");

		ReportResponse report = reportService.report("failures", "custom", "2026-03-05", "2026-03-06", TIMEZONE,
				LOCALE, null);
		ReportAggregates.Failures failures = (ReportAggregates.Failures) report.aggregates();

		assertThat(failures.total()).isEqualTo(2);
		assertThat(failures.validCount()).isEqualTo(1);
		assertThat(failures.errorCount()).isEqualTo(1);
		assertThat(report.errorCount()).isEqualTo(1);
		assertThat(failures.byType()).containsEntry("tool_error", 1).containsEntry("db_write_failure", 1);
		assertThat(failures.bySeverity()).containsEntry("medium", 1).containsEntry("critical", 1);
		assertThat(failures.rows()).hasSize(2);
		assertThat(failures.rows().get(1).error()).isTrue();
		assertThat(failures.rows().get(1).code()).isEqualTo("DB_WRITE_FAILURE");
		assertThat(failures.rows().get(1).message()).isEqualTo("The database write failed.");
		assertThat(failures.rows().get(0).error()).isFalse();
		assertThat(report.donut()).isNotEmpty();
	}

	@Test
	void theQualityReportAveragesTheScoresAndNeverReportsZeroWithoutAnEvaluation() {
		append(quality(10, "2026-03-06T10:00:00Z", "ses_quality_a"), "agg-quality-1");
		append(quality(6, "2026-03-06T11:00:00Z", "ses_quality_b"), "agg-quality-2");

		ReportResponse report = reportService.report("quality", "custom", "2026-03-06", "2026-03-07", TIMEZONE,
				LOCALE, null);
		ReportAggregates.Quality quality = (ReportAggregates.Quality) report.aggregates();

		assertThat(quality.averageScore()).isEqualTo(8.0);
		assertThat(quality.evaluations()).isEqualTo(2);
		assertThat(quality.bands()).containsEntry("10", 1).containsEntry("5-7", 1);
		assertThat(quality.averageLevels()).containsEntry("adherence", 4.0);
		assertThat(report.donut()).hasSize(4);

		ReportResponse empty = reportService.report("quality", "custom", "2026-03-20", "2026-03-21", TIMEZONE,
				LOCALE, null);
		assertThat(((ReportAggregates.Quality) empty.aggregates()).averageScore()).isNull();
		assertThat(empty.empty()).isTrue();
		assertThat(empty.notMeasurable()).anyMatch(reason -> reason.startsWith("quality_average"));
	}

	@Test
	void theGraphUsageReportCountsTheShareOfSessionsThatConsultedTheGraphBeforeTheWork() {
		append(ReportRequestBuilder.of(ReportRecordType.GRAPH_USAGE)
				.occurredAt(Instant.parse("2026-03-07T10:00:00Z"))
				.agentId("agent-1")
				.sessionId("ses_graph_a")
				.queryCount(5)
				.distinctNodes(12)
				.consultedBeforeWork(true)
				.build(), "agg-graph-1");
		append(ReportRequestBuilder.of(ReportRecordType.GRAPH_USAGE)
				.occurredAt(Instant.parse("2026-03-07T11:00:00Z"))
				.agentId("jessica")
				.sessionId("ses_graph_b")
				.queryCount(3)
				.distinctNodes(7)
				.consultedBeforeWork(false)
				.build(), "agg-graph-2");

		ReportResponse report = reportService.report("graph_usage", "custom", "2026-03-07", "2026-03-08", TIMEZONE,
				LOCALE, null);
		ReportAggregates.GraphUsage usage = (ReportAggregates.GraphUsage) report.aggregates();

		assertThat(usage.queries()).isEqualTo(8);
		assertThat(usage.distinctNodes()).isEqualTo(19);
		assertThat(usage.sessions()).isEqualTo(2);
		assertThat(usage.sessionsBeforeWork()).isEqualTo(1);
		assertThat(usage.beforeWorkShare()).isEqualTo(50.0);
		assertThat(report.donut()).hasSize(2);
	}

	@Test
	void theTokenReportLeavesTheSavingEmptyWhenItIsNotMeasurable() {
		append(token(false, null, "the baseline is absent", "2026-03-08T10:00:00Z"), "agg-token-1");

		ReportResponse notMeasurable = reportService.report("token_economy", "custom", "2026-03-08", "2026-03-09",
				TIMEZONE, LOCALE, null);
		ReportAggregates.TokenEconomy withoutBaseline = (ReportAggregates.TokenEconomy) notMeasurable.aggregates();

		assertThat(withoutBaseline.inputTokens()).isEqualTo(1000L);
		assertThat(withoutBaseline.outputTokens()).isEqualTo(500L);
		assertThat(withoutBaseline.savingsTokens()).isNull();
		assertThat(withoutBaseline.measurable()).isFalse();
		assertThat(withoutBaseline.notMeasurableRecords()).isEqualTo(1);
		assertThat(notMeasurable.notMeasurable()).anyMatch(reason -> reason.startsWith("token_savings"));
		assertThat(notMeasurable.chartSeries()).hasSize(1);

		append(token(true, 400L, null, "2026-03-09T10:00:00Z"), "agg-token-2");

		ReportResponse measurable = reportService.report("token_economy", "custom", "2026-03-09", "2026-03-10",
				TIMEZONE, LOCALE, null);
		ReportAggregates.TokenEconomy withBaseline = (ReportAggregates.TokenEconomy) measurable.aggregates();

		assertThat(withBaseline.savingsTokens()).isEqualTo(400L);
		assertThat(withBaseline.measurable()).isTrue();
		assertThat(measurable.chartSeries()).hasSize(2);
	}

	@Test
	void theSourcesReportRedactsASecretBeforePersistingTheRecord() {
		ReportRecordResponse response = append(ReportRequestBuilder.of(ReportRecordType.SOURCE)
				.occurredAt(Instant.parse("2026-03-10T10:00:00Z"))
				.agentId("agent-1")
				.sessionId("ses_source")
				.source("https://api.example.com/v1?api_key=SUPERSECRETVALUE")
				.origin(SourceOrigin.DOMAIN)
				.taskContext("check the contract")
				.purpose("confirm the endpoint")
				.result(EventResult.SUCCESS)
				.cited(true)
				.build(), "agg-source-1");

		assertThat(response.redacted()).isTrue();

		ReportResponse report = reportService.report("sources", "custom", "2026-03-10", "2026-03-11", TIMEZONE,
				LOCALE, null);
		ReportAggregates.Sources sources = (ReportAggregates.Sources) report.aggregates();

		assertThat(sources.accesses()).isEqualTo(1);
		assertThat(sources.cited()).isEqualTo(1);
		assertThat(sources.rows().getFirst().source()).doesNotContain("SUPERSECRETVALUE");
		assertThat(sources.rows().getFirst().source()).contains("[REDACTED]");
		assertThat(sources.rows().getFirst().redacted()).isTrue();
		assertThat(sources.byOrigin()).containsEntry("domain", 1);
	}

	@Test
	void theTransferReportsReturnTheSuccessRateAndAnExplicitZeroForAnEmptyPeriod() {
		append(ReportRequestBuilder.of(ReportRecordType.DOWNLOAD)
				.occurredAt(Instant.parse("2026-03-11T10:00:00Z"))
				.agentId("agent-1")
				.sessionId("ses_transfer")
				.source("maven central")
				.destination("/tmp/repo")
				.item("jasperreports.jar")
				.itemType("jar")
				.sizeBytes(2_048L)
				.result(EventResult.SUCCESS)
				.build(), "agg-download-1");
		append(ReportRequestBuilder.of(ReportRecordType.DOWNLOAD)
				.occurredAt(Instant.parse("2026-03-11T11:00:00Z"))
				.agentId("agent-1")
				.sessionId("ses_transfer")
				.source("maven central")
				.destination("/tmp/repo")
				.item("missing.jar")
				.itemType("jar")
				.sizeBytes(0L)
				.result(EventResult.FAILURE)
				.build(), "agg-download-2");
		append(ReportRequestBuilder.of(ReportRecordType.UPLOAD)
				.occurredAt(Instant.parse("2026-03-11T12:00:00Z"))
				.agentId("agent-1")
				.sessionId("ses_transfer")
				.destination("s3://bucket")
				.item("evidence.json")
				.itemType("json")
				.sizeBytes(1_024L)
				.result(EventResult.SUCCESS)
				.sent(true)
				.build(), "agg-upload-1");

		ReportResponse downloads = reportService.report("downloads", "custom", "2026-03-11", "2026-03-12",
				TIMEZONE, LOCALE, null);
		ReportAggregates.Downloads downloadAggregates = (ReportAggregates.Downloads) downloads.aggregates();

		assertThat(downloadAggregates.count()).isEqualTo(2);
		assertThat(downloadAggregates.volumeBytes()).isEqualTo(2_048L);
		assertThat(downloadAggregates.successes()).isEqualTo(1);
		assertThat(downloadAggregates.failures()).isEqualTo(1);
		assertThat(downloadAggregates.successRate()).isEqualTo(50.0);
		assertThat(downloadAggregates.throughputBytesPerSecond()).isNull();
		assertThat(downloads.notMeasurable()).anyMatch(reason -> reason.startsWith("download_throughput"));

		ReportResponse uploads = reportService.report("uploads", "custom", "2026-03-11", "2026-03-12", TIMEZONE,
				LOCALE, null);
		ReportAggregates.Uploads uploadAggregates = (ReportAggregates.Uploads) uploads.aggregates();

		assertThat(uploadAggregates.count()).isEqualTo(1);
		assertThat(uploadAggregates.sent()).isEqualTo(1);
		assertThat(uploadAggregates.byDestination()).containsEntry("s3://bucket", 1);

		ReportResponse empty = reportService.report("uploads", "custom", "2026-03-25", "2026-03-26", TIMEZONE,
				LOCALE, null);

		assertThat(((ReportAggregates.Uploads) empty.aggregates()).count()).isZero();
		assertThat(empty.empty()).isTrue();
		assertThat(empty.chartSeries().getFirst().points()).isEmpty();
	}

	@Test
	void aRepeatedIdempotencyKeyDoesNotDuplicateTheRecord() {
		ReportRecordRequest request = quality(9, "2026-03-12T10:00:00Z", "ses_idempotent");

		ReportRecordResponse first = append(request, "agg-idempotent-1");
		ReportRecordResponse second = append(request, "agg-idempotent-1");

		assertThat(first.recordId()).isNotNull();
		assertThat(second.family()).isEqualTo(first.family());
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from quality_evaluation where idempotency_key = 'agg-idempotent-1'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void theSameKeyWithAnotherPayloadIsAConflict() {
		append(quality(9, "2026-03-13T10:00:00Z", "ses_conflict"), "agg-conflict-1");

		assertThatThrownBy(() -> append(quality(2, "2026-03-13T10:00:00Z", "ses_conflict"), "agg-conflict-1"))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

	@Test
	void aValueOutsideTheClosedDomainIsRefusedBeforeAnythingIsPersisted() {
		assertThatThrownBy(() -> append(ReportRequestBuilder.of(ReportRecordType.QUALITY)
				.occurredAt(Instant.parse("2026-03-14T10:00:00Z"))
				.sessionId("ses_range")
				.score(11)
				.levels(4)
				.rationale("out of range")
				.authority(EvaluationAuthority.SELF)
				.build(), "agg-range-1"))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.VALUE_OUT_OF_RANGE));
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from quality_evaluation where idempotency_key = 'agg-range-1'", Integer.class))
				.isZero();
	}

	private ReportRecordResponse append(ReportRecordRequest request, String idempotencyKey) {
		return reportService.append(familyOf(request.recordType()), request, idempotencyKey);
	}

	private String familyOf(ReportRecordType recordType) {
		return switch (recordType) {
			case FAILURE, ERRORS -> "failures";
			case HALLUCINATION -> "hallucination";
			case QUALITY -> "quality";
			case GRAPH_USAGE -> "graph_usage";
			case TOKEN_ECONOMY -> "token_economy";
			case SOURCE -> "sources";
			case DOWNLOAD -> "downloads";
			case UPLOAD -> "uploads";
		};
	}

	private ReportRecordRequest quality(int score, String occurredAt, String sessionId) {
		return ReportRequestBuilder.of(ReportRecordType.QUALITY)
				.occurredAt(Instant.parse(occurredAt))
				.agentId("agent-1")
				.sessionId(sessionId)
				.score(score)
				.levels(4)
				.rationale("All criteria at level 4.")
				.authority(EvaluationAuthority.SELF)
				.build();
	}

	private ReportRecordRequest token(boolean measurable, Long savings, String reason, String occurredAt) {
		ReportRequestBuilder builder = ReportRequestBuilder.of(ReportRecordType.TOKEN_ECONOMY)
				.occurredAt(Instant.parse(occurredAt))
				.agentId("agent-1")
				.sessionId("ses_token")
				.inputTokens(1000L)
				.outputTokens(500L)
				.modelId("deepseek-v4.1-flash")
				.measurable(measurable)
				.savingsTokens(savings);
		if (measurable) {
			builder.baseline(900L, 400L);
		}
		else {
			builder.notMeasurableReason(reason);
		}
		return builder.build();
	}

	private Long insertProcedure(String sessionId, String title, String startedAt) {
		Instant instant = Instant.parse(startedAt);
		jdbcTemplate.update("""
				insert into procedure (session_id, title, directory, status, started_at, created_at, updated_at)
				values (?, ?, '/project', 'active', ?, ?, ?)
				""", sessionId, title, Timestamp.from(instant), Timestamp.from(instant), Timestamp.from(instant));
		return jdbcTemplate.queryForObject("select id from procedure where session_id = ?", Long.class, sessionId);
	}

	private void insertWorkSession(Long procedureId, String startedAt, String endedAt, long pausedMs) {
		Instant start = Instant.parse(startedAt);
		Instant end = Instant.parse(endedAt);
		long total = end.toEpochMilli() - start.toEpochMilli();
		jdbcTemplate.update("""
				insert into work_session (procedure_id, mode, started_at, ended_at, total_ms, paused_ms, active_ms,
					timezone)
				values (?, 'stopwatch', ?, ?, ?, ?, ?, 'UTC')
				""", procedureId, Timestamp.from(start), Timestamp.from(end), total, pausedMs, total - pausedMs);
	}
}
