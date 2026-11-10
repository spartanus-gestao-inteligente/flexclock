package br.com.flexclock.opencode;

/**
 * Facts of one pause attempt.
 *
 * <p>It states what really happened instead of a single boolean, so the pause workstream can apply
 * decision 0045 without inventing state: {@code abortApplied} is true only when the OpenCode server
 * confirmed the abort, and {@code directionTurnSent} is true only when the direction turn was accepted
 * after the abort.
 *
 * @param outcome the closed outcome of the attempt
 * @param abortApplied true when the abort was confirmed by the server
 * @param directionTurnSent true when the direction turn was accepted after the abort
 * @param attempts the number of attempts spent on the operation that decided the outcome
 * @param detail an English detail safe for a local log, never shown to the user as is
 */
public record OpenCodePauseResult(OpenCodePauseOutcome outcome, boolean abortApplied, boolean directionTurnSent,
		int attempts, String detail) {

	/**
	 * Reports whether the pause may be persisted as paused.
	 *
	 * @return true only when the abort and the direction turn both succeeded
	 */
	public boolean isPaused() {
		return outcome == OpenCodePauseOutcome.PAUSED;
	}
}
