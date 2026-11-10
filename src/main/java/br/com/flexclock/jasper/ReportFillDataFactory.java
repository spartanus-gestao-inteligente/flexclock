package br.com.flexclock.jasper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import br.com.flexclock.dto.ChartPoint;
import br.com.flexclock.dto.ChartSeries;
import br.com.flexclock.dto.DonutSlice;
import br.com.flexclock.dto.ReportAggregates;
import br.com.flexclock.dto.ReportResponse;
import br.com.flexclock.reports.ReportFamily;
import br.com.flexclock.reports.ReportPeriodWindow;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;

/**
 * Builds the parameters and the rows a JRXML template is filled with.
 *
 * <p>The factory is the single place that knows the shape of the nine templates: every value is
 * formatted here, in the report language and in the configured timezone, so the templates only lay out
 * strings and the dashboard and the PDF never disagree about a number. The chart is fed by a map data
 * source of the series of the family.
 *
 * <p>Report locale, periods and metric definitions follow decision 0019; the visual model is the one
 * approved with the product (decision 0052).
 */
@Component
public class ReportFillDataFactory {

	private static final DateTimeFormatter INSTANT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
			.withLocale(Locale.ENGLISH);

	/**
	 * Builds the fill data of one family.
	 *
	 * @param response the aggregated report of the family
	 * @param window the resolved window, used for the timezone of the formatted instants
	 * @param generatedAt the instant the report is generated
	 * @param logoPath the absolute path of the product mark, null when it is not configured
	 * @return the parameters and the rows of the template
	 */
	public ReportFillData build(ReportResponse response, ReportPeriodWindow window, Instant generatedAt,
			String logoPath) {
		ReportFamily family = response.family();
		List<Map<String, ?>> rows = switch (family) {
			case TIME -> timeRows(response, window.zone());
			case FAILURES -> failureRows(response, window.zone());
			case HALLUCINATION -> hallucinationRows(response, window.zone());
			case QUALITY -> qualityRows(response, window.zone());
			case GRAPH_USAGE -> graphUsageRows(response, window.zone());
			case TOKEN_ECONOMY -> tokenEconomyRows(response, window.zone());
			case SOURCES -> sourceRows(response, window.zone());
			case DOWNLOADS, UPLOADS -> transferRows(response, window.zone());
		};

		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("logoPath", logoPath);
		parameters.put("reportName", titleOf(family));
		parameters.put("periodStart", response.periodStart());
		parameters.put("periodEnd", response.periodEnd());
		parameters.put("generatedAt", INSTANT_FORMAT.format(generatedAt.atZone(window.zone())));
		parameters.put("footerNote", "Flex Clock | " + titleOf(family) + " report | " + response.locale());
		parameters.put("emptyMessage", "No " + family.wireName().replace('_', ' ') + " record in the selected period.");
		parameters.put("chartTitle", chartTitleOf(family));
		parameters.put("chartNote", chartNoteOf(response));
		parameters.put("chartDataSource", toDataSource(chartRows(response)));
		putKpis(parameters, response);
		putBreakdown(parameters, response);

		// A template parameter may legitimately be null (no logo, no breakdown slice), so the map is not
		// passed through Map.copyOf, which refuses null values. The map stays mutable on purpose: the
		// JasperReports fill writes the data source reference into it.
		return new ReportFillData(new LinkedHashMap<>(parameters), List.copyOf(rows));
	}

	private void putBreakdown(Map<String, Object> parameters, ReportResponse response) {
		List<DonutSlice> slices = response.donut();
		parameters.put("breakdownTitle", "Breakdown of the period");
		for (int index = 0; index < 6; index++) {
			String line = index < slices.size()
					? slices.get(index).label() + ": " + slices.get(index).value() + " ("
							+ percentage(slices.get(index).percentage()) + ")"
					: null;
			parameters.put("breakdown" + (index + 1), line);
		}
	}

	private void putKpis(Map<String, Object> parameters, ReportResponse response) {
		List<String[]> kpis = kpisOf(response);
		for (int index = 0; index < 3; index++) {
			String[] kpi = kpis.get(index);
			parameters.put("kpi" + (index + 1) + "Label", kpi[0]);
			parameters.put("kpi" + (index + 1) + "Value", kpi[1]);
		}
	}

	private List<String[]> kpisOf(ReportResponse response) {
		return switch (response.family()) {
			case TIME -> {
				ReportAggregates.Time time = (ReportAggregates.Time) response.aggregates();
				yield List.of(new String[] { "ACTIVE TIME", duration(time.activeMs()) },
						new String[] { "PAUSED TIME", duration(time.pausedMs()) },
						new String[] { "SESSIONS", Integer.toString(time.sessionCount()) });
			}
			case FAILURES -> {
				ReportAggregates.Failures failures = (ReportAggregates.Failures) response.aggregates();
				yield List.of(new String[] { "FAILURES", Integer.toString(failures.total()) },
						new String[] { "ERROR RECORDS", Integer.toString(failures.errorCount()) },
						new String[] { "FAILURE TYPES", Integer.toString(failures.byType().size()) });
			}
			case HALLUCINATION -> {
				ReportAggregates.Hallucination hallucination = (ReportAggregates.Hallucination) response.aggregates();
				yield List.of(new String[] { "CONFIRMED", Integer.toString(hallucination.confirmed()) },
						new String[] { "RESPONSES", hallucination.responses() == null ? "not available"
								: Long.toString(hallucination.responses()) },
						new String[] { "RATE", hallucination.rate() == null ? "not available"
								: percentage(hallucination.rate()) });
			}
			case QUALITY -> {
				ReportAggregates.Quality quality = (ReportAggregates.Quality) response.aggregates();
				yield List.of(new String[] { "AVERAGE SCORE",
						quality.averageScore() == null ? "not available" : number(quality.averageScore()) },
						new String[] { "EVALUATIONS", Integer.toString(quality.evaluations()) },
						new String[] { "SCORE 10", Integer.toString(quality.bands().getOrDefault("10", 0)) });
			}
			case GRAPH_USAGE -> {
				ReportAggregates.GraphUsage usage = (ReportAggregates.GraphUsage) response.aggregates();
				yield List.of(new String[] { "QUERIES", Long.toString(usage.queries()) },
						new String[] { "DISTINCT NODES", Long.toString(usage.distinctNodes()) },
						new String[] { "BEFORE WORK", usage.beforeWorkShare() == null ? "not available"
								: percentage(usage.beforeWorkShare()) });
			}
			case TOKEN_ECONOMY -> {
				ReportAggregates.TokenEconomy tokens = (ReportAggregates.TokenEconomy) response.aggregates();
				yield List.of(new String[] { "INPUT TOKENS", Long.toString(tokens.inputTokens()) },
						new String[] { "OUTPUT TOKENS", Long.toString(tokens.outputTokens()) },
						new String[] { "SAVINGS", tokens.savingsTokens() == null ? "not measurable"
								: Long.toString(tokens.savingsTokens()) });
			}
			case SOURCES -> {
				ReportAggregates.Sources sources = (ReportAggregates.Sources) response.aggregates();
				yield List.of(new String[] { "ACCESSES", Integer.toString(sources.accesses()) },
						new String[] { "DISTINCT SOURCES", Integer.toString(sources.distinctSources()) },
						new String[] { "CITED", Integer.toString(sources.cited()) });
			}
			case DOWNLOADS -> {
				ReportAggregates.Downloads downloads = (ReportAggregates.Downloads) response.aggregates();
				yield List.of(new String[] { "DOWNLOADS", Integer.toString(downloads.count()) },
						new String[] { "VOLUME", bytes(downloads.volumeBytes()) },
						new String[] { "SUCCESS RATE", downloads.successRate() == null ? "not available"
								: percentage(downloads.successRate()) });
			}
			case UPLOADS -> {
				ReportAggregates.Uploads uploads = (ReportAggregates.Uploads) response.aggregates();
				yield List.of(new String[] { "UPLOADS", Integer.toString(uploads.count()) },
						new String[] { "VOLUME", bytes(uploads.volumeBytes()) },
						new String[] { "SUCCESS RATE", uploads.successRate() == null ? "not available"
								: percentage(uploads.successRate()) });
			}
		};
	}

	private List<Map<String, ?>> timeRows(ReportResponse response, ZoneId zone) {
		ReportAggregates.Time time = (ReportAggregates.Time) response.aggregates();
		List<Map<String, ?>> rows = new ArrayList<>();
		for (ReportAggregates.TimeRow row : time.rows()) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("startText", INSTANT_FORMAT.format(row.start().atZone(zone)));
			values.put("endText", INSTANT_FORMAT.format(row.end().atZone(zone)));
			values.put("durationText", duration(row.durationMs()));
			values.put("pausedText", duration(row.pausedMs()));
			values.put("activeText", duration(row.activeMs()));
			values.put("procedureTitle", orDash(row.procedureTitle()));
			values.put("sessionId", orDash(row.sessionId()));
			rows.add(Map.copyOf(values));
		}
		return rows;
	}

	private List<Map<String, ?>> failureRows(ReportResponse response, ZoneId zone) {
		ReportAggregates.Failures failures = (ReportAggregates.Failures) response.aggregates();
		List<Map<String, ?>> rows = new ArrayList<>();
		for (ReportAggregates.FailureRow row : failures.rows()) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("occurredText", INSTANT_FORMAT.format(row.occurredAt().atZone(zone)));
			values.put("typeLabel", row.failureType());
			values.put("severityLabel", row.severity());
			values.put("agentId", row.agentId());
			values.put("codeText", orDash(row.code()));
			values.put("messageText", row.error() ? orDash(row.message()) : "recorded failure");
			rows.add(Map.copyOf(values));
		}
		return rows;
	}

	private List<Map<String, ?>> hallucinationRows(ReportResponse response, ZoneId zone) {
		ReportAggregates.Hallucination hallucination = (ReportAggregates.Hallucination) response.aggregates();
		List<Map<String, ?>> rows = new ArrayList<>();
		for (ReportAggregates.HallucinationRow row : hallucination.rows()) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("occurredText", INSTANT_FORMAT.format(row.occurredAt().atZone(zone)));
			values.put("agentId", row.agentId());
			values.put("sessionId", orDash(row.sessionId()));
			values.put("responseRef", row.responseRef());
			rows.add(Map.copyOf(values));
		}
		return rows;
	}

	private List<Map<String, ?>> qualityRows(ReportResponse response, ZoneId zone) {
		ReportAggregates.Quality quality = (ReportAggregates.Quality) response.aggregates();
		List<Map<String, ?>> rows = new ArrayList<>();
		for (ReportAggregates.QualityRow row : quality.rows()) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("occurredText", INSTANT_FORMAT.format(row.occurredAt().atZone(zone)));
			values.put("sessionId", orDash(row.sessionId()));
			values.put("scoreText", Integer.toString(row.score()));
			values.put("adherenceText", Integer.toString(row.adherenceLevel()));
			values.put("evidenceText", Integer.toString(row.evidenceLevel()));
			values.put("processText", Integer.toString(row.processLevel()));
			values.put("artifactText", Integer.toString(row.artifactLevel()));
			values.put("honestyText", Integer.toString(row.honestyLevel()));
			values.put("authority", row.authority());
			rows.add(Map.copyOf(values));
		}
		return rows;
	}

	private List<Map<String, ?>> graphUsageRows(ReportResponse response, ZoneId zone) {
		ReportAggregates.GraphUsage usage = (ReportAggregates.GraphUsage) response.aggregates();
		List<Map<String, ?>> rows = new ArrayList<>();
		for (ReportAggregates.GraphUsageRow row : usage.rows()) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("occurredText", INSTANT_FORMAT.format(row.occurredAt().atZone(zone)));
			values.put("agentId", row.agentId());
			values.put("sessionId", orDash(row.sessionId()));
			values.put("queryText", Integer.toString(row.queryCount()));
			values.put("nodeText", Integer.toString(row.distinctNodes()));
			values.put("beforeWorkText", row.consultedBeforeWork() ? "yes" : "no");
			rows.add(Map.copyOf(values));
		}
		return rows;
	}

	private List<Map<String, ?>> tokenEconomyRows(ReportResponse response, ZoneId zone) {
		ReportAggregates.TokenEconomy tokens = (ReportAggregates.TokenEconomy) response.aggregates();
		List<Map<String, ?>> rows = new ArrayList<>();
		for (ReportAggregates.TokenEconomyRow row : tokens.rows()) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("occurredText", INSTANT_FORMAT.format(row.occurredAt().atZone(zone)));
			values.put("modelId", row.modelId());
			values.put("inputText", Long.toString(row.inputTokens()));
			values.put("outputText", Long.toString(row.outputTokens()));
			values.put("baselineText", row.baselineInputTokens() == null ? "-"
					: row.baselineInputTokens() + " / " + row.baselineOutputTokens());
			values.put("savingsText", row.savingsTokens() == null ? "empty" : Long.toString(row.savingsTokens()));
			values.put("stateText", row.measurable() ? "measurable" : "not measurable");
			rows.add(Map.copyOf(values));
		}
		return rows;
	}

	private List<Map<String, ?>> sourceRows(ReportResponse response, ZoneId zone) {
		ReportAggregates.Sources sources = (ReportAggregates.Sources) response.aggregates();
		List<Map<String, ?>> rows = new ArrayList<>();
		for (ReportAggregates.SourceRow row : sources.rows()) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("occurredText", INSTANT_FORMAT.format(row.occurredAt().atZone(zone)));
			values.put("agentId", row.agentId());
			values.put("origin", row.origin());
			values.put("source", row.source());
			values.put("purpose", row.purpose());
			values.put("result", row.result());
			values.put("citedText", row.cited() == null ? "-" : row.cited() ? "cited" : "not cited");
			rows.add(Map.copyOf(values));
		}
		return rows;
	}

	private List<Map<String, ?>> transferRows(ReportResponse response, ZoneId zone) {
		List<ReportAggregates.TransferRow> transferRows = switch (response.aggregates()) {
			case ReportAggregates.Downloads downloads -> downloads.rows();
			case ReportAggregates.Uploads uploads -> uploads.rows();
			default -> List.of();
		};
		boolean upload = response.family() == ReportFamily.UPLOADS;
		List<Map<String, ?>> rows = new ArrayList<>();
		for (ReportAggregates.TransferRow row : transferRows) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("occurredText", INSTANT_FORMAT.format(row.occurredAt().atZone(zone)));
			values.put("agentId", row.agentId());
			values.put("sourceOrDestination", upload ? orDash(row.destination()) : orDash(row.source()));
			values.put("item", row.item());
			values.put("itemType", row.itemType());
			values.put("sizeText", bytes(row.sizeBytes()));
			values.put("result", row.result());
			values.put("sentText", row.sent() == null ? "-" : row.sent() ? "sent" : "not sent");
			rows.add(Map.copyOf(values));
		}
		return rows;
	}

	private List<Map<String, ?>> chartRows(ReportResponse response) {
		List<ChartSeries> series = response.chartSeries();
		ChartSeries first = series.isEmpty() ? new ChartSeries("Value", List.of()) : series.get(0);
		ChartSeries second = series.size() > 1 ? series.get(1) : null;
		Map<String, Double> secondByLabel = new LinkedHashMap<>();
		if (second != null) {
			second.points().forEach(point -> secondByLabel.put(point.label(), point.value()));
		}
		List<Map<String, ?>> rows = new ArrayList<>();
		for (ChartPoint point : first.points()) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("label", point.label());
			values.put("value1", point.value());
			values.put("value2", second == null ? 0d : secondByLabel.getOrDefault(point.label(), 0d));
			rows.add(Map.copyOf(values));
		}
		return rows;
	}

	private JRDataSource toDataSource(List<Map<String, ?>> rows) {
		return new JRMapCollectionDataSource(rows);
	}

	private String chartNoteOf(ReportResponse response) {
		if (response.notMeasurable().isEmpty()) {
			return "";
		}
		return String.join("; ", response.notMeasurable());
	}

	private String titleOf(ReportFamily family) {
		return switch (family) {
			case TIME -> "Time";
			case FAILURES -> "Failures and errors";
			case HALLUCINATION -> "Hallucination";
			case QUALITY -> "Quality";
			case GRAPH_USAGE -> "Graph usage";
			case TOKEN_ECONOMY -> "Token economy";
			case SOURCES -> "Sources";
			case DOWNLOADS -> "Downloads";
			case UPLOADS -> "Uploads";
		};
	}

	private String chartTitleOf(ReportFamily family) {
		return switch (family) {
			case TIME -> "Active and paused time per day";
			case FAILURES -> "Failures by type";
			case HALLUCINATION -> "Confirmed hallucination events per day";
			case QUALITY -> "Average score per day";
			case GRAPH_USAGE -> "Queries and distinct nodes per day";
			case TOKEN_ECONOMY -> "Tokens spent and saving per day";
			case SOURCES -> "Source accesses per day";
			case DOWNLOADS -> "Downloads per day";
			case UPLOADS -> "Uploads per day";
		};
	}

	private String orDash(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	private String duration(long milliseconds) {
		Duration duration = Duration.ofMillis(Math.max(0L, milliseconds));
		return "%d:%02d:%02d".formatted(duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
	}

	private String bytes(long value) {
		if (value < 1024) {
			return value + " B";
		}
		double kilobytes = value / 1024d;
		if (kilobytes < 1024) {
			return number(kilobytes) + " KB";
		}
		double megabytes = kilobytes / 1024d;
		if (megabytes < 1024) {
			return number(megabytes) + " MB";
		}
		return number(megabytes / 1024d) + " GB";
	}

	private String percentage(double value) {
		return number(value) + "%";
	}

	private String number(double value) {
		return Math.abs(value - Math.rint(value)) < 0.005 ? Long.toString(Math.round(value))
				: String.format(Locale.ENGLISH, "%.2f", value);
	}

	/**
	 * Fill data of one report template.
	 *
	 * @param parameters the JasperReports parameters, including the chart and breakdown data sources
	 * @param rows the rows of the main data source, already formatted
	 */
	public record ReportFillData(Map<String, Object> parameters, List<Map<String, ?>> rows) {
	}
}
