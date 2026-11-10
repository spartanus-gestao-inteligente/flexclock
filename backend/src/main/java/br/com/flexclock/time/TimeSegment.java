package br.com.flexclock.time;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One local day of a work session.
 *
 * <p>A session that crosses midnight is reported as one segment per local day it touches, so the time
 * report never overlaps two rows and never counts the same millisecond twice. The split uses the
 * timezone stored with the session, not the timezone of the machine reading the report (decision 0020;
 * requirements RF-023, RF-055).
 *
 * <p>The record is the contract the reports workstream consumes: total, paused and active always
 * satisfy {@code totalMs = pausedMs + activeMs}.
 *
 * @param date the local date of the segment, in the session timezone
 * @param start the first instant of the segment, in UTC
 * @param end the exclusive end of the segment, in UTC
 * @param totalMs the wall clock duration of the segment
 * @param pausedMs the paused time inside the segment
 * @param activeMs the coding time inside the segment, total minus paused
 */
public record TimeSegment(LocalDate date, Instant start, Instant end, long totalMs, long pausedMs, long activeMs) {
}
