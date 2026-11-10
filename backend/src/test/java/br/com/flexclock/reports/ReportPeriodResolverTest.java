package br.com.flexclock.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;

/**
 * Proves that every report resolves the same period identically: the limits are computed in the
 * configured timezone, the week starts on the first day of the week of the locale, and a custom period
 * is start inclusive and end exclusive.
 *
 * <p>Decision 0019; requirements RF-054, RF-068.
 */
class ReportPeriodResolverTest {

	private static final Instant NOW = Instant.parse("2026-11-10T15:30:00Z");

	private final ReportPeriodResolver resolver = new ReportPeriodResolver();

	@Test
	void theDefaultPeriodIsTheCurrentLocalDay() {
		ReportPeriodWindow window = resolver.resolve(null, null, null, "America/Sao_Paulo", Locale.US, null, NOW);

		assertThat(window.period()).isEqualTo(ReportPeriod.DAY);
		assertThat(window.start()).isEqualTo(Instant.parse("2026-11-10T03:00:00Z"));
		assertThat(window.end()).isEqualTo(Instant.parse("2026-11-11T03:00:00Z"));
	}

	@Test
	void aWeekStartsOnTheFirstDayOfTheWeekOfTheLocale() {
		ReportPeriodWindow american = resolver.resolve("week", null, null, "UTC", Locale.US, null, NOW);
		ReportPeriodWindow british = resolver.resolve("week", null, null, "UTC", Locale.UK, null, NOW);

		assertThat(american.start()).isEqualTo(Instant.parse("2026-11-08T00:00:00Z"));
		assertThat(british.start()).isEqualTo(Instant.parse("2026-11-09T00:00:00Z"));
	}

	@Test
	void aMonthStartsOnTheFirstDay() {
		ReportPeriodWindow window = resolver.resolve("month", null, null, "UTC", Locale.US, null, NOW);

		assertThat(window.start()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
		assertThat(window.end()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
	}

	@Test
	void aCustomPeriodIsStartInclusiveAndEndExclusive() {
		ReportPeriodWindow window = resolver.resolve("custom", "2026-11-01", "2026-11-03", "UTC", Locale.US, null, NOW);

		assertThat(window.start()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
		assertThat(window.end()).isEqualTo(Instant.parse("2026-11-03T00:00:00Z"));
		assertThat(window.contains(Instant.parse("2026-11-01T00:00:00Z"))).isTrue();
		assertThat(window.contains(Instant.parse("2026-11-02T23:59:59Z"))).isTrue();
		assertThat(window.contains(Instant.parse("2026-11-03T00:00:00Z"))).isFalse();
	}

	@Test
	void aCustomPeriodWithTheStartAfterTheEndIsRefused() {
		assertThatThrownBy(() -> resolver.resolve("custom", "2026-11-05", "2026-11-03", "UTC", Locale.US, null,
				NOW))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.VALUE_OUT_OF_RANGE));
	}

	@Test
	void aCustomPeriodWithoutBothDatesIsRefused() {
		assertThatThrownBy(() -> resolver.resolve("custom", "2026-11-05", null, "UTC", Locale.US, null, NOW))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void aSessionPeriodRequiresTheProcedure() {
		assertThatThrownBy(() -> resolver.resolve("session", null, null, "UTC", Locale.US, null, NOW))
				.isInstanceOf(ApiException.class)
				.hasMessage("One or more fields are invalid.");

		ReportPeriodWindow window = resolver.resolve("session", null, null, "UTC", Locale.US, 7L, NOW);
		assertThat(window.period()).isEqualTo(ReportPeriod.SESSION);
		assertThat(window.procedureId()).isEqualTo(7L);
		assertThat(window.contains(Instant.parse("2026-11-10T15:00:00Z"))).isTrue();
	}

	@Test
	void anUnknownPeriodIsRefusedWithTheEnumerationCode() {
		assertThatThrownBy(() -> resolver.resolve("quarter", null, null, "UTC", Locale.US, null, NOW))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.INVALID_ENUM_VALUE));
	}

	@Test
	void aMalformedDateIsRefusedWithTheDateCode() {
		assertThatThrownBy(() -> resolver.resolve("custom", "10/11/2026", "2026-11-12", "UTC", Locale.US, null,
				NOW))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.MALFORMED_DATE));
	}

	@Test
	void theWindowUsesTheConfiguredTimezone() {
		ReportPeriodWindow window = resolver.resolve("day", null, null, "Asia/Tokyo", Locale.JAPAN, null, NOW);

		assertThat(window.zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
		assertThat(window.start().atZone(window.zone()).toLocalDate()).isEqualTo(LocalDate.parse("2026-11-11"));
	}
}
