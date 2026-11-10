package br.com.flexclock.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Guard that makes every write safe to repeat.
 *
 * <p>The key presented by the client decides the outcome: the same key with the same payload returns
 * the response of the first call and does not duplicate the effect; the same key with a different
 * payload is rejected with {@link ErrorCode#IDEMPOTENCY_CONFLICT}. The payload is compared through a
 * digest of its canonical form, never by parsing the body twice (architecture contract, section 5;
 * requirement RF-174).
 *
 * <p>The guard is deliberately outside the transaction of the write: the record is stored only after
 * the action returned, so a write that rolls back never leaves a replayable result behind (requirement
 * RF-155).
 *
 * <p>Two calls with the same key are serialized by a per key lock, so the second one sees the record of
 * the first one even when the two arrive at the same time. Different keys never block each other, which
 * keeps a pause in one session from waiting on a settings write in another window (requirement
 * RNF-013).
 */
@Component
public class IdempotencyService {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

	/**
	 * Maximum number of per key locks kept in memory. Beyond this bound the map is cleared, which costs
	 * concurrency precision but never correctness, and keeps a long desktop session from growing without
	 * limit (requirement RNF-009).
	 */
	private static final int MAX_LOCKS = 4096;

	private static final char FINGERPRINT_SEPARATOR = '\u001f';

	private final IdempotencyStore store;
	private final ObjectMapper objectMapper;
	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

	public IdempotencyService(IdempotencyStore idempotencyStore, ObjectMapper objectMapper) {
		this.store = idempotencyStore;
		this.objectMapper = objectMapper;
	}

	/**
	 * Builds the digest that identifies the payload of a write.
	 *
	 * @param parts the fields that describe the write, in a stable order
	 * @return the hexadecimal SHA-256 digest
	 */
	public String fingerprint(String... parts) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String part : parts) {
				digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) FINGERPRINT_SEPARATOR);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required by the idempotency guard.", exception);
		}
	}

	/**
	 * Executes a write at most once for a key.
	 *
	 * @param idempotencyKey the key presented by the client
	 * @param fingerprint the digest of the write payload
	 * @param status the HTTP status to publish on the first call
	 * @param responseType the type of the body
	 * @param action the write
	 * @param <T> the type of the body
	 * @return the response of the first call, replayed when the key repeats with the same payload
	 */
	public <T> IdempotentResponse<T> execute(String idempotencyKey, String fingerprint, int status,
			Class<T> responseType, Supplier<T> action) {
		Object lock = lockFor(idempotencyKey);
		synchronized (lock) {
			Optional<IdempotencyRecord> existing = store.find(idempotencyKey);
			if (existing.isPresent()) {
				return replay(existing.get(), fingerprint, responseType);
			}
			T body = action.get();
			String serialized = serialize(body);
			store.save(new IdempotencyRecord(idempotencyKey, fingerprint, status, serialized, Instant.now()));
			log.debug("Write recorded once for the idempotency key with the status {}", status);
			return new IdempotentResponse<>(status, body);
		}
	}

	private <T> IdempotentResponse<T> replay(IdempotencyRecord record, String fingerprint, Class<T> responseType) {
		if (!record.requestFingerprint().equals(fingerprint)) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, IdempotencyFilter.IDEMPOTENCY_KEY_HEADER,
					"The same idempotency key was already used with a different payload.");
		}
		log.debug("Replaying the stored response of an idempotency key");
		return new IdempotentResponse<>(record.responseStatus(), deserialize(record.responseBody(), responseType));
	}

	private Object lockFor(String idempotencyKey) {
		if (locks.size() > MAX_LOCKS) {
			locks.clear();
		}
		return locks.computeIfAbsent(idempotencyKey, key -> new Object());
	}

	private String serialize(Object body) {
		if (body == null) {
			return "null";
		}
		return objectMapper.writeValueAsString(body);
	}

	private <T> T deserialize(String body, Class<T> responseType) {
		if (body == null || "null".equals(body)) {
			return null;
		}
		return objectMapper.readValue(body, responseType);
	}
}
