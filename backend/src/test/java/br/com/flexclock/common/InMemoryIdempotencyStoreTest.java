package br.com.flexclock.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Guards the idempotency store contract delivered in Phase 0.
 */
class InMemoryIdempotencyStoreTest {

	private final IdempotencyStore store = new InMemoryIdempotencyStore();

	@Test
	void aStoredKeyIsFoundWithItsOriginalResult() {
		IdempotencyRecord record = new IdempotencyRecord("key-1", "fingerprint-1", 201, "{\"id\":1}", Instant.now());

		store.save(record);

		assertThat(store.find("key-1")).contains(record);
	}

	@Test
	void anUnknownKeyIsEmpty() {
		assertThat(store.find("unknown")).isEmpty();
	}

	@Test
	void anExpiredRecordIsDropped() {
		Instant expired = Instant.now().minus(Duration.ofDays(2));
		store.save(new IdempotencyRecord("key-2", "fingerprint-2", 201, "{}", expired));

		assertThat(store.find("key-2")).isEmpty();
	}
}
