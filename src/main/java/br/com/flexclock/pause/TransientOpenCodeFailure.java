package br.com.flexclock.pause;

/**
 * Transient failure of an OpenCode call.
 *
 * <p>Only a connection failure, a timeout, a 408, a 429 or a 5xx is transient and therefore retried.
 * Any other refusal is final and is turned into a stable contract error without a retry (decision 0046;
 * requirements RF-042, RF-095).
 */
class TransientOpenCodeFailure extends RuntimeException {

	private static final long serialVersionUID = 1L;

	TransientOpenCodeFailure(String message, Throwable cause) {
		super(message, cause);
	}
}
