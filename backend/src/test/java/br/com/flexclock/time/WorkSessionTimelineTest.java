package br.com.flexclock.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.flexclock.dto.TimeSegment;
import br.com.flexclock.enums.PauseReason;
import br.com.flexclock.enums.PauseStatus;
import br.com.flexclock.enums.TimeMode;
import br.com.flexclock.model.AgentPause;
import br.com.flexclock.model.WorkSession;

/**
 * Proves the time semantics of a work session without a database.
 *
 * <p>Every duration must come from the recorded instants, the midnight split must never overlap or
 * double count, a pause that crosses midnight must be discounted on both days, and a clock that moves
 * backwards must never produce a negative duration (decision 0020; requirements RF-022, RF-023,
 * RF-024).
 */
class WorkSessionTimelineTest {

	private static final Instant T0 = Instant.parse("2026-11-11T02:00:00Z");

	private static final String SAO_PAULO = "America/Sao_Paulo";

	private final WorkSessionTimeline timeline = new WorkSessionTimeline();

	@Test
	void theElapsedTimeComesFromTheRecordedInstants() {
		WorkSession session = stopwatch(T0, SAO_PAULO);

		assertThat(timeline.totalMillis(session, T0.plusSeconds(90))).isEqualTo(90_000L);
		assertThat(timeline.activeMillis(session, List.of(), T0.plusSeconds(90))).isEqualTo(90_000L);
		assertThat(timeline.remainingMillis(session, 90_000L)).isNull();
		assertThat(timeline.timerCompleted(session, 90_000L)).isFalse();
	}

	@Test
	void aClockThatMovesBackwardsNeverProducesANegativeDuration() {
		WorkSession session = stopwatch(T0, SAO_PAULO);

		assertThat(timeline.totalMillis(session, T0.minusSeconds(30))).isZero();
		assertThat(timeline.activeMillis(session, List.of(), T0.minusSeconds(30))).isZero();
	}

	@Test
	void aProjectCountdownNeverReportsANegativeRemainder() {
		WorkSession session = WorkSession.start(TimeMode.PROJECT_STOPWATCH, 60, null, T0, SAO_PAULO);

		assertThat(timeline.remainingMillis(session, 30_000L)).isEqualTo(30_000L);
		assertThat(timeline.timerCompleted(session, 30_000L)).isFalse();
		assertThat(timeline.remainingMillis(session, 60_000L)).isZero();
		assertThat(timeline.timerCompleted(session, 60_000L)).isTrue();
		assertThat(timeline.remainingMillis(session, 90_000L)).isZero();
		assertThat(timeline.timerCompleted(session, 90_000L)).isTrue();
	}

	@Test
	void aSessionThatCrossesMidnightIsSplitIntoOneSegmentPerLocalDay() {
		// 02:00 UTC is 23:00 on the previous day in Sao Paulo; 04:00 UTC is 01:00 on the next day.
		WorkSession session = stopwatch(T0, SAO_PAULO);

		List<TimeSegment> segments = timeline.segments(session, List.of(), T0.plus(Duration.ofHours(2)));

		assertThat(segments).hasSize(2);
		assertThat(segments.get(0).date()).isEqualTo(LocalDate.of(2026, 11, 10));
		assertThat(segments.get(0).totalMs()).isEqualTo(3_600_000L);
		assertThat(segments.get(1).date()).isEqualTo(LocalDate.of(2026, 11, 11));
		assertThat(segments.get(1).totalMs()).isEqualTo(3_600_000L);
		assertThat(segments).allSatisfy(segment -> assertThat(segment.activeMs())
				.isEqualTo(segment.totalMs() - segment.pausedMs()));
	}

	@Test
	void aPauseThatCrossesMidnightIsDiscountedOnBothDays() {
		WorkSession session = stopwatch(T0, SAO_PAULO);
		AgentPause pause = resumedPause(T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofMinutes(90)));

		List<TimeSegment> segments = timeline.segments(session, List.of(pause), T0.plus(Duration.ofHours(2)));

		assertThat(segments).hasSize(2);
		assertThat(segments.get(0).totalMs()).isEqualTo(3_600_000L);
		assertThat(segments.get(0).pausedMs()).isEqualTo(1_800_000L);
		assertThat(segments.get(0).activeMs()).isEqualTo(1_800_000L);
		assertThat(segments.get(1).pausedMs()).isEqualTo(1_800_000L);
		assertThat(segments.get(1).activeMs()).isEqualTo(1_800_000L);
	}

	@Test
	void overlappingPausesAreNeverDiscountedTwice() {
		AgentPause first = resumedPause(T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofMinutes(60)));
		AgentPause second = resumedPause(T0.plus(Duration.ofMinutes(45)), T0.plus(Duration.ofMinutes(75)));

		long paused = timeline.pausedMillis(List.of(first, second), T0, T0.plus(Duration.ofHours(2)),
				T0.plus(Duration.ofHours(2)));

		assertThat(paused).isEqualTo(Duration.ofMinutes(45).toMillis());
	}

	@Test
	void anExpiredPauseKeepsAccruingUntilItIsResolved() {
		AgentPause expired = AgentPause.open(1L, "ses_01", T0.plus(Duration.ofMinutes(30)));
		expired.setStatus(PauseStatus.EXPIRED);
		expired.setDurationMs(Duration.ofMinutes(60).toMillis());

		long paused = timeline.pausedMillis(List.of(expired), T0, T0.plus(Duration.ofMinutes(120)),
				T0.plus(Duration.ofMinutes(120)));

		assertThat(paused).isEqualTo(Duration.ofMinutes(90).toMillis());
	}

	@Test
	void anAbortedPauseIsDiscountedByItsOwnDuration() {
		AgentPause aborted = AgentPause.open(1L, "ses_01", T0.plus(Duration.ofMinutes(30)));
		aborted.setStatus(PauseStatus.ABORTED);
		aborted.setReason(PauseReason.USER_CANCEL);
		aborted.setResumeAt(T0.plus(Duration.ofMinutes(45)));
		aborted.setDurationMs(Duration.ofMinutes(15).toMillis());

		long paused = timeline.pausedMillis(List.of(aborted), T0, T0.plus(Duration.ofHours(2)),
				T0.plus(Duration.ofHours(2)));

		assertThat(paused).isEqualTo(Duration.ofMinutes(15).toMillis());
	}

	@Test
	void aSessionAcrossTheSpringDstChangeStillSplitsByLocalDay() {
		// Europe/Berlin moves to summer time on 2026-03-29; the split must follow the local dates.
		Instant start = Instant.parse("2026-03-28T12:00:00Z");
		WorkSession session = stopwatch(start, "Europe/Berlin");

		List<TimeSegment> segments = timeline.segments(session, List.of(), start.plus(Duration.ofHours(48)));

		assertThat(segments).extracting(TimeSegment::date)
				.containsExactly(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 30));
		assertThat(segments.stream().mapToLong(TimeSegment::totalMs).sum())
				.isEqualTo(Duration.ofHours(48).toMillis());
	}

	@Test
	void theSegmentsAlwaysAddUpToTheSessionTotals() {
		WorkSession session = stopwatch(T0, SAO_PAULO);
		AgentPause pause = resumedPause(T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofMinutes(90)));
		Instant end = T0.plus(Duration.ofHours(2));

		List<TimeSegment> segments = timeline.segments(session, List.of(pause), end);
		long total = timeline.totalMillis(session, end);
		long paused = timeline.pausedMillis(List.of(pause), T0, end, end);

		assertThat(segments.stream().mapToLong(TimeSegment::totalMs).sum()).isEqualTo(total);
		assertThat(segments.stream().mapToLong(TimeSegment::pausedMs).sum()).isEqualTo(paused);
		assertThat(segments.stream().mapToLong(TimeSegment::activeMs).sum()).isEqualTo(total - paused);
	}

	private WorkSession stopwatch(Instant start, String timezone) {
		return WorkSession.start(TimeMode.STOPWATCH, null, null, start, timezone);
	}

	private AgentPause resumedPause(Instant start, Instant end) {
		AgentPause pause = AgentPause.open(1L, "ses_01", start);
		pause.setStatus(PauseStatus.RESUMED);
		pause.setResumeAt(end);
		pause.setDurationMs(Duration.between(start, end).toMillis());
		return pause;
	}
}
