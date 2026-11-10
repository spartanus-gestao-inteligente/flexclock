package br.com.flexclock.common;

import org.springframework.http.HttpStatus;

/**
 * Stable machine readable error codes of the REST contract.
 *
 * <p>Every error response carries one of these codes, an English message, the field or resource
 * involved and an optional detail. The interface localizes the message from the code, so the backend
 * never depends on a localized phrase and the codes never change meaning inside a major contract
 * version.
 *
 * <p>Architecture contract, section 6.4. The list is closed: adding a code is an additive contract
 * change and must be recorded before it is used.
 */
public enum ErrorCode {

	/** One or more fields are invalid, one error per field. */
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "One or more fields are invalid."),

	/** An unknown field was sent. */
	UNKNOWN_FIELD(HttpStatus.BAD_REQUEST, "An unknown field was sent."),

	/** A value is outside the allowed range. */
	VALUE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "A value is outside the allowed range."),

	/** An enumerator value is not in the closed set. */
	INVALID_ENUM_VALUE(HttpStatus.BAD_REQUEST, "An enumerator value is not in the closed set."),

	/** A date is malformed. */
	MALFORMED_DATE(HttpStatus.BAD_REQUEST, "A date is malformed."),

	/** A write request arrived without the Idempotency-Key header. */
	IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST, "A write request requires the Idempotency-Key header."),

	/** The same Idempotency-Key was used with a different payload. */
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "The Idempotency-Key was already used with a different payload."),

	/** The request arrived without the per run instance identifier. */
	INSTANCE_ID_MISSING(HttpStatus.BAD_REQUEST, "The request is missing the instance identifier."),

	/** The instance identifier does not match the live process. */
	INSTANCE_ID_MISMATCH(HttpStatus.FORBIDDEN, "The instance identifier does not match the live process."),

	/** The request arrived without the contract version. */
	MISSING_VERSION(HttpStatus.BAD_REQUEST, "The request is missing the contract version."),

	/** The contract major version is divergent. */
	VERSION_MISMATCH(HttpStatus.CONFLICT, "The contract major version is not supported."),

	/** The setting key is owned by the user and cannot be changed by the agent. */
	SETTING_NOT_WRITABLE(HttpStatus.FORBIDDEN, "The setting is owned by the user and cannot be changed by the agent."),

	/** The session is already anchored. */
	ANCHOR_EXISTS(HttpStatus.CONFLICT, "The session is already anchored."),

	/** The anchor does not exist. */
	ANCHOR_NOT_FOUND(HttpStatus.NOT_FOUND, "The anchor does not exist."),

	/** The anchor is stale and the pause is disabled. */
	ANCHOR_STALE(HttpStatus.CONFLICT, "The anchor is stale and the pause is disabled."),

	/** No anchored session exists for the pause. */
	NO_SESSION_ANCHORED(HttpStatus.CONFLICT, "No anchored session exists for the pause."),

	/** The OpenCode session no longer exists. */
	SESSION_GONE(HttpStatus.NOT_FOUND, "The OpenCode session no longer exists."),

	/** The pause transition is not allowed. */
	INVALID_PAUSE_TRANSITION(HttpStatus.CONFLICT, "The pause transition is not allowed."),

	/** The procedure transition is not allowed. */
	INVALID_PROCEDURE_TRANSITION(HttpStatus.CONFLICT, "The procedure transition is not allowed."),

	/**
	 * The procedure already has an open work session.
	 *
	 * <p>Additive code of the time workstream: the contract allows additive evolution inside the same
	 * major version, and the generated OpenAPI document publishes it. The code never changes meaning.
	 */
	WORK_SESSION_ALREADY_RUNNING(HttpStatus.CONFLICT, "The procedure already has an open work session."),

	/**
	 * The work session is not open, so it cannot be stopped.
	 *
	 * <p>Additive code of the time workstream, on the same terms as
	 * {@link #WORK_SESSION_ALREADY_RUNNING}.
	 */
	WORK_SESSION_NOT_RUNNING(HttpStatus.CONFLICT, "The work session is not running."),

	/** The requested resource was not found. */
	NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),

	/** The request body exceeds the endpoint limit. */
	PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "The request body exceeds the endpoint limit."),

	/** The OpenCode server is unreachable. */
	OPENCODE_UNREACHABLE(HttpStatus.SERVICE_UNAVAILABLE, "The OpenCode server is unreachable."),

	/** The port is held by another process. */
	PORT_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "The port is held by another process."),

	/** The abort or the direction turn failed after the retries. */
	PAUSE_FAILED(HttpStatus.BAD_GATEWAY, "The abort or the direction turn failed after the retries."),

	/** A database write failed. */
	DB_WRITE_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR, "The database write failed."),

	/** An unexpected error occurred. */
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	/**
	 * Returns the HTTP status that this code always maps to.
	 *
	 * @return the HTTP status
	 */
	public HttpStatus status() {
		return status;
	}

	/**
	 * Returns the safe English message of the code.
	 *
	 * @return the English message, never null
	 */
	public String message() {
		return message;
	}
}
