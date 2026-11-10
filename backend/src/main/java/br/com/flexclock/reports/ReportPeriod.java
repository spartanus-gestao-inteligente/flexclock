package br.com.flexclock.reports;

/**
 * Reporting period of a report read, with the wire token accepted by the dashboard and the MCP
 * sidecar.
 *
 * <p>The day is the default. Every family resolves the period through
 * {@link ReportPeriodResolver}, so all reports compute their window identically in the configured
 * timezone and with the first day of the week of the selected locale.
 *
 * <p>Decision 0019; architecture contract, section 6.1; requirements RF-054, RF-068.
 */
public enum ReportPeriod {

	/** One anchored session, identified by its procedure. */
	SESSION("session"),

	/** One local day, the default. */
	DAY("day"),

	/** One local week, starting on the first day of the week of the locale. */
	WEEK("week"),

	/** One local month. */
	MONTH("month"),

	/** An explicit interval: start inclusive, end exclusive. */
	CUSTOM("custom");

	private final String wireName;

	ReportPeriod(String wireName) {
		this.wireName = wireName;
	}

	/**
	 * Returns the token used in the query string and in the response.
	 *
	 * @return the wire token, never null
	 */
	public String wireName() {
		return wireName;
	}

	/**
	 * Resolves the period of a wire token, defaulting to {@link #DAY} when absent.
	 *
	 * @param wireName the token of the query string, may be null
	 * @return the matching period, day when the token is absent
	 * @throws IllegalArgumentException when the token is not one of the five periods
	 */
	public static ReportPeriod fromWireName(String wireName) {
		if (wireName == null || wireName.isBlank()) {
			return DAY;
		}
		String normalized = wireName.trim().toLowerCase(java.util.Locale.ROOT);
		for (ReportPeriod period : values()) {
			if (period.wireName.equals(normalized)) {
				return period;
			}
		}
		throw new IllegalArgumentException("Unknown report period: " + wireName);
	}
}
