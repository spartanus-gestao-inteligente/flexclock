package br.com.flexclock.dto;

import java.util.List;

import br.com.flexclock.reports.ReportFamily;

/**
 * Aggregated report of one family for one resolved period.
 *
 * <p>The response carries the aggregates, the chart series, the donut slices where a donut applies,
 * the explicit error count and the list of metrics that could not be computed. {@code empty} is true
 * only when the period has no record at all, so the dashboard can name the period and the family in
 * its empty state instead of showing a zero that looks like data.
 *
 * <p>This is a read only surface: reading a report never writes, updates or deletes a record.
 *
 * <p>Decisions 0019, 0031, 0032, 0054; requirements RF-051, RF-065, RF-078.
 *
 * @param family the report family
 * @param period the resolved period token
 * @param periodStart the inclusive start of the period, ISO 8601 in the configured timezone
 * @param periodEnd the exclusive end of the period, ISO 8601 in the configured timezone
 * @param timezone the timezone the limits were computed in
 * @param locale the locale used for the labels
 * @param aggregates the aggregates of the family
 * @param chartSeries the chart series, at most two
 * @param donut the donut slices, empty for the families without a donut
 * @param errorCount the number of records whose type is errors
 * @param notMeasurable the metrics of the family that could not be computed, with the reason
 * @param empty true when the period has no record of the family
 */
public record ReportResponse(ReportFamily family, String period, String periodStart, String periodEnd,
		String timezone, String locale, ReportAggregates aggregates, List<ChartSeries> chartSeries,
		List<DonutSlice> donut, int errorCount, List<String> notMeasurable, boolean empty) {
}
