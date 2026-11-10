package br.com.flexclock.common;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Bounded in-memory implementation of the idempotency store.
 *
 * <p>Keeps the most recently used records up to a fixed capacity and drops a record after its time to
 * live. The bound protects a long running desktop session from unbounded growth. The store is
 * thread safe because two windows of the same product can write at the same time.
 *
 * <p>It is the single store behind {@link IdempotencyService}, the one idempotency engine of every
 * write path. The record of a write is stored only after the write committed, so a rollback never
 * leaves a replayable result behind.
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

	private static final int MAX_ENTRIES = 1000;
	private static final Duration TIME_TO_LIVE = Duration.ofHours(24);

	private final Map<String, IdempotencyRecord> records = new LinkedHashMap<>(16, 0.75f, true) {

		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, IdempotencyRecord> eldest) {
			return size() > MAX_ENTRIES;
		}
	};

	@Override
	public synchronized Optional<IdempotencyRecord> find(String idempotencyKey) {
		IdempotencyRecord record = records.get(idempotencyKey);
		if (record == null) {
			return Optional.empty();
		}
		if (isExpired(record)) {
			records.remove(idempotencyKey);
			return Optional.empty();
		}
		return Optional.of(record);
	}

	@Override
	public synchronized void save(IdempotencyRecord record) {
		records.put(record.idempotencyKey(), record);
	}

	private boolean isExpired(IdempotencyRecord record) {
		return record.createdAt().plus(TIME_TO_LIVE).isBefore(Instant.now());
	}
}
