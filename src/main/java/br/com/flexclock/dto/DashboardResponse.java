package br.com.flexclock.dto;

import java.util.List;

/**
 * Consolidated report of the nine families for one resolved period, consumed by the Monitor
 * dashboard.
 *
 * <p>The dashboard asks for this payload when it opens and when the operator changes the period: it
 * receives the nine family reports at once, so the selector, the charts, the donuts and the headline
 * cards all describe the same window. Each family report keeps its own error count and its own list of
 * not measurable metrics, so the consolidated view never hides a gap behind a zero.
 *
 * <p>Decision 0030, 0031, 0032; requirements RF-067, RF-068, RF-069.
 *
 * @param period the resolved period token
 * @param periodStart the inclusive start of the period, ISO 8601 in the configured timezone
 * @param periodEnd the exclusive end of the period, ISO 8601 in the configured timezone
 * @param timezone the timezone the limits were computed in
 * @param locale the locale used for the labels
 * @param headline the consolidated headline numbers
 * @param reports the nine family reports, in the order of the report selector
 * @param notMeasurable the metrics that could not be computed in any family, with the reason
 */
public record DashboardResponse(String period, String periodStart, String periodEnd, String timezone,
		String locale, Headline headline, List<ReportResponse> reports, List<String> notMeasurable) {

	/**
	 * Consolidated headline numbers of the dashboard.
	 *
	 * @param activeMs the active coding time of the period
	 * @param pausedMs the paused time of the period
	 * @param sessionCount the number of work sessions of the period
	 * @param failures the number of failure records of the period
	 * @param errorCount the number of records whose type is errors
	 * @param averageScore the average quality score, null when the period has no evaluation
	 * @param graphQueries the number of graph queries of the period
	 * @param tokensSaved the measurable token saving, null when nothing is measurable
	 * @param sources the number of source accesses of the period
	 * @param downloads the number of downloads of the period
	 * @param uploads the number of uploads of the period, zero when the period has none
	 */
	public record Headline(long activeMs, long pausedMs, int sessionCount, int failures, int errorCount,
			Double averageScore, long graphQueries, Long tokensSaved, int sources, int downloads, int uploads) {
	}
}
