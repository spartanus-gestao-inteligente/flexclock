package br.com.flexclock.dto;

/**
 * One point of a chart series.
 *
 * <p>The dashboard plots the point as it arrives: the label is already localised into the report
 * language by the backend and the value is already aggregated, so the Flutter layer never recomputes
 * a metric.
 *
 * @param label the axis label of the point
 * @param value the aggregated value of the point
 */
public record ChartPoint(String label, double value) {
}
