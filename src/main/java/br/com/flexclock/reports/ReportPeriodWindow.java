package br.com.flexclock.reports;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Resolved reporting window: the half open interval {@code [start, end)} in UTC plus the timezone the
 * limits were computed in.
 *
 * <p>A report query always receives a resolved window, never raw dates, so every family applies the
 * same limits. The start is inclusive and the end is exclusive, which makes two adjacent periods add
 * up without counting a record twice.
 *
 * @param period the requested period
 * @param start inclusive lower bound in UTC
 * @param end exclusive upper bound in UTC
 * @param zone the timezone used to compute the local day, week or month limits
 * @param procedureId the procedure that scopes a session period, null for the other periods
 */
public record ReportPeriodWindow(ReportPeriod period, Instant start, Instant end, ZoneId zone, Long procedureId) {

	/**
	 * Reports whether an instant belongs to the window.
	 *
	 * @param instant the instant to test
	 * @return true when the instant is inside the half open interval
	 */
	public boolean contains(Instant instant) {
		return instant != null && !instant.isBefore(start) && instant.isBefore(end);
	}
}
