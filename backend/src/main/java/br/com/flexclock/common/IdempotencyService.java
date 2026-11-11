package br.com.flexclock.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
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
 * <p>Two calls with the same key are serialized by a per key lock, and the lock covers the commit as
 * well as the write itself. When the engine runs inside an active transaction (the report ingestion,
 * whose use case owns the boundary) the release is handed to {@code afterCompletion}, so a duplicate
 * that arrives while the first transaction is still open waits for it instead of running beside it, and
 * then reads the record that {@code afterCommit} stored. The duplicate therefore never reaches the
 * commit window, never meets the unique index of the Idempotency-Key as a database error and always
 * receives the original response (requirement RF-174). A write that rolls back releases the lock and
 * stores nothing, so the same key runs again afterwards. The unique index stays the last resort for a
 * key committed by an earlier process, whose in memory record is gone.
 *
 * <p>Different keys never block each other, which keeps a pause in one session from waiting on a
 * settings write in another window (requirement RNF-013).
 */
@Component
public class IdempotencyService {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

	/**
	 * Maximum number of per key locks kept in memory. Beyond this bound the map is pruned of the locks
	 * that nobody holds and nobody waits for. A lock of a write in flight is never dropped, because
	 * dropping it would let a second writer into the commit window of the first one; the bound is
	 * therefore soft, and it still keeps a long desktop session from growing without limit (requirement
	 * RNF-009).
	 */
	private static final int MAX_LOCKS = 4096;

	private static final char FINGERPRINT_SEPARATOR = '\u001f';

	private final IdempotencyStore store;
	private final ObjectMapper objectMapper;
	private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

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
		ReentrantLock lock = lockFor(idempotencyKey);
		lock.lock();
		TransactionRecord transactionRecord = null;
		try {
			String fingerprint = fingerprint(operation, payload);
			Optional<IdempotencyRecord> existing = store.find(idempotencyKey);
			if (existing.isPresent()) {
				return replay(existing.get(), fingerprint, responseType);
			}
			transactionRecord = openTransactionRecord(lock);
			T body = action.get();
			IdempotencyRecord record = new IdempotencyRecord(idempotencyKey, fingerprint, status, serialize(body),
					Instant.now());
			if (transactionRecord == null) {
				store.save(record);
			}
			else {
				transactionRecord.stored(record);
			}
			log.debug("Write recorded once for the idempotency key with the status {}", status);
			return new IdempotentResponse<>(status, body);
		}
		finally {
			if (transactionRecord == null) {
				lock.unlock();
			}
		}
	}

	/**
	 * Opens the deferred store of the record and the deferred release of the lock, when a transaction is
	 * active.
	 *
	 * <p>The write may already be committed when the action returns (a transaction template inside the
	 * action, or a service the action calls), in which case the record is stored at once inside the per
	 * key lock. When a transaction is still active (an annotated use case, the report ingestion) the
	 * record waits for its commit, so a rollback discards it and a repeated key re-executes the write
	 * instead of replaying a result that was never stored, and the lock is released only after the
	 * transaction completed, so no duplicate can enter the commit window.
	 *
	 * <p>The release is registered before the write runs, not after it succeeded: a write that throws
	 * must keep the key locked until its transaction rolled back, otherwise a duplicate could write
	 * beside a transaction that is still unwinding.
	 *
	 * @param lock the lock of the key
	 * @return the deferred record, or null when no transaction is active
	 */
	private TransactionRecord openTransactionRecord(ReentrantLock lock) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return null;
		}
		TransactionRecord transactionRecord = new TransactionRecord(lock);
		TransactionSynchronizationManager.registerSynchronization(transactionRecord);
		return transactionRecord;
	}

	/**
	 * Stores the record after the commit and releases the lock after the completion of the transaction.
	 *
	 * <p>{@code afterCommit} runs before {@code afterCompletion}, so the record is visible to the next
	 * caller before the key is unlocked: the caller that was waiting reads it and receives the original
	 * response instead of running the write again.
	 */
	private final class TransactionRecord implements TransactionSynchronization {

		private final ReentrantLock lock;

		private IdempotencyRecord record;

		private TransactionRecord(ReentrantLock lock) {
			this.lock = lock;
		}

		private void stored(IdempotencyRecord record) {
			this.record = record;
		}

		@Override
		public void afterCommit() {
			if (record != null) {
				store.save(record);
			}
		}

		@Override
		public void afterCompletion(int status) {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
				return;
			}
			log.error("The idempotency lock of a write completed on a thread that does not hold it.");
		}
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

	private ReentrantLock lockFor(String idempotencyKey) {
		if (locks.size() > MAX_LOCKS) {
			evictIdleLocks();
		}
		return locks.computeIfAbsent(idempotencyKey, key -> new ReentrantLock());
	}

	/**
	 * Drops the locks that nobody holds and nobody waits for.
	 *
	 * <p>A lock of a write in flight is kept even when the map is over the bound: dropping it would give
	 * the next caller of the same key a different lock, and the two writes would run beside each other.
	 */
	private void evictIdleLocks() {
		locks.entrySet().removeIf(entry -> {
			ReentrantLock candidate = entry.getValue();
			return !candidate.isLocked() && !candidate.hasQueuedThreads();
		});
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
