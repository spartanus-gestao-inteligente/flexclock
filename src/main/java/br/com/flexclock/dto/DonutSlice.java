package br.com.flexclock.dto;

/**
 * One slice of a donut view.
 *
 * <p>The donut exists only where the message is a part of a whole: failures by type, users by score
 * band and sessions that consulted the graph before the work against the ones that did not. The
 * slice always carries a label, a value and the share of the total, so the view never relies on colour
 * alone. At most six slices are returned, the tail grouped as Other.
 *
 * <p>Decision 0032; requirement RF-064.
 *
 * @param label the label of the slice
 * @param value the value of the slice
 * @param percentage the share of the total, from 0 to 100
 */
public record DonutSlice(String label, long value, double percentage) {
}
