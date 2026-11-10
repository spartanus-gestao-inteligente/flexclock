package br.com.flexclock.time;

import br.com.flexclock.enums.TimeMode;

/**
 * Current time state of the clock window.
 *
 * <p>Shape frozen by the architecture contract, section 6.2. The clock window renders from this record
 * and never calculates time locally (requirement RF-025): {@code elapsedMs} is the active coding time,
 * with the paused time already subtracted, and {@code remainingMs} is the countdown remainder, null for
 * a stopwatch. A remaining time of zero means the project countdown reached its target; reaching the
 * target never stops the session, because only the agent stops the time (decision 0020).
 *
 * @param mode the time mode, null when no session is running
 * @param elapsedMs the active coding time in milliseconds
 * @param remainingMs the countdown remainder in milliseconds, null for a stopwatch
 * @param running true while a session is open
 * @param procedureId the procedure of the session, null when the caller did not name one
 */
public record ClockState(TimeMode mode, Long elapsedMs, Long remainingMs, boolean running, Long procedureId) {
}
