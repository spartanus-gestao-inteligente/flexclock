package br.com.flexclock.common;

/**
 * Application exception that already carries a stable contract error code.
 *
 * <p>Services throw this exception when the failure is a product rule with a known code. The global
 * handler turns it into the single error envelope. Any other exception becomes
 * {@link ErrorCode#INTERNAL_ERROR} and never leaks a stack trace, an SQL statement, a file path or a
 * driver message to the client.
 *
 * <p>Architecture contract, sections 6.3 and 6.4.
 */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode errorCode;
	private final String field;
	private final String detail;

	/**
	 * Creates the exception with the safe default message of the code.
	 *
	 * @param errorCode the stable error code
	 * @param field the field or resource involved, may be null
	 * @param detail an optional English detail, may be null
	 */
	public ApiException(ErrorCode errorCode, String field, String detail) {
		super(errorCode.message());
		this.errorCode = errorCode;
		this.field = field;
		this.detail = detail;
	}

	/**
	 * Returns the stable error code.
	 *
	 * @return the error code
	 */
	public ErrorCode errorCode() {
		return errorCode;
	}

	/**
	 * Returns the field or resource involved.
	 *
	 * @return the field or resource, may be null
	 */
	public String field() {
		return field;
	}

	/**
	 * Returns the optional English detail.
	 *
	 * @return the detail, may be null
	 */
	public String detail() {
		return detail;
	}

	/**
	 * Builds the envelope of this exception.
	 *
	 * @return the error envelope
	 */
	public ErrorResponse toErrorResponse() {
		return ErrorResponse.of(errorCode, getMessage(), field, detail);
	}
}
