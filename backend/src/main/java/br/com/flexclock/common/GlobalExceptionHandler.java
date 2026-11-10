package br.com.flexclock.common;

import java.time.temporal.Temporal;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.exc.ValueInstantiationException;

/**
 * Turns every exception into the single error envelope of the contract.
 *
 * <p>No stack trace, SQL statement, file path, driver message or internal class name ever reaches the
 * client: the envelope carries a stable code, a safe English message, the field or resource and an
 * optional detail. The real cause is logged locally with a correlation identifier, and a server error
 * publishes that identifier in the {@code X-Correlation-Id} response header so a local log line can
 * be found from a client report (requirement RF-154).
 *
 * <p>Architecture contract, sections 6.3 and 6.4.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** Response header that correlates a server error with a local log line. */
	public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
		log.debug("Rejected request with code {}", exception.errorCode(), exception);
		return envelope(exception.errorCode(), exception.field(), exception.detail());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException exception) {
		return fromBindingErrors(exception.getBindingResult().getFieldErrors());
	}

	@ExceptionHandler(BindException.class)
	public ResponseEntity<ErrorResponse> handleBindException(BindException exception) {
		return fromBindingErrors(exception.getBindingResult().getFieldErrors());
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException exception) {
		String field = exception.getParameterValidationResults().stream()
				.map(result -> result.getMethodParameter().getParameterName())
				.filter(name -> name != null)
				.findFirst()
				.orElse(null);
		return envelope(ErrorCode.VALIDATION_ERROR, field, "A request value is invalid.");
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
		ConstraintViolation<?> violation = exception.getConstraintViolations().stream().findFirst().orElse(null);
		String field = violation == null ? null : violation.getPropertyPath().toString();
		String detail = violation == null ? null : violation.getMessage();
		return envelope(ErrorCode.VALIDATION_ERROR, field, detail);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
		Class<?> requiredType = exception.getRequiredType();
		if (requiredType != null && requiredType.isEnum()) {
			return envelope(ErrorCode.INVALID_ENUM_VALUE, exception.getName(),
					"The value is not part of the closed set of " + requiredType.getSimpleName() + ".");
		}
		if (requiredType != null && Temporal.class.isAssignableFrom(requiredType)) {
			return envelope(ErrorCode.MALFORMED_DATE, exception.getName(),
					"The date must use the ISO 8601 format.");
		}
		return envelope(ErrorCode.VALIDATION_ERROR, exception.getName(), "The value has the wrong type.");
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
		UnrecognizedPropertyException unknownField = findCause(exception, UnrecognizedPropertyException.class);
		if (unknownField != null) {
			return envelope(ErrorCode.UNKNOWN_FIELD, unknownField.getPropertyName(),
					"The request contains a field that is not part of the contract.");
		}

		InvalidFormatException invalidFormat = findCause(exception, InvalidFormatException.class);
		if (invalidFormat != null && invalidFormat.getTargetType() != null) {
			if (invalidFormat.getTargetType().isEnum()) {
				return envelope(ErrorCode.INVALID_ENUM_VALUE, lastPathSegment(invalidFormat),
						"The value is not part of the closed set of " + invalidFormat.getTargetType().getSimpleName() + ".");
			}
			if (Temporal.class.isAssignableFrom(invalidFormat.getTargetType())) {
				return envelope(ErrorCode.MALFORMED_DATE, lastPathSegment(invalidFormat),
						"The date must use the ISO 8601 format.");
			}
		}

		ValueInstantiationException valueInstantiation = findCause(exception, ValueInstantiationException.class);
		if (valueInstantiation != null && valueInstantiation.getType() != null
				&& valueInstantiation.getType().getRawClass() != null
				&& valueInstantiation.getType().getRawClass().isEnum()) {
			return envelope(ErrorCode.INVALID_ENUM_VALUE, lastPathSegment(valueInstantiation),
					"The value is not part of the closed set of "
							+ valueInstantiation.getType().getRawClass().getSimpleName() + ".");
		}

		return envelope(ErrorCode.VALIDATION_ERROR, null, "The request body is malformed.");
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException exception) {
		if (IdempotencyFilter.IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(exception.getHeaderName())) {
			return envelope(ErrorCode.IDEMPOTENCY_KEY_MISSING, exception.getHeaderName(),
					"Every write must carry the Idempotency-Key header.");
		}
		return envelope(ErrorCode.VALIDATION_ERROR, exception.getHeaderName(),
				"The request is missing a required header.");
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException exception) {
		return envelope(ErrorCode.VALIDATION_ERROR, exception.getParameterName(),
				"The request is missing a required parameter.");
	}

	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
	public ResponseEntity<ErrorResponse> handleNotFound(Exception exception) {
		return envelope(ErrorCode.NOT_FOUND, null, "The path is not part of the contract.");
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
		return envelope(ErrorCode.VALIDATION_ERROR, null,
				"The HTTP method " + exception.getMethod() + " is not supported for this path.");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException exception) {
		return envelope(ErrorCode.VALIDATION_ERROR, "Content-Type",
				"The media type of the request is not supported.");
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handlePayloadTooLarge(MaxUploadSizeExceededException exception) {
		return envelope(ErrorCode.PAYLOAD_TOO_LARGE, null, "The request body exceeds the endpoint limit.");
	}

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException exception) {
		String correlationId = newCorrelationId();
		log.error("Database failure, correlationId={}", correlationId, exception);
		return serverError(ErrorCode.DB_WRITE_FAILURE, correlationId);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
		String correlationId = newCorrelationId();
		log.error("Unexpected failure, correlationId={}", correlationId, exception);
		return serverError(ErrorCode.INTERNAL_ERROR, correlationId);
	}

	private ResponseEntity<ErrorResponse> fromBindingErrors(List<FieldError> fieldErrors) {
		if (fieldErrors.isEmpty()) {
			return envelope(ErrorCode.VALIDATION_ERROR, null, "The request is invalid.");
		}
		FieldError first = fieldErrors.getFirst();
		String detail = first.getDefaultMessage();
		if (fieldErrors.size() > 1) {
			String alsoInvalid = fieldErrors.stream().skip(1).map(FieldError::getField)
					.distinct()
					.reduce((left, right) -> left + ", " + right)
					.orElse("");
			detail = detail + " Also invalid: " + alsoInvalid + ".";
		}
		return envelope(ErrorCode.VALIDATION_ERROR, first.getField(), detail);
	}

	private ResponseEntity<ErrorResponse> envelope(ErrorCode code, String field, String detail) {
		return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, field, detail));
	}

	private ResponseEntity<ErrorResponse> serverError(ErrorCode code, String correlationId) {
		return ResponseEntity.status(code.status())
				.header(CORRELATION_ID_HEADER, correlationId)
				.body(ErrorResponse.of(code, null, null));
	}

	private String newCorrelationId() {
		return UUID.randomUUID().toString();
	}

	private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
		Throwable current = throwable;
		while (current != null) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			current = current.getCause();
		}
		return null;
	}

	private String lastPathSegment(DatabindException exception) {
		List<?> path = exception.getPath();
		if (path == null || path.isEmpty()) {
			return null;
		}
		Object last = path.getLast();
		if (last instanceof tools.jackson.core.JacksonException.Reference reference) {
			return reference.getPropertyName();
		}
		return null;
	}
}
