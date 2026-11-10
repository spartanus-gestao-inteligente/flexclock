package br.com.flexclock.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

/**
 * The single idempotency engine of the product, backed by the {@link IdempotencyStore}.
 *
 * <p>Every write path of the backend, the settings surface, the anchor, the clock, the procedure, the
 * pause and the report ingestion, goes through this class. The key presented by the client decides the
 * outcome: the same key with the same payload returns the response of the first call and does not
 * duplicate the effect; the same key with a different payload is rejected with
 * {@link ErrorCode#IDEMPOTENCY_CONFLICT} (architecture contract, section 5; requirement RF-174).
 *
 * <p>The fingerprint is a digest of the stable operation name plus the canonical JSON of the payload,
 * so two different operations can never share a stored result by accident and every field of the
 * payload is part of the comparison.
 *
 * <p>The replay record is written only after the write succeeded, and only after its transaction
 * committed: when a transaction is active the record is registered for {@code afterCommit}, so a write
 * that rolls back never leaves a replayable result behind (requirement RF-155). The engine itself opens
 * no transaction; the caller owns the boundary.
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
	 * Executes a write at most once for a key.
	 *
	 * @param idempotencyKey the key presented by the client
	 * @param operation the stable operation name, part of the fingerprint, for example
	 * {@code anchors.pause}
	 * @param payload the request payload, part of the fingerprint
	 * @param status the HTTP status to publish on the first call
	 * @param responseType the type of the body
	 * @param action the write
	 * @param <T> the type of the body
	 * @return the response of the first call, replayed when the key repeats with the same payload
	 * @throws ApiException with {@link ErrorCode#IDEMPOTENCY_CONFLICT} when the key was used with a
	 * different payload
	 */
	public <T> IdempotentResponse<T> execute(String idempotencyKey, String operation, Object payload, int status,
			Class<T> responseType, Supplier<T> action) {
		Object lock = lockFor(idempotencyKey);
		synchronized (lock) {
			String fingerprint = fingerprint(operation, payload);
			Optional<IdempotencyRecord> existing = store.find(idempotencyKey);
			if (existing.isPresent()) {
				return replay(existing.get(), fingerprint, responseType);
			}
			T body = action.get();
			recordAfterCommit(new IdempotencyRecord(idempotencyKey, fingerprint, status, serialize(body),
					Instant.now()));
			log.debug("Write recorded once for the idempotency key with the status {}", status);
			return new IdempotentResponse<>(status, body);
		}
	}

	/**
	 * Stores the replay record after the commit of the write.
	 *
	 * <p>The write may already be committed when the action returns (a transaction template inside the
	 * action), in which case the record is stored at once inside the per key lock. When a transaction is
	 * still active (an annotated use case), the record waits for its commit, so a rollback discards it
	 * and a repeated key re-executes the write instead of replaying a result that was never stored.
	 *
	 * @param record the record to store
	 */
	private void recordAfterCommit(IdempotencyRecord record) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

				@Override
				public void afterCommit() {
					store.save(record);
				}
			});
			return;
		}
		store.save(record);
	}

	private <T> IdempotentResponse<T> replay(IdempotencyRecord record, String fingerprint,
			Class<T> responseType) {
		if (!record.requestFingerprint().equals(fingerprint)) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, IdempotencyFilter.IDEMPOTENCY_KEY_HEADER,
					"The same idempotency key was already used with a different payload.");
		}
		log.debug("Replaying the stored response of an idempotency key");
		return new IdempotentResponse<>(record.responseStatus(), deserialize(record.responseBody(), responseType));
	}

	private String fingerprint(String operation, Object payload) {
		String canonical = (operation == null ? "" : operation) + FINGERPRINT_SEPARATOR
				+ objectMapper.writeValueAsString(payload);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required by the idempotency guard.", exception);
		}
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
