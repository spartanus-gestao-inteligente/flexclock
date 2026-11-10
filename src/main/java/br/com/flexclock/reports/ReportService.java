package br.com.flexclock.reports;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.dto.DashboardResponse;
import br.com.flexclock.dto.ReportAggregates;
import br.com.flexclock.dto.ReportRecordRequest;
import br.com.flexclock.dto.ReportRecordResponse;
import br.com.flexclock.dto.ReportResponse;
import br.com.flexclock.jasper.ReportPdfService;

/**
 * Facade of the report surface: ingestion, aggregation, the consolidated dashboard and the PDF export.
 *
 * <p>The service resolves the period once and hands the same window to every family, so the nine
 * reports of a dashboard request always describe the same interval. Reading a report performs no write:
 * only the ingestion path appends, and it appends through the repository that exposes insertion alone.
 *
 * <p>Decisions 0019, 0030, 0052; requirements RF-045, RF-054, RF-065, RF-074.
 */
@Service
public class ReportService {

	private final ReportIngestionService ingestionService;
	private final ReportAggregationService aggregationService;
	private final ReportPeriodResolver periodResolver;
	private final ReportPdfService pdfService;
	private final String defaultTimezone;
	private final String defaultLocale;

	public ReportService(ReportIngestionService ingestionService, ReportAggregationService aggregationService,
			ReportPeriodResolver periodResolver, ReportPdfService pdfService,
			@Value("${flexclock.region.timezone:#{T(java.time.ZoneId).systemDefault().getId()}}") String defaultTimezone,
			@Value("${flexclock.locale.default:en}") String defaultLocale) {
		this.ingestionService = ingestionService;
		this.aggregationService = aggregationService;
		this.periodResolver = periodResolver;
		this.pdfService = pdfService;
		this.defaultTimezone = defaultTimezone;
		this.defaultLocale = defaultLocale;
	}

	/**
	 * Appends one record of a family.
	 *
	 * @param familyToken the family token of the path
	 * @param request the record to append
	 * @param idempotencyKey the key presented by the client
	 * @return the result of the append
	 */
	@Transactional
	public ReportRecordResponse append(String familyToken, ReportRecordRequest request, String idempotencyKey) {
		return ingestionService.append(parseFamily(familyToken), request, idempotencyKey);
	}

	/**
	 * Aggregates one family for one period.
	 *
	 * @param familyToken the family token of the path
	 * @param periodToken the period token, day when absent
	 * @param startToken the ISO date of the start of a custom period
	 * @param endToken the ISO date of the exclusive end of a custom period
	 * @param timezone the timezone, the configured default when absent
	 * @param localeToken the locale, the configured default when absent
	 * @param procedureId the procedure of a session period
	 * @return the aggregated report
	 */
	public ReportResponse report(String familyToken, String periodToken, String startToken, String endToken,
			String timezone, String localeToken, Long procedureId) {
		ReportFamily family = parseFamily(familyToken);
		ReportPeriodWindow window = resolveWindow(periodToken, startToken, endToken, timezone, localeToken,
				procedureId);
		return aggregationService.report(family, window, effectiveLocale(localeToken), Instant.now());
	}

	/**
	 * Builds the consolidated dashboard of the nine families.
	 *
	 * @param periodToken the period token, day when absent
	 * @param startToken the ISO date of the start of a custom period
	 * @param endToken the ISO date of the exclusive end of a custom period
	 * @param timezone the timezone, the configured default when absent
	 * @param localeToken the locale, the configured default when absent
	 * @param procedureId the procedure of a session period
	 * @return the consolidated dashboard
	 */
	public DashboardResponse dashboard(String periodToken, String startToken, String endToken, String timezone,
			String localeToken, Long procedureId) {
		ReportPeriodWindow window = resolveWindow(periodToken, startToken, endToken, timezone, localeToken,
				procedureId);
		String locale = effectiveLocale(localeToken);
		Instant now = Instant.now();

		List<ReportResponse> reports = new ArrayList<>();
		List<String> notMeasurable = new ArrayList<>();
		for (ReportFamily family : ReportFamily.values()) {
			ReportResponse report = aggregationService.report(family, window, locale, now);
			reports.add(report);
			for (String reason : report.notMeasurable()) {
				if (!notMeasurable.contains(reason)) {
					notMeasurable.add(reason);
				}
			}
		}
		return new DashboardResponse(window.period().wireName(), reports.getFirst().periodStart(),
				reports.getFirst().periodEnd(), window.zone().getId(), locale, headline(reports),
				List.copyOf(reports), List.copyOf(notMeasurable));
	}

	/**
	 * Exports the PDF of one family for one period.
	 *
	 * @param familyToken the family token of the path
	 * @param periodToken the period token, day when absent
	 * @param startToken the ISO date of the start of a custom period
	 * @param endToken the ISO date of the exclusive end of a custom period
	 * @param timezone the timezone, the configured default when absent
	 * @param localeToken the locale, the configured default when absent
	 * @param procedureId the procedure of a session period
	 * @return the PDF and its suggested file name
	 */
	public PdfExport pdf(String familyToken, String periodToken, String startToken, String endToken,
			String timezone, String localeToken, Long procedureId) {
		ReportFamily family = parseFamily(familyToken);
		ReportPeriodWindow window = resolveWindow(periodToken, startToken, endToken, timezone, localeToken,
				procedureId);
		ReportResponse report = aggregationService.report(family, window, effectiveLocale(localeToken),
				Instant.now());
		return new PdfExport(pdfService.pdf(report, window, Instant.now()), pdfService.fileName(report, window));
	}

	/**
	 * Lists the family tokens accepted by the report surface, in the order of the report selector.
	 *
	 * @return the family tokens
	 */
	public List<String> families() {
		List<String> families = new ArrayList<>();
		for (ReportFamily family : ReportFamily.values()) {
			families.add(family.wireName());
		}
		return List.copyOf(families);
	}

	private DashboardResponse.Headline headline(List<ReportResponse> reports) {
		Map<ReportFamily, ReportResponse> byFamily = new EnumMap<>(ReportFamily.class);
		reports.forEach(report -> byFamily.put(report.family(), report));

		var time = (ReportAggregates.Time) byFamily.get(ReportFamily.TIME).aggregates();
		var failures = (ReportAggregates.Failures) byFamily.get(ReportFamily.FAILURES).aggregates();
		var quality = (ReportAggregates.Quality) byFamily.get(ReportFamily.QUALITY).aggregates();
		var graph = (ReportAggregates.GraphUsage) byFamily.get(ReportFamily.GRAPH_USAGE).aggregates();
		var tokens = (ReportAggregates.TokenEconomy) byFamily.get(ReportFamily.TOKEN_ECONOMY).aggregates();
		var sources = (ReportAggregates.Sources) byFamily.get(ReportFamily.SOURCES).aggregates();
		var downloads = (ReportAggregates.Downloads) byFamily.get(ReportFamily.DOWNLOADS).aggregates();
		var uploads = (ReportAggregates.Uploads) byFamily.get(ReportFamily.UPLOADS).aggregates();
		return new DashboardResponse.Headline(time.activeMs(), time.pausedMs(), time.sessionCount(),
				failures.total(), failures.errorCount(), quality.averageScore(), graph.queries(),
				tokens.savingsTokens(), sources.accesses(), downloads.count(), uploads.count());
	}

	private ReportPeriodWindow resolveWindow(String periodToken, String startToken, String endToken,
			String timezone, String localeToken, Long procedureId) {
		String zone = timezone == null || timezone.isBlank() ? defaultTimezone : timezone;
		return periodResolver.resolve(periodToken, startToken, endToken, zone, effectiveLocaleObject(localeToken),
				procedureId, Instant.now());
	}

	private ReportFamily parseFamily(String familyToken) {
		try {
			return ReportFamily.fromWireName(familyToken);
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(ErrorCode.INVALID_ENUM_VALUE, "family",
					"The family must be one of " + String.join(", ", families()) + ".");
		}
	}

	private String effectiveLocale(String localeToken) {
		return localeToken == null || localeToken.isBlank() ? defaultLocale : localeToken;
	}

	private Locale effectiveLocaleObject(String localeToken) {
		return Locale.forLanguageTag(effectiveLocale(localeToken));
	}

	/**
	 * Exported PDF and the file name suggested to the client.
	 *
	 * @param content the PDF bytes
	 * @param fileName the suggested file name
	 */
	public record PdfExport(byte[] content, String fileName) {
	}
}
