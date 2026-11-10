package br.com.flexclock.opencode;

/**
 * Raised when the OpenCode server cannot answer: connection refused, timeout, a port held by another
 * process or a response the client cannot read.
 *
 * <p>The exception carries no payload and no URL, so it can never leak the endpoint into a response.
 * The calling service turns it into the stable {@code OPENCODE_UNREACHABLE} error of the contract
 * (decision 0046; requirements RF-042 and RF-044).
 */
public class OpenCodeUnavailableException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 *
	 * @param message the local diagnostic message, never published to the client
	 */
	public OpenCodeUnavailableException(String message) {
		super(message);
	}

	/**
	 * Creates the exception with the local cause.
	 *
	 * @param message the local diagnostic message, never published to the client
	 * @param cause the transport failure
	 */
	public OpenCodeUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
