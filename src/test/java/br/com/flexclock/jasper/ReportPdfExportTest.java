package br.com.flexclock.jasper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import br.com.flexclock.dto.ChartPoint;
import br.com.flexclock.dto.ChartSeries;
import br.com.flexclock.dto.DonutSlice;
import br.com.flexclock.dto.ReportAggregates;
import br.com.flexclock.dto.ReportResponse;
import br.com.flexclock.reports.ReportFamily;
import br.com.flexclock.reports.ReportPeriod;
import br.com.flexclock.reports.ReportPeriodWindow;

/**
 * Proves that each of the nine templates fills with real shaped data and exports a PDF, with and without
 * records in the period.
 *
 * <p>Nothing is written to disk: the export goes to memory, so no generated PDF can reach the
 * repository (decision 0052). An empty period is proven separately, because the templates must show the
 * period and the family instead of an empty page.
 *
 * <p>Decisions 0031, 0052; requirements RF-074, RF-078.
 */
class ReportPdfExportTest {

	private static final Instant NOW = Instant.parse("2026-11-10T18:00:00Z");

	private static final ReportPeriodWindow WINDOW = new ReportPeriodWindow(ReportPeriod.DAY,
			Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-11T00:00:00Z"), ZoneId.of("UTC"), null);

	private final ReportPdfService service = new ReportPdfService(new JasperReportCatalog(),
			new ReportFillDataFactory(), "");

	@ParameterizedTest
	@EnumSource(ReportFamily.class)
	void everyFamilyExportsAPdfWithRecords(ReportFamily family) {
		byte[] pdf = service.pdf(sample(family, false), WINDOW, NOW);

		assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
		assertThat(pdf.length).isGreaterThan(3000);
	}

	@ParameterizedTest
	@EnumSource(ReportFamily.class)
	void anEmptyPeriodStillRendersTheReportPage(ReportFamily family) {
		byte[] pdf = service.pdf(sample(family, true), WINDOW, NOW);

		assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
		assertThat(pdf.length).isGreaterThan(2000);
	}

	@Test
	void theSuggestedFileNameCarriesTheFamilyAndTheWindow() {
		String fileName = service.fileName(sample(ReportFamily.TIME, false), WINDOW);

		assertThat(fileName).isEqualTo("flex-clock-time-2026-11-10-2026-11-11.pdf");
	}

	@Test
	void theLogoIsOnlyUsedWhenTheConfiguredFileExists() {
		ReportPdfService withoutLogo = new ReportPdfService(new JasperReportCatalog(), new ReportFillDataFactory(),
				"/does/not/exist/spartanus-logo.png");

		assertThat(withoutLogo.effectiveLogoPath()).isNull();
		assertThat(service.effectiveLogoPath()).isNull();
	}

	private ReportResponse sample(ReportFamily family, boolean empty) {
		ReportAggregates aggregates = empty ? emptyAggregates(family) : populatedAggregates(family);
		return new ReportResponse(family, "custom", "2026-11-10T00:00Z", "2026-11-11T00:00Z", "UTC", "en",
				aggregates, chartSeries(empty), donut(family), 1, List.of("sample metric: not measurable"), empty);
	}

	private ReportAggregates populatedAggregates(ReportFamily family) {
		Instant occurredAt = Instant.parse("2026-11-10T09:15:00Z");
		return switch (family) {
			case TIME -> new ReportAggregates.Time(7_200_000L, 900_000L, 6_300_000L, 2, 1,
					List.of(new ReportAggregates.TimeRow(occurredAt, occurredAt.plusSeconds(3600), 3_600_000L,
							450_000L, 3_150_000L, "Refactor the report surface", "ses_01", "2026-11-10")));
			case FAILURES -> new ReportAggregates.Failures(2, 1, 1, Map.of("tool_error", 1),
					Map.of("high", 1, "critical", 1), List.of(
							new ReportAggregates.FailureRow(occurredAt, "tool_error", "high", "agent-1", "ses_01",
									false, null, null, null),
							new ReportAggregates.FailureRow(occurredAt, "db_write_failure", "critical", "agent-1",
									"ses_01", true, "DB_WRITE_FAILURE", "The database write failed.", "failure_event")));
			case HALLUCINATION -> new ReportAggregates.Hallucination(1, null, null,
					List.of(new ReportAggregates.HallucinationRow(occurredAt, "agent-1", "ses_01", "response-42")));
			case QUALITY -> new ReportAggregates.Quality(9.5, 1,
					Map.of("adherence", 4.0, "evidence", 3.0, "process", 4.0, "artifact", 4.0, "honesty", 3.0),
					Map.of("0-4", 0, "5-7", 0, "8-9", 0, "10", 1),
					List.of(new ReportAggregates.QualityRow(occurredAt, "ses_01", 10, 4, 4, 4, 4, 4, "SELF",
							"All criteria at level 4.")));
			case GRAPH_USAGE -> new ReportAggregates.GraphUsage(12, 40, 2, 1, 50.0,
					List.of(new ReportAggregates.GraphUsageRow(occurredAt, "agent-1", "ses_01", 12, 40, true)));
			case TOKEN_ECONOMY -> new ReportAggregates.TokenEconomy(1_200L, 800L, null, false, 0, 1,
					List.of("the baseline is absent"),
					List.of(new ReportAggregates.TokenEconomyRow(occurredAt, "deepseek-v4.1-flash", 1_200L, 800L,
							null, null, null, false, "the baseline is absent")));
			case SOURCES -> new ReportAggregates.Sources(3, 2, Map.of("domain", 2, "graph", 1),
					Map.of("agent-1", 3), 1, 1, List.of(
							new ReportAggregates.SourceRow(occurredAt, "agent-1", "ses_01", "domain",
									"https://docs.spring.io/", "confirm the contract", "success", true, false),
							new ReportAggregates.SourceRow(occurredAt, "agent-1", "ses_01", "graph",
									"graph node reports", "find the report decision", "failure", null, false)));
			case DOWNLOADS -> new ReportAggregates.Downloads(2, 4_194_304L, 1, 1, 50.0, null,
					Map.of("agent-1", 2), Map.of("maven central", 2), Map.of("jar", 2), List.of(
							new ReportAggregates.TransferRow(occurredAt, "agent-1", "ses_01", "maven central", null,
									"jasperreports-7.0.6.jar", "jar", 2_097_152L, "success", null, false)));
			case UPLOADS -> new ReportAggregates.Uploads(1, 1_024L, 1, 0, 100.0, 1, Map.of("s3://bucket", 1),
					List.of(new ReportAggregates.TransferRow(occurredAt, "agent-1", "ses_01", null, "s3://bucket",
							"evidence.json", "json", 1_024L, "success", true, false)));
		};
	}

	private ReportAggregates emptyAggregates(ReportFamily family) {
		return switch (family) {
			case TIME -> new ReportAggregates.Time(0, 0, 0, 0, 0, List.of());
			case FAILURES -> new ReportAggregates.Failures(0, 0, 0, Map.of(), Map.of(), List.of());
			case HALLUCINATION -> new ReportAggregates.Hallucination(0, null, null, List.of());
			case QUALITY -> new ReportAggregates.Quality(null, 0, Map.of(), Map.of(), List.of());
			case GRAPH_USAGE -> new ReportAggregates.GraphUsage(0, 0, 0, 0, null, List.of());
			case TOKEN_ECONOMY -> new ReportAggregates.TokenEconomy(0, 0, null, false, 0, 0, List.of(), List.of());
			case SOURCES -> new ReportAggregates.Sources(0, 0, Map.of(), Map.of(), 0, 0, List.of());
			case DOWNLOADS -> new ReportAggregates.Downloads(0, 0, 0, 0, null, null, Map.of(), Map.of(), Map.of(),
					List.of());
			case UPLOADS -> new ReportAggregates.Uploads(0, 0, 0, 0, null, 0, Map.of(), List.of());
		};
	}

	private List<ChartSeries> chartSeries(boolean empty) {
		if (empty) {
			return List.of(new ChartSeries("Active minutes", List.of()));
		}
		return List.of(new ChartSeries("Active minutes", List.of(new ChartPoint("2026-11-10", 105.0))),
				new ChartSeries("Paused minutes", List.of(new ChartPoint("2026-11-10", 15.0))));
	}

	private List<DonutSlice> donut(ReportFamily family) {
		return switch (family) {
			case FAILURES -> List.of(new DonutSlice("tool_error", 1, 50.0), new DonutSlice("Other", 1, 50.0));
			case QUALITY -> List.of(new DonutSlice("10", 1, 100.0));
			case GRAPH_USAGE -> List.of(new DonutSlice("Consulted before work", 1, 100.0));
			default -> List.of();
		};
	}
}
