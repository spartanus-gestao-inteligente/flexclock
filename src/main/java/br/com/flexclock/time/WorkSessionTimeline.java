package br.com.flexclock.time;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.flexclock.enums.PauseStatus;
import br.com.flexclock.enums.TimeMode;
import br.com.flexclock.model.AgentPause;
import br.com.flexclock.model.WorkSession;

/**
 * Derives every duration of a work session from the instants that were recorded.
 *
 * <p>Nothing is accumulated in a memory counter, so a suspended machine, a resumed process, a clock
 * adjustment or a long pause cannot corrupt the total, the paused time or the active time (requirement
 * RF-024). Every value is clamped: a clock that moves backwards never produces a negative duration and
 * the remaining time of a timer never becomes negative or larger than its target.
 *
 * <p>The midnight split follows decision 0020 and requirement RF-023: a session is cut at every local
 * midnight of the timezone stored with the session, each piece belongs to exactly one local date, and
 * the paused intervals are cut the same way, so a pause that crosses midnight is discounted on both
 * days without double counting.
 *
 * <p>The class has no dependency on the database or on the framework: it is a pure calculation over the
 * persisted rows, which is what makes the semantics testable with a controlled clock.
 */
@Component
public class WorkSessionTimeline {

	/** Safety bound of the midnight split, so a corrupt row can never loop forever. */
	private static final int MAX_SEGMENT_DAYS = 366;

	/**
	 * Wall clock duration of a session, from its start to the given instant.
	 *
	 * @param session the work session
	 * @param now the instant of the read, or the stop instant
	 * @return the duration in milliseconds, never negative
	 */
	public long totalMillis(WorkSession session, Instant now) {
		Instant end = effectiveEnd(session, now);
		return Math.max(0L, Duration.between(session.getStartedAt(), end).toMillis());
	}

	/**
	 * Coding time of a session: wall clock duration minus the paused time of its procedure.
	 *
	 * @param session the work session
	 * @param pauses the pauses of the session procedure, in any order
	 * @param now the instant of the read, or the stop instant
	 * @return the active time in milliseconds, never negative and never larger than the total
	 */
	public long activeMillis(WorkSession session, List<AgentPause> pauses, Instant now) {
		Instant end = effectiveEnd(session, now);
		long total = Math.max(0L, Duration.between(session.getStartedAt(), end).toMillis());
		long paused = Math.min(total, pausedMillis(pauses, session.getStartedAt(), end, now));
		return total - paused;
	}

	/**
	 * Remaining time of a project countdown.
	 *
	 * @param session the work session
	 * @param activeMs the active time already calculated
	 * @return the remaining milliseconds for a project countdown, null for a stopwatch
	 */
	public Long remainingMillis(WorkSession session, long activeMs) {
		if (session.getMode() != TimeMode.PROJECT_STOPWATCH || session.getTargetSeconds() == null) {
			return null;
		}
		long target = session.getTargetSeconds() * 1000L;
		return Math.max(0L, target - Math.max(0L, activeMs));
	}

	/**
	 * Reports whether a project countdown already reached its target.
	 *
	 * <p>Reaching the target never stops the session: only the agent stops the time (decision 0020).
	 *
	 * @param session the work session
	 * @param activeMs the active time already calculated
	 * @return true when the mode is a project countdown and the active time reached the target
	 */
	public boolean timerCompleted(WorkSession session, long activeMs) {
		if (session.getMode() != TimeMode.PROJECT_STOPWATCH || session.getTargetSeconds() == null) {
			return false;
		}
		return activeMs >= session.getTargetSeconds() * 1000L;
	}

	/**
	 * Sums the paused time that overlaps a window, merging overlapping pauses so the same millisecond is
	 * never discounted twice.
	 *
	 * @param pauses the pauses of the procedure
	 * @param windowStart the inclusive start of the window
	 * @param windowEnd the exclusive end of the window
	 * @param now the instant used as the end of an unresolved pause
	 * @return the paused milliseconds inside the window, never negative
	 */
	public long pausedMillis(List<AgentPause> pauses, Instant windowStart, Instant windowEnd, Instant now) {
		if (pauses == null || pauses.isEmpty() || !windowEnd.isAfter(windowStart)) {
			return 0L;
		}
		long paused = 0L;
		for (PauseInterval interval : intervals(pauses, now)) {
			Instant start = interval.start().isAfter(windowStart) ? interval.start() : windowStart;
			Instant end = interval.end().isBefore(windowEnd) ? interval.end() : windowEnd;
			if (end.isAfter(start)) {
				paused += Duration.between(start, end).toMillis();
			}
		}
		return paused;
	}

	/**
	 * Splits a session into one segment per local date, using the timezone stored with the session.
	 *
	 * @param session the work session
	 * @param pauses the pauses of the session procedure, in any order
	 * @param effectiveEnd the end of the session, or the instant of the read while it is open
	 * @return the ordered segments, one per local date the session touches
	 */
	public List<TimeSegment> segments(WorkSession session, List<AgentPause> pauses, Instant effectiveEnd) {
		Instant start = session.getStartedAt();
		Instant end = effectiveEnd == null ? start : effectiveEnd;
		if (end.isBefore(start)) {
			end = start;
		}
		ZoneId zone = zoneOf(session);
		List<PauseInterval> intervals = intervals(pauses, end);
		List<TimeSegment> segments = new ArrayList<>();

		LocalDate day = start.atZone(zone).toLocalDate();
		LocalDate lastDay = end.atZone(zone).toLocalDate();
		for (int index = 0; index < MAX_SEGMENT_DAYS && !day.isAfter(lastDay); index++) {
			Instant dayStart = day.atStartOfDay(zone).toInstant();
			Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
			Instant segmentStart = start.isAfter(dayStart) ? start : dayStart;
			Instant segmentEnd = end.isBefore(dayEnd) ? end : dayEnd;
			if (segmentEnd.isAfter(segmentStart)) {
				long total = Duration.between(segmentStart, segmentEnd).toMillis();
				long paused = Math.min(total, overlapMillis(intervals, segmentStart, segmentEnd));
				segments.add(new TimeSegment(day, segmentStart, segmentEnd, total, paused, total - paused));
			}
			day = day.plusDays(1);
		}
		return segments;
	}

	private long overlapMillis(List<PauseInterval> intervals, Instant start, Instant end) {
		long paused = 0L;
		for (PauseInterval interval : intervals) {
			Instant overlapStart = interval.start().isAfter(start) ? interval.start() : start;
			Instant overlapEnd = interval.end().isBefore(end) ? interval.end() : end;
			if (overlapEnd.isAfter(overlapStart)) {
				paused += Duration.between(overlapStart, overlapEnd).toMillis();
			}
		}
		return paused;
	}

	private List<PauseInterval> intervals(List<AgentPause> pauses, Instant now) {
		if (pauses == null || pauses.isEmpty()) {
			return List.of();
		}
		List<PauseInterval> ordered = new ArrayList<>(pauses.size());
		for (AgentPause pause : pauses) {
			Instant start = pause.getPauseStart();
			if (start == null) {
				continue;
			}
			Instant end = pauseEnd(pause, now);
			if (end.isBefore(start)) {
				end = start;
			}
			ordered.add(new PauseInterval(start, end));
		}
		ordered.sort(Comparator.comparing(PauseInterval::start));
		List<PauseInterval> merged = new ArrayList<>(ordered.size());
		for (PauseInterval candidate : ordered) {
			if (!merged.isEmpty()) {
				PauseInterval previous = merged.getLast();
				if (!candidate.start().isAfter(previous.end())) {
					Instant end = candidate.end().isAfter(previous.end()) ? candidate.end() : previous.end();
					merged.set(merged.size() - 1, new PauseInterval(previous.start(), end));
					continue;
				}
			}
			merged.add(candidate);
		}
		return merged;
	}

	private Instant pauseEnd(AgentPause pause, Instant now) {
		PauseStatus status = pause.getStatus();
		if (status == PauseStatus.PAUSED || status == PauseStatus.EXPIRED) {
			return now;
		}
		return pause.getResumeAt() != null ? pause.getResumeAt() : pause.getPauseStart();
	}

	private Instant effectiveEnd(WorkSession session, Instant now) {
		if (session.getEndedAt() != null) {
			return session.getEndedAt();
		}
		return now == null ? session.getStartedAt() : now;
	}

	private ZoneId zoneOf(WorkSession session) {
		String timezone = session.getTimezone();
		if (timezone == null || timezone.isBlank()) {
			return ZoneOffset.UTC;
		}
		try {
			return ZoneId.of(timezone);
		}
		catch (DateTimeException exception) {
			// A row written before the timezone validation existed cannot break a report.
			return ZoneOffset.UTC;
		}
	}

	private record PauseInterval(Instant start, Instant end) {
	}
}
