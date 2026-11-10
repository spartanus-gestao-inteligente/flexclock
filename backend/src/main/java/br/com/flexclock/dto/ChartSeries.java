package br.com.flexclock.dto;

import java.util.List;

/**
 * One named series of a chart.
 *
 * <p>Reports that compare two values (active against paused time, tokens spent against savings,
 * count against volume, success against failure) return two series, never one series with a mixed
 * meaning.
 *
 * @param name the legend name of the series
 * @param points the points of the series, ordered by label
 */
public record ChartSeries(String name, List<ChartPoint> points) {
}
