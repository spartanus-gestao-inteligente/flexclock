package br.com.flexclock.common;

/**
 * The single error envelope of the REST contract.
 *
 * <p>Every error response is one of these records: an English machine readable code, an English
 * message, the field or resource involved and an optional detail. The UI localizes from the code and
 * its parameters; the backend never depends on a localized phrase.
 *
 * <p>Architecture contract, section 6.3.
 *
 * @param code the stable error code
 * @param message the safe English message
 * @param field the field or resource involved, null when the error is not tied to one
 * @param detail an optional English detail, null when there is none
 */
public record ErrorResponse(ErrorCode code, String message, String field, String detail) {

	/**
	 * Builds an envelope using the safe default message of the code.
	 *
	 * @param code the stable error code
	 * @param field the field or resource involved, may be null
	 * @param detail an optional English detail, may be null
	 * @return the error envelope
	 */
	public static ErrorResponse of(ErrorCode code, String field, String detail) {
		return new ErrorResponse(code, code.message(), field, detail);
	}

	/**
	 * Builds an envelope with an explicit English message.
	 *
	 * @param code the stable error code
	 * @param message the English message to publish
	 * @param field the field or resource involved, may be null
	 * @param detail an optional English detail, may be null
	 * @return the error envelope
	 */
	public static ErrorResponse of(ErrorCode code, String message, String field, String detail) {
		return new ErrorResponse(code, message, field, detail);
	}
}
