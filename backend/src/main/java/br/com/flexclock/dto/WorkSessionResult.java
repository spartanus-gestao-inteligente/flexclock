package br.com.flexclock.dto;

import java.time.Instant;
import java.util.List;

import br.com.flexclock.enums.TimeMode;

/**
 * Complete state of a work session, returned by the stop operation and by the session read.
 *
 * <p>Every duration is derived from the recorded instants, never from a counter, and the segments are
 * the midnight split the time report consumes: one segment per local date, with total, paused and
 * active time that add up exactly. The record is additive to the frozen contract; the stop operation
 * of section 6.1 only promises status 200, so the payload is free to carry the whole result.
 *
 * @param workSessionId the identifier of the session
 * @param mode the time mode
 * @param targetSeconds the countdown target, null for a stopwatch
 * @param procedureId the procedure that owns the session, may be null
 * @param startedAt the start instant, in UTC
 * @param endedAt the stop instant, null while the session is open
 * @param totalMs the wall clock duration
 * @param pausedMs the paused time discounted from the coding time
 * @param activeMs the coding time, total minus paused
 * @param timezone the timezone used by the midnight split
 * @param running true while the session has no end instant
 * @param timerCompleted true when a project countdown reached its target
 * @param segments the session split by local date
 */
public record WorkSessionResult(Long workSessionId, TimeMode mode, Integer targetSeconds, Long procedureId,
		Instant startedAt, Instant endedAt, long totalMs, long pausedMs, long activeMs, String timezone,
		boolean running, boolean timerCompleted, List<TimeSegment> segments) {
}
