package br.com.flexclock.reports;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import br.com.flexclock.dto.ChartPoint;
import br.com.flexclock.dto.ChartSeries;
import br.com.flexclock.dto.DonutSlice;
import br.com.flexclock.dto.ReportAggregates;
import br.com.flexclock.dto.ReportResponse;
import br.com.flexclock.enums.EventResult;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.model.DownloadEvent;
import br.com.flexclock.model.ErrorDetail;
import br.com.flexclock.model.FailureEvent;
import br.com.flexclock.model.GraphUsageEvent;
import br.com.flexclock.model.HallucinationEvent;
import br.com.flexclock.model.Procedure;
import br.com.flexclock.model.QualityEvaluation;
import br.com.flexclock.model.SourceEvent;
import br.com.flexclock.model.TokenEconomyEvent;
import br.com.flexclock.model.UploadEvent;
import br.com.flexclock.model.WorkSession;

/**
 * Aggregates the nine report families for one resolved period.
 *
 * <p>Every family is a read only computation over the records of the window: this service never
 * writes, never updates and never deletes a record. Valid and error records are always kept apart, a
 * metric that cannot be computed is returned as null with its reason, and a period without a record is
 * flagged as empty instead of being reported as a zero that looks like data.
 *
 * <p>The time report splits a session at midnight of the configured timezone and counts overlapping
 * sessions once, so two clocks running together never inflate the coding time.
 *
 * <p>Decisions 0019, 0020, 0031, 0032, 0053, 0054, 0056; requirements RF-051 to RF-066.
 */
@Service
public class ReportAggregationService {

	private static final String NOT_AVAILABLE = "not available";

	private final ReportQueryRepository queryRepository;

	public ReportAggregationService(ReportQueryRepository queryRepository) {
		this.queryRepository = queryRepository;
	}

	/**
	 * Aggregates one family for a resolved window.
	 *
	 * @param family the family to aggregate
	 * @param window the resolved window
	 * @param locale the locale used for the labels
	 * @param now the reference instant, used as the end of an open work session
	 * @return the report response of the family
	 */
	public ReportResponse report(ReportFamily family, ReportPeriodWindow window, String locale, Instant now) {
		return switch (family) {
			case TIME -> time(window, locale, now);
			case FAILURES -> failures(window, locale);
			case HALLUCINATION -> hallucination(window, locale);
			case QUALITY -> quality(window, locale);
			case GRAPH_USAGE -> graphUsage(window, locale);
			case TOKEN_ECONOMY -> tokenEconomy(window, locale);
			case SOURCES -> sources(window, locale);
			case DOWNLOADS -> downloads(window, locale);
			case UPLOADS -> uploads(window, locale);
		};
	}

	// ---------------------------------------------------------------- time

	private ReportResponse time(ReportPeriodWindow window, String locale, Instant now) {
		List<WorkSession> sessions = queryRepository.workSessions(window, now);
		ZoneId zone = window.zone();
		Map<Long, Procedure> procedures = new LinkedHashMap<>();
		Set<Long> procedureIds = new LinkedHashSet<>();
		sessions.stream().map(WorkSession::getProcedureId).filter(java.util.Objects::nonNull)
				.forEach(procedureIds::add);
		for (Procedure procedure : queryRepository.procedures(procedureIds)) {
			procedures.put(procedure.getId(), procedure);
		}

		List<ReportAggregates.TimeRow> rows = new ArrayList<>();
		Map<LocalDate, List<Interval>> intervalsByDay = new TreeMap<>();
		Map<LocalDate, Long> pausedByDay = new TreeMap<>();
		Set<Long> sessionIds = new LinkedHashSet<>();

		for (WorkSession session : sessions) {
			Instant sessionStart = session.getStartedAt();
			Instant sessionEnd = session.getEndedAt() == null ? now : session.getEndedAt();
			Instant clippedStart = max(sessionStart, window.start());
			Instant clippedEnd = min(sessionEnd, window.end());
			if (!clippedEnd.isAfter(clippedStart)) {
				continue;
			}

			long sessionDuration = span(sessionStart, sessionEnd);
			long clippedDuration = span(clippedStart, clippedEnd);
			long sessionPaused = session.getPausedMs() == null ? 0L : Math.max(0L, session.getPausedMs());
			// The pause instants are not part of the work session row, so the paused time of the session
			// is distributed over the days it occupies in proportion to the time it spends in each day.
			double share = sessionDuration == 0 ? 0d : (double) clippedDuration / (double) sessionDuration;
			long clippedPaused = Math.min(clippedDuration, Math.round(sessionPaused * share));

			sessionIds.add(session.getId());
			for (Segment segment : splitAtMidnight(clippedStart, clippedEnd, zone)) {
				long duration = span(segment.start(), segment.end());
				long paused = sessionDuration == 0 ? 0L
						: Math.round((double) clippedPaused * duration / (double) clippedDuration);
				paused = Math.min(duration, paused);
				Procedure procedure = session.getProcedureId() == null ? null
						: procedures.get(session.getProcedureId());
				rows.add(new ReportAggregates.TimeRow(segment.start(), segment.end(), duration, paused,
						duration - paused, procedure == null ? null : procedure.getTitle(),
						procedure == null ? null : procedure.getSessionId(), segment.day().toString()));
				intervalsByDay.computeIfAbsent(segment.day(), day -> new ArrayList<>())
						.add(new Interval(segment.start(), segment.end()));
				pausedByDay.merge(segment.day(), paused, Long::sum);
			}
		}

		long totalMs = 0L;
		long pausedMs = 0L;
		List<ChartPoint> activePoints = new ArrayList<>();
		List<ChartPoint> pausedPoints = new ArrayList<>();
		int days = 0;
		for (Map.Entry<LocalDate, List<Interval>> entry : intervalsByDay.entrySet()) {
			long dayDuration = merge(entry.getValue());
			long dayPaused = Math.min(dayDuration, pausedByDay.getOrDefault(entry.getKey(), 0L));
			totalMs += dayDuration;
			pausedMs += dayPaused;
			days++;
			activePoints.add(new ChartPoint(entry.getKey().toString(), minutes(dayDuration - dayPaused)));
			pausedPoints.add(new ChartPoint(entry.getKey().toString(), minutes(dayPaused)));
		}

		rows.sort(Comparator.comparing(ReportAggregates.TimeRow::start));
		// Every time metric is computable for a period without a session: the value is zero, not unknown.
		List<String> notMeasurable = new ArrayList<>();

		ReportAggregates.Time aggregates = new ReportAggregates.Time(totalMs, pausedMs, totalMs - pausedMs,
				sessionIds.size(), days, List.copyOf(rows));
		return response(ReportFamily.TIME, window, locale, aggregates,
				List.of(new ChartSeries("Active minutes", List.copyOf(activePoints)),
						new ChartSeries("Paused minutes", List.copyOf(pausedPoints))),
				List.of(), 0, notMeasurable, rows.isEmpty());
	}

	// ------------------------------------------------------------ failures

	private ReportResponse failures(ReportPeriodWindow window, String locale) {
		List<FailureEvent> records = queryRepository.failures(window);
		List<Long> errorIds = records.stream()
				.filter(record -> record.getRecordType() == ReportRecordType.ERRORS)
				.map(FailureEvent::getId)
				.toList();
		Map<Long, ErrorDetail> details = new LinkedHashMap<>();
		for (ErrorDetail detail : queryRepository.errorDetails(errorIds)) {
			details.put(detail.getFailureEventId(), detail);
		}

		Map<String, Integer> byType = new LinkedHashMap<>();
		Map<String, Integer> bySeverity = new LinkedHashMap<>();
		List<ReportAggregates.FailureRow> rows = new ArrayList<>();
		int errorCount = 0;
		for (FailureEvent record : records) {
			String type = record.getFailureType().wireValue();
			String severity = record.getSeverity().wireValue();
			byType.merge(type, 1, Integer::sum);
			bySeverity.merge(severity, 1, Integer::sum);
			boolean error = record.getRecordType() == ReportRecordType.ERRORS;
			if (error) {
				errorCount++;
			}
			ErrorDetail detail = details.get(record.getId());
			rows.add(new ReportAggregates.FailureRow(record.getOccurredAt(), type, severity, record.getAgentId(),
					record.getSessionId(), error, detail == null ? null : detail.getCode(),
					detail == null ? null : detail.getMessage(),
					detail == null ? null : detail.getFieldOrResource()));
		}

		List<ChartPoint> points = new ArrayList<>();
		sortedByCountDescending(byType).forEach(entry -> points.add(
				new ChartPoint(entry.getKey(), entry.getValue().doubleValue())));

		// The failure counts are computable for a period without a record: the value is zero.
		List<String> notMeasurable = new ArrayList<>();

		ReportAggregates.Failures aggregates = new ReportAggregates.Failures(records.size(),
				records.size() - errorCount, errorCount, Map.copyOf(byType), Map.copyOf(bySeverity), List.copyOf(rows));
		return response(ReportFamily.FAILURES, window, locale, aggregates,
				List.of(new ChartSeries("Failures", List.copyOf(points))),
				donutOf(byType, 6), errorCount, notMeasurable, records.isEmpty());
	}

	// ------------------------------------------------------- hallucination

	private ReportResponse hallucination(ReportPeriodWindow window, String locale) {
		List<HallucinationEvent> events = queryRepository.hallucinations(window);
		List<ReportAggregates.HallucinationRow> rows = events.stream()
				.map(event -> new ReportAggregates.HallucinationRow(event.getOccurredAt(), event.getAgentId(),
						event.getSessionId(), event.getResponseRef()))
				.toList();

		// The denominator is the number of agent responses of the period. The product stores no response
		// counter, so the rate is reported as not available instead of being invented (RF-057).
		List<String> notMeasurable = new ArrayList<>();
		notMeasurable.add("hallucination_rate: the number of agent responses is " + NOT_AVAILABLE);

		Map<String, Integer> byDay = new LinkedHashMap<>();
		events.forEach(event -> byDay.merge(dayLabel(event.getOccurredAt(), window.zone()), 1, Integer::sum));
		List<ChartPoint> points = new ArrayList<>();
		byDay.forEach((day, count) -> points.add(new ChartPoint(day, count.doubleValue())));

		ReportAggregates.Hallucination aggregates = new ReportAggregates.Hallucination(events.size(), null, null,
				rows);
		return response(ReportFamily.HALLUCINATION, window, locale, aggregates,
				List.of(new ChartSeries("Confirmed hallucination events", List.copyOf(points))), List.of(), 0,
				notMeasurable, events.isEmpty());
	}

	// ------------------------------------------------------------- quality

	private ReportResponse quality(ReportPeriodWindow window, String locale) {
		List<QualityEvaluation> evaluations = queryRepository.qualityEvaluations(window);
		List<ReportAggregates.QualityRow> rows = evaluations.stream()
				.map(evaluation -> new ReportAggregates.QualityRow(evaluation.getOccurredAt(),
						evaluation.getSessionId(), evaluation.getScore(), evaluation.getAdherenceLevel(),
						evaluation.getEvidenceLevel(), evaluation.getProcessLevel(), evaluation.getArtifactLevel(),
						evaluation.getHonestyLevel(), evaluation.getAuthority().name(), evaluation.getRationale()))
				.toList();

		List<String> notMeasurable = new ArrayList<>();
		Double averageScore = null;
		Map<String, Double> averageLevels = new LinkedHashMap<>();
		Map<String, Integer> bands = new LinkedHashMap<>();
		bands.put("0-4", 0);
		bands.put("5-7", 0);
		bands.put("8-9", 0);
		bands.put("10", 0);

		if (evaluations.isEmpty()) {
			notMeasurable.add("quality_average: the period has no quality evaluation, " + NOT_AVAILABLE);
		}
		else {
			averageScore = round2(evaluations.stream().mapToInt(QualityEvaluation::getScore).average().orElse(0d));
			averageLevels.put("adherence", round2(levelAverage(evaluations, QualityEvaluation::getAdherenceLevel)));
			averageLevels.put("evidence", round2(levelAverage(evaluations, QualityEvaluation::getEvidenceLevel)));
			averageLevels.put("process", round2(levelAverage(evaluations, QualityEvaluation::getProcessLevel)));
			averageLevels.put("artifact", round2(levelAverage(evaluations, QualityEvaluation::getArtifactLevel)));
			averageLevels.put("honesty", round2(levelAverage(evaluations, QualityEvaluation::getHonestyLevel)));
			evaluations.forEach(evaluation -> bands.merge(bandOf(evaluation.getScore()), 1, Integer::sum));
		}

		Map<String, double[]> scoreByDay = new LinkedHashMap<>();
		evaluations.forEach(evaluation -> {
			String day = dayLabel(evaluation.getOccurredAt(), window.zone());
			double[] accumulator = scoreByDay.computeIfAbsent(day, key -> new double[2]);
			accumulator[0] += evaluation.getScore();
			accumulator[1] += 1;
		});
		List<ChartPoint> points = new ArrayList<>();
		scoreByDay.forEach((day, accumulator) -> points.add(
				new ChartPoint(day, round2(accumulator[1] == 0 ? 0d : accumulator[0] / accumulator[1]))));

		ReportAggregates.Quality aggregates = new ReportAggregates.Quality(averageScore, evaluations.size(),
				Map.copyOf(averageLevels), Map.copyOf(bands), rows);
		return response(ReportFamily.QUALITY, window, locale, aggregates,
				List.of(new ChartSeries("Average score", List.copyOf(points))), donutOf(bands, 4), 0, notMeasurable,
				evaluations.isEmpty());
	}

	// --------------------------------------------------------- graph usage

	private ReportResponse graphUsage(ReportPeriodWindow window, String locale) {
		List<GraphUsageEvent> events = queryRepository.graphUsages(window);
		long queries = events.stream().mapToLong(event -> event.getQueryCount() == null ? 0 : event.getQueryCount())
				.sum();
		long nodes = events.stream().mapToLong(event -> event.getDistinctNodes() == null ? 0 : event.getDistinctNodes())
				.sum();

		Set<String> sessions = new LinkedHashSet<>();
		Set<String> sessionsBeforeWork = new LinkedHashSet<>();
		for (GraphUsageEvent event : events) {
			String key = event.getSessionId() == null ? "event:" + event.getId() : event.getSessionId();
			sessions.add(key);
			if (event.isConsultedBeforeWork()) {
				sessionsBeforeWork.add(key);
			}
		}
		Double beforeWorkShare = sessions.isEmpty() ? null
				: round2(sessionsBeforeWork.size() * 100d / sessions.size());

		List<String> notMeasurable = new ArrayList<>();
		if (sessions.isEmpty()) {
			notMeasurable.add("graph_before_work_share: the period has no graph session, " + NOT_AVAILABLE);
		}

		Map<String, long[]> byDay = new LinkedHashMap<>();
		for (GraphUsageEvent event : events) {
			long[] accumulator = byDay.computeIfAbsent(dayLabel(event.getOccurredAt(), window.zone()),
					key -> new long[2]);
			accumulator[0] += event.getQueryCount() == null ? 0 : event.getQueryCount();
			accumulator[1] += event.getDistinctNodes() == null ? 0 : event.getDistinctNodes();
		}
		List<ChartPoint> queryPoints = new ArrayList<>();
		List<ChartPoint> nodePoints = new ArrayList<>();
		byDay.forEach((day, accumulator) -> {
			queryPoints.add(new ChartPoint(day, accumulator[0]));
			nodePoints.add(new ChartPoint(day, accumulator[1]));
		});

		Map<String, Integer> consulted = new LinkedHashMap<>();
		consulted.put("Consulted before work", sessionsBeforeWork.size());
		consulted.put("Not consulted before work", sessions.size() - sessionsBeforeWork.size());

		List<ReportAggregates.GraphUsageRow> rows = events.stream()
				.map(event -> new ReportAggregates.GraphUsageRow(event.getOccurredAt(), event.getAgentId(),
						event.getSessionId(), event.getQueryCount(), event.getDistinctNodes(),
						event.isConsultedBeforeWork()))
				.toList();

		ReportAggregates.GraphUsage aggregates = new ReportAggregates.GraphUsage(queries, nodes, sessions.size(),
				sessionsBeforeWork.size(), beforeWorkShare, rows);
		return response(ReportFamily.GRAPH_USAGE, window, locale, aggregates,
				List.of(new ChartSeries("Queries", List.copyOf(queryPoints)),
						new ChartSeries("Distinct nodes", List.copyOf(nodePoints))),
				donutOf(consulted, 2), 0, notMeasurable, events.isEmpty());
	}

	// ------------------------------------------------------ token economy

	private ReportResponse tokenEconomy(ReportPeriodWindow window, String locale) {
		List<TokenEconomyEvent> events = queryRepository.tokenEconomies(window);
		long inputTokens = events.stream().mapToLong(event -> nullSafe(event.getInputTokens())).sum();
		long outputTokens = events.stream().mapToLong(event -> nullSafe(event.getOutputTokens())).sum();

		int measurableRecords = 0;
		int notMeasurableRecords = 0;
		Long savingsTokens = null;
		List<String> reasons = new ArrayList<>();
		for (TokenEconomyEvent event : events) {
			if (event.isMeasurable()) {
				measurableRecords++;
				if (event.getSavingsTokens() != null) {
					savingsTokens = (savingsTokens == null ? 0L : savingsTokens) + event.getSavingsTokens();
				}
			}
			else {
				notMeasurableRecords++;
				String reason = event.getNotMeasurableReason();
				if (reason != null && !reason.isBlank() && !reasons.contains(reason)) {
					reasons.add(reason);
				}
			}
		}

		List<String> notMeasurable = new ArrayList<>();
		if (events.isEmpty()) {
			notMeasurable.add("token_savings: the period has no token economy record, " + NOT_AVAILABLE);
		}
		else if (measurableRecords == 0) {
			notMeasurable.add("token_savings: the saving is not measurable for the records of the period"
					+ (reasons.isEmpty() ? "" : " (" + String.join("; ", reasons) + ")"));
		}

		Map<String, long[]> byDay = new LinkedHashMap<>();
		for (TokenEconomyEvent event : events) {
			long[] accumulator = byDay.computeIfAbsent(dayLabel(event.getOccurredAt(), window.zone()),
					key -> new long[2]);
			accumulator[0] += nullSafe(event.getInputTokens()) + nullSafe(event.getOutputTokens());
			accumulator[1] += event.isMeasurable() && event.getSavingsTokens() != null ? event.getSavingsTokens() : 0L;
		}
		boolean anyMeasurable = measurableRecords > 0;
		List<ChartPoint> spentPoints = new ArrayList<>();
		List<ChartPoint> savingPoints = new ArrayList<>();
		byDay.forEach((day, accumulator) -> {
			spentPoints.add(new ChartPoint(day, accumulator[0]));
			if (anyMeasurable) {
				savingPoints.add(new ChartPoint(day, accumulator[1]));
			}
		});

		List<ChartSeries> chartSeries = anyMeasurable
				? List.of(new ChartSeries("Tokens spent", List.copyOf(spentPoints)),
						new ChartSeries("Savings", List.copyOf(savingPoints)))
				: List.of(new ChartSeries("Tokens spent", List.copyOf(spentPoints)));

		List<ReportAggregates.TokenEconomyRow> rows = events.stream()
				.map(event -> new ReportAggregates.TokenEconomyRow(event.getOccurredAt(), event.getModelId(),
						nullSafe(event.getInputTokens()), nullSafe(event.getOutputTokens()),
						event.getBaselineInputTokens(), event.getBaselineOutputTokens(), event.getSavingsTokens(),
						event.isMeasurable(), event.getNotMeasurableReason()))
				.toList();

		ReportAggregates.TokenEconomy aggregates = new ReportAggregates.TokenEconomy(inputTokens, outputTokens,
				savingsTokens, measurableRecords > 0, measurableRecords, notMeasurableRecords, List.copyOf(reasons),
				rows);
		return response(ReportFamily.TOKEN_ECONOMY, window, locale, aggregates, chartSeries, List.of(), 0,
				notMeasurable, events.isEmpty());
	}

	// ------------------------------------------------------------- sources

	private ReportResponse sources(ReportPeriodWindow window, String locale) {
		List<SourceEvent> events = queryRepository.sources(window);
		Map<String, Integer> byOrigin = new LinkedHashMap<>();
		Map<String, Integer> byAgent = new LinkedHashMap<>();
		Set<String> distinctSources = new LinkedHashSet<>();
		int cited = 0;
		int failures = 0;
		Map<String, Integer> byDay = new LinkedHashMap<>();

		for (SourceEvent event : events) {
			byOrigin.merge(event.getOrigin().wireValue(), 1, Integer::sum);
			byAgent.merge(event.getAgentId(), 1, Integer::sum);
			distinctSources.add(event.getSource());
			if (Boolean.TRUE.equals(event.getCited())) {
				cited++;
			}
			if (event.getResult() == EventResult.FAILURE) {
				failures++;
			}
			byDay.merge(dayLabel(event.getOccurredAt(), window.zone()), 1, Integer::sum);
		}

		List<ChartPoint> points = new ArrayList<>();
		byDay.forEach((day, count) -> points.add(new ChartPoint(day, count.doubleValue())));

		List<ReportAggregates.SourceRow> rows = events.stream()
				.map(event -> new ReportAggregates.SourceRow(event.getOccurredAt(), event.getAgentId(),
						event.getSessionId(), event.getOrigin().wireValue(), event.getSource(), event.getPurpose(),
						event.getResult().wireValue(), event.getCited(), event.isRedacted()))
				.toList();

		// The source counts are computable for a period without an access: the value is zero.
		List<String> notMeasurable = new ArrayList<>();

		ReportAggregates.Sources aggregates = new ReportAggregates.Sources(events.size(), distinctSources.size(),
				Map.copyOf(byOrigin), Map.copyOf(byAgent), cited, failures, rows);
		return response(ReportFamily.SOURCES, window, locale, aggregates,
				List.of(new ChartSeries("Accesses", List.copyOf(points))), donutOf(byOrigin, 6), 0, notMeasurable,
				events.isEmpty());
	}

	// ----------------------------------------------------------- downloads

	private ReportResponse downloads(ReportPeriodWindow window, String locale) {
		List<DownloadEvent> events = queryRepository.downloads(window);
		long volume = 0L;
		int successes = 0;
		int failures = 0;
		Map<String, Integer> byAgent = new LinkedHashMap<>();
		Map<String, Integer> bySource = new LinkedHashMap<>();
		Map<String, Integer> byType = new LinkedHashMap<>();
		Map<String, int[]> byDay = new LinkedHashMap<>();

		for (DownloadEvent event : events) {
			long size = nullSafe(event.getSizeBytes());
			volume += size;
			boolean success = event.getResult() == EventResult.SUCCESS;
			if (success) {
				successes++;
			}
			else {
				failures++;
			}
			byAgent.merge(event.getAgentId(), 1, Integer::sum);
			bySource.merge(event.getSource(), 1, Integer::sum);
			byType.merge(event.getItemType(), 1, Integer::sum);
			int[] accumulator = byDay.computeIfAbsent(dayLabel(event.getOccurredAt(), window.zone()),
					key -> new int[2]);
			if (success) {
				accumulator[0]++;
			}
			else {
				accumulator[1]++;
			}
		}

		List<ChartPoint> successPoints = new ArrayList<>();
		List<ChartPoint> failurePoints = new ArrayList<>();
		byDay.forEach((day, accumulator) -> {
			successPoints.add(new ChartPoint(day, accumulator[0]));
			failurePoints.add(new ChartPoint(day, accumulator[1]));
		});

		List<String> notMeasurable = new ArrayList<>();
		Double successRate = events.isEmpty() ? null : round2(successes * 100d / events.size());
		if (events.isEmpty()) {
			notMeasurable.add("download_success_rate: the period has no download, " + NOT_AVAILABLE);
		}
		// The throughput needs the transfer duration, which the contract does not record.
		notMeasurable.add("download_throughput: the transfer duration is not recorded, " + NOT_AVAILABLE);

		List<ReportAggregates.TransferRow> rows = events.stream()
				.map(event -> new ReportAggregates.TransferRow(event.getOccurredAt(), event.getAgentId(),
						event.getSessionId(), event.getSource(), null, event.getItem(), event.getItemType(),
						nullSafe(event.getSizeBytes()), event.getResult().wireValue(), null, event.isRedacted()))
				.toList();

		ReportAggregates.Downloads aggregates = new ReportAggregates.Downloads(events.size(), volume, successes,
				failures, successRate, null, Map.copyOf(byAgent), Map.copyOf(bySource), Map.copyOf(byType), rows);
		return response(ReportFamily.DOWNLOADS, window, locale, aggregates,
				List.of(new ChartSeries("Successes", List.copyOf(successPoints)),
						new ChartSeries("Failures", List.copyOf(failurePoints))),
				List.of(), 0, notMeasurable, events.isEmpty());
	}

	// ------------------------------------------------------------- uploads

	private ReportResponse uploads(ReportPeriodWindow window, String locale) {
		List<UploadEvent> events = queryRepository.uploads(window);
		long volume = 0L;
		int successes = 0;
		int failures = 0;
		int sent = 0;
		Map<String, Integer> byDestination = new LinkedHashMap<>();
		Map<String, int[]> byDay = new LinkedHashMap<>();

		for (UploadEvent event : events) {
			volume += nullSafe(event.getSizeBytes());
			boolean success = event.getResult() == EventResult.SUCCESS;
			if (success) {
				successes++;
			}
			else {
				failures++;
			}
			if (event.isSent()) {
				sent++;
			}
			byDestination.merge(event.getDestination(), 1, Integer::sum);
			int[] accumulator = byDay.computeIfAbsent(dayLabel(event.getOccurredAt(), window.zone()),
					key -> new int[2]);
			if (success) {
				accumulator[0]++;
			}
			else {
				accumulator[1]++;
			}
		}

		List<ChartPoint> successPoints = new ArrayList<>();
		List<ChartPoint> failurePoints = new ArrayList<>();
		byDay.forEach((day, accumulator) -> {
			successPoints.add(new ChartPoint(day, accumulator[0]));
			failurePoints.add(new ChartPoint(day, accumulator[1]));
		});

		List<String> notMeasurable = new ArrayList<>();
		// A period without an upload is an explicit zero in the count, not a missing value (RF-063); the
		// rate is the only metric that cannot be computed without a record.
		Double successRate = events.isEmpty() ? null : round2(successes * 100d / events.size());
		if (events.isEmpty()) {
			notMeasurable.add("upload_success_rate: the period has no upload, " + NOT_AVAILABLE);
		}

		List<ReportAggregates.TransferRow> rows = events.stream()
				.map(event -> new ReportAggregates.TransferRow(event.getOccurredAt(), event.getAgentId(),
						event.getSessionId(), null, event.getDestination(), event.getItem(), event.getItemType(),
						nullSafe(event.getSizeBytes()), event.getResult().wireValue(), event.isSent(),
						event.isRedacted()))
				.toList();

		ReportAggregates.Uploads aggregates = new ReportAggregates.Uploads(events.size(), volume, successes,
				failures, successRate, sent, Map.copyOf(byDestination), rows);
		return response(ReportFamily.UPLOADS, window, locale, aggregates,
				List.of(new ChartSeries("Successes", List.copyOf(successPoints)),
						new ChartSeries("Failures", List.copyOf(failurePoints))),
				List.of(), 0, notMeasurable, events.isEmpty());
	}

	// ------------------------------------------------------------- helpers

	private ReportResponse response(ReportFamily family, ReportPeriodWindow window, String locale,
			ReportAggregates aggregates, List<ChartSeries> chartSeries, List<DonutSlice> donut, int errorCount,
			List<String> notMeasurable, boolean empty) {
		ZonedDateTime start = window.start().atZone(window.zone());
		ZonedDateTime end = window.end().atZone(window.zone());
		return new ReportResponse(family, window.period().wireName(), start.toOffsetDateTime().toString(),
				end.toOffsetDateTime().toString(), window.zone().getId(), locale, aggregates, chartSeries, donut,
				errorCount, List.copyOf(notMeasurable), empty);
	}

	private List<Segment> splitAtMidnight(Instant start, Instant end, ZoneId zone) {
		List<Segment> segments = new ArrayList<>();
		Instant cursor = start;
		while (cursor.isBefore(end)) {
			LocalDate day = cursor.atZone(zone).toLocalDate();
			Instant nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant();
			Instant segmentEnd = nextMidnight.isBefore(end) ? nextMidnight : end;
			segments.add(new Segment(day, cursor, segmentEnd));
			cursor = segmentEnd;
		}
		if (segments.isEmpty()) {
			segments.add(new Segment(start.atZone(zone).toLocalDate(), start, end));
		}
		return segments;
	}

	private long merge(List<Interval> intervals) {
		List<Interval> ordered = intervals.stream().sorted(Comparator.comparing(Interval::start)).toList();
		long total = 0L;
		Instant currentStart = null;
		Instant currentEnd = null;
		for (Interval interval : ordered) {
			if (currentStart == null) {
				currentStart = interval.start();
				currentEnd = interval.end();
				continue;
			}
			if (!interval.start().isAfter(currentEnd)) {
				currentEnd = max(currentEnd, interval.end());
			}
			else {
				total += span(currentStart, currentEnd);
				currentStart = interval.start();
				currentEnd = interval.end();
			}
		}
		if (currentStart != null) {
			total += span(currentStart, currentEnd);
		}
		return total;
	}

	private double levelAverage(List<QualityEvaluation> evaluations,
			java.util.function.Function<QualityEvaluation, Integer> extractor) {
		return evaluations.stream().mapToInt(evaluation -> {
			Integer level = extractor.apply(evaluation);
			return level == null ? 0 : level;
		}).average().orElse(0d);
	}

	private String bandOf(int score) {
		if (score <= 4) {
			return "0-4";
		}
		if (score <= 7) {
			return "5-7";
		}
		return score <= 9 ? "8-9" : "10";
	}

	private List<Map.Entry<String, Integer>> sortedByCountDescending(Map<String, Integer> counts) {
		return counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
						.thenComparing(Map.Entry.comparingByKey()))
				.toList();
	}

	private List<DonutSlice> donutOf(Map<String, Integer> counts, int maxSlices) {
		List<Map.Entry<String, Integer>> ordered = sortedByCountDescending(counts);
		long total = ordered.stream().mapToLong(Map.Entry::getValue).sum();
		if (total == 0) {
			return List.of();
		}
		List<DonutSlice> slices = new ArrayList<>();
		int visible = Math.min(maxSlices, ordered.size());
		boolean groupTail = ordered.size() > visible;
		int slicesToShow = groupTail ? visible - 1 : visible;
		long tail = 0L;
		for (int index = 0; index < ordered.size(); index++) {
			Map.Entry<String, Integer> entry = ordered.get(index);
			if (index < slicesToShow) {
				slices.add(new DonutSlice(entry.getKey(), entry.getValue(), share(entry.getValue(), total)));
			}
			else {
				tail += entry.getValue();
			}
		}
		if (groupTail && tail > 0) {
			slices.add(new DonutSlice("Other", tail, share(tail, total)));
		}
		return List.copyOf(slices);
	}

	private double share(long value, long total) {
		return round2(value * 100d / total);
	}

	private String dayLabel(Instant instant, ZoneId zone) {
		return instant.atZone(zone).toLocalDate().toString();
	}

	private double minutes(long milliseconds) {
		return round2(milliseconds / 60_000d);
	}

	private double round2(double value) {
		return Math.round(value * 100d) / 100d;
	}

	private long nullSafe(Long value) {
		return value == null ? 0L : value;
	}

	private long span(Instant start, Instant end) {
		return Math.max(0L, Duration.between(start, end).toMillis());
	}

	private Instant max(Instant left, Instant right) {
		return left.isAfter(right) ? left : right;
	}

	private Instant min(Instant left, Instant right) {
		return left.isBefore(right) ? left : right;
	}

	private record Segment(LocalDate day, Instant start, Instant end) {
	}

	private record Interval(Instant start, Instant end) {
	}
}
