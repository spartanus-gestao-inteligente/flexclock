package br.com.flexclock.reports;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.Locale;

import org.springframework.stereotype.Component;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;

/**
 * Turns a period token, a locale and an optional explicit interval into one resolved window.
 *
 * <p>Every report family uses this resolver, so the session, day, week, month and custom windows are
 * identical across the nine reports. Limits are computed in the configured timezone, the week starts
 * on the first day of the week of the selected locale, and a custom interval is start inclusive and
 * end exclusive.
 *
 * <p>Decision 0019; requirements RF-054, RF-068.
 */
@Component
public class ReportPeriodResolver {

	/**
	 * Resolves the window of a report read.
	 *
	 * @param periodToken the period token of the query string, day when absent
	 * @param startToken the ISO 8601 local date of the start of a custom interval, null otherwise
	 * @param endToken the ISO 8601 local date of the exclusive end of a custom interval, null otherwise
	 * @param timezone the configured zone identifier
	 * @param locale the selected locale, which fixes the first day of the week
	 * @param procedureId the procedure of a session period, null otherwise
	 * @param now the reference instant, injected so the window is testable
	 * @return the resolved window
	 */
	public ReportPeriodWindow resolve(String periodToken, String startToken, String endToken, String timezone,
			Locale locale, Long procedureId, Instant now) {
		ReportPeriod period = parsePeriod(periodToken);
		ZoneId zone = parseZone(timezone);
		ZonedDateTime reference = now.atZone(zone);

		return switch (period) {
			case SESSION -> sessionWindow(reference, zone, procedureId);
			case DAY -> dayWindow(reference, zone);
			case WEEK -> weekWindow(reference, zone, locale);
			case MONTH -> monthWindow(reference, zone);
			case CUSTOM -> customWindow(startToken, endToken, zone);
		};
	}

	private ReportPeriodWindow sessionWindow(ZonedDateTime reference, ZoneId zone, Long procedureId) {
		if (procedureId == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "procedureId",
					"The session period requires the procedure identifier.");
		}
		// A session window is open ended: the report reads the records that belong to the procedure,
		// from the first record to now. The explicit bounds keep the interval half open and bounded.
		Instant start = reference.minusYears(100).toInstant();
		Instant end = reference.plusSeconds(1).toInstant();
		return new ReportPeriodWindow(ReportPeriod.SESSION, start, end, zone, procedureId);
	}

	private ReportPeriodWindow dayWindow(ZonedDateTime reference, ZoneId zone) {
		LocalDate today = reference.toLocalDate();
		return window(ReportPeriod.DAY, today, today.plusDays(1), zone);
	}

	private ReportPeriodWindow weekWindow(ZonedDateTime reference, ZoneId zone, Locale locale) {
		WeekFields weekFields = WeekFields.of(locale == null ? Locale.ENGLISH : locale);
		LocalDate start = reference.toLocalDate().with(TemporalAdjusters.previousOrSame(weekFields.getFirstDayOfWeek()));
		return window(ReportPeriod.WEEK, start, start.plusWeeks(1), zone);
	}

	private ReportPeriodWindow monthWindow(ZonedDateTime reference, ZoneId zone) {
		LocalDate start = reference.toLocalDate().with(TemporalAdjusters.firstDayOfMonth());
		return window(ReportPeriod.MONTH, start, start.plusMonths(1), zone);
	}

	private ReportPeriodWindow customWindow(String startToken, String endToken, ZoneId zone) {
		if (startToken == null || startToken.isBlank() || endToken == null || endToken.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "start",
					"A custom period requires both the start and the end date.");
		}
		LocalDate start = parseDate(startToken, "start");
		LocalDate end = parseDate(endToken, "end");
		if (!start.isBefore(end)) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, "end",
					"The custom period end must be later than its start.");
		}
		return window(ReportPeriod.CUSTOM, start, end, zone);
	}

	private ReportPeriodWindow window(ReportPeriod period, LocalDate start, LocalDate end, ZoneId zone) {
		return new ReportPeriodWindow(period, start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant(),
				zone, null);
	}

	private ReportPeriod parsePeriod(String periodToken) {
		try {
			return ReportPeriod.fromWireName(periodToken);
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(ErrorCode.INVALID_ENUM_VALUE, "period",
					"The period must be one of session, day, week, month or custom.");
		}
	}

	private ZoneId parseZone(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			return ZoneId.systemDefault();
		}
		try {
			return ZoneId.of(timezone);
		}
		catch (DateTimeParseException exception) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "timezone",
					"The timezone must be a zone identifier such as America/Sao_Paulo.");
		}
	}

	private LocalDate parseDate(String value, String field) {
		try {
			return LocalDate.parse(value);
		}
		catch (DateTimeParseException exception) {
			throw new ApiException(ErrorCode.MALFORMED_DATE, field, "The date must use the ISO 8601 format.");
		}
	}
}
