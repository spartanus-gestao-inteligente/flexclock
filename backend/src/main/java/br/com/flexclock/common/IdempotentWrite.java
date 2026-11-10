package br.com.flexclock.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Applies the idempotency rule of the contract to a write.
 *
 * <p>Every write carries an Idempotency-Key. The same key with the same payload returns the original
 * result and does not duplicate the effect; the same key with a different payload is rejected with
 * {@link ErrorCode#IDEMPOTENCY_CONFLICT} (architecture contract, section 5; requirement RF-174).
 *
 * <p>The fingerprint is a digest of the operation name plus the canonical JSON of the payload, so two
 * different operations can never share a stored result by accident. The store is the single
 * {@link IdempotencyStore} bean; the whole read, execute and store sequence runs under one monitor,
 * which serializes writes. That is acceptable for a single user desktop product and it is the same
 * serialization the single writer SQLite connection already imposes.
 */
@Component
public class IdempotentWrite {

	private static final String FINGERPRINT_ALGORITHM = "SHA-256";

	private final IdempotencyStore store;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public IdempotentWrite(IdempotencyStore store, ObjectMapper objectMapper, Clock clock) {
		this.store = store;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/**
	 * Executes a write once per idempotency key.
	 *
	 * @param idempotencyKey the key presented by the client
	 * @param operation the stable operation name, part of the fingerprint
	 * @param payload the request payload, part of the fingerprint
	 * @param successStatus the status of a successful write
	 * @param responseType the type of the response body
	 * @param action the write itself
	 * @param <T> the response type
	 * @return the original result for a repeated key, the new result otherwise
	 * @throws ApiException with {@link ErrorCode#IDEMPOTENCY_CONFLICT} when the key was used with a
	 * different payload
	 */
	public <T> ResponseEntity<T> execute(String idempotencyKey, String operation, Object payload,
			HttpStatus successStatus, Class<T> responseType, Supplier<T> action) {
		synchronized (store) {
			String fingerprint = fingerprint(operation, payload);
			Optional<IdempotencyRecord> existing = store.find(idempotencyKey);
			if (existing.isPresent()) {
				IdempotencyRecord record = existing.get();
				if (!record.requestFingerprint().equals(fingerprint)) {
					throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key",
							"The key was already used with a different payload.");
				}
				String storedBody = record.responseBody();
				T body = storedBody == null || storedBody.isEmpty() ? null
						: objectMapper.readValue(storedBody, responseType);
				return ResponseEntity.status(record.responseStatus()).body(body);
			}

			T result = action.get();
			store.save(new IdempotencyRecord(idempotencyKey, fingerprint, successStatus.value(),
					serialize(result), clock.instant()));
			return ResponseEntity.status(successStatus).body(result);
		}
	}

	/**
	 * Serializes the answer of the write, keeping an empty representation for a write without a body so
	 * a repetition of a 204 answers 204 again instead of failing to deserialize.
	 *
	 * @param result the answer of the write, may be null
	 * @return the stored representation, never null
	 */
	private String serialize(Object result) {
		return result == null ? "" : objectMapper.writeValueAsString(result);
	}

	private String fingerprint(String operation, Object payload) {
		String canonical = operation + "|" + objectMapper.writeValueAsString(payload);
		try {
			MessageDigest digest = MessageDigest.getInstance(FINGERPRINT_ALGORITHM);
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("The SHA-256 digest is required by the idempotency store", exception);
		}
	}
}
