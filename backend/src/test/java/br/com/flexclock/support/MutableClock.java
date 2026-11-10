package br.com.flexclock.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Controllable clock used by the time and pause tests.
 *
 * <p>The production code reads every instant from the {@link Clock} bean, so a test can move time
 * forward without sleeping and prove the midnight split, the timer completion and the 60 minute pause
 * expiry deterministically.
 */
public final class MutableClock extends Clock {

	private final ZoneId zone;

	private volatile Instant instant;

	private MutableClock(Instant instant, ZoneId zone) {
		this.instant = instant;
		this.zone = zone;
	}

	/**
	 * Creates a clock frozen at an instant, in UTC.
	 *
	 * @param instant the initial instant
	 * @return the controllable clock
	 */
	public static MutableClock at(Instant instant) {
		return new MutableClock(instant, ZoneOffset.UTC);
	}

	/**
	 * Moves the clock to an instant.
	 *
	 * @param instant the new instant
	 */
	public void set(Instant instant) {
		this.instant = instant;
	}

	/**
	 * Moves the clock forward.
	 *
	 * @param duration the amount of time to advance
	 */
	public void advance(Duration duration) {
		this.instant = this.instant.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId newZone) {
		return new MutableClock(instant, newZone);
	}

	@Override
	public Instant instant() {
		return instant;
	}
}
