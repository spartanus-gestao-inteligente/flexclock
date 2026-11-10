package br.com.flexclock.opencode;

import br.com.flexclock.common.ErrorCode;

/**
 * Outcome of a pause attempt, as defined by decision 0034 and refined by decision 0046.
 *
 * <p>The enumeration exists so the pause workstream can decide what to persist without parsing a
 * message: only {@link #PAUSED} means the abort was applied and the direction turn was delivered, so
 * only {@link #PAUSED} may be written as {@code paused}. Every other value leaves the pause record
 * untouched and the user sees the localized message derived from the error code (decision 0046, no
 * phantom pause).
 */
public enum OpenCodePauseOutcome {

	/** The session was aborted and the direction turn was delivered. */
	PAUSED(null),

	/** The OpenCode server refused the connection or did not answer in time. */
	SERVER_DOWN(ErrorCode.OPENCODE_UNREACHABLE),

	/** Another process answered on the port, or the server reported itself unhealthy. */
	PORT_BUSY(ErrorCode.PORT_BUSY),

	/** The anchored session no longer exists. */
	SESSION_GONE(ErrorCode.SESSION_GONE),

	/** The abort or the direction turn failed after the bounded retries. */
	PAUSE_FAILED(ErrorCode.PAUSE_FAILED);

	private final ErrorCode errorCode;

	OpenCodePauseOutcome(ErrorCode errorCode) {
		this.errorCode = errorCode;
	}

	/**
	 * Returns the stable error code the caller publishes for this outcome.
	 *
	 * @return the error code, or null when the pause succeeded
	 */
	public ErrorCode errorCode() {
		return errorCode;
	}
}
