package br.com.flexclock.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the idempotency guard: the same key with the same payload is answered once, the same key with
 * another payload is refused, and the guard never runs the write twice.
 */
class IdempotencyServiceTest {

	private final IdempotencyService guard = new IdempotencyService(new InMemoryIdempotencyStore(),
			JsonMapper.builder().build());

	@Test
	void theSameKeyAndPayloadRunsTheWriteOnceAndReplaysTheAnswer() {
		AtomicInteger executions = new AtomicInteger();
		String fingerprint = guard.fingerprint("language", "pt-BR", "agent");

		IdempotentResponse<Sample> first = guard.execute("key-1", fingerprint, 200, Sample.class, () -> {
			executions.incrementAndGet();
			return new Sample("pt-BR");
		});
		IdempotentResponse<Sample> second = guard.execute("key-1", fingerprint, 200, Sample.class, () -> {
			executions.incrementAndGet();
			return new Sample("other");
		});

		assertThat(executions).hasValue(1);
		assertThat(first.status()).isEqualTo(200);
		assertThat(second.status()).isEqualTo(200);
		assertThat(second.body()).isEqualTo(new Sample("pt-BR"));
	}

	@Test
	void theSameKeyWithAnotherPayloadIsRefused() {
		guard.execute("key-2", guard.fingerprint("language", "en"), 200, Sample.class, () -> new Sample("en"));

		assertThatThrownBy(() -> guard.execute("key-2", guard.fingerprint("language", "es"), 200, Sample.class,
				() -> new Sample("es"))).isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@Test
	void differentKeysRunTheWriteEachTime() {
		AtomicInteger executions = new AtomicInteger();
		String fingerprint = guard.fingerprint("language", "en");

		guard.execute("key-3", fingerprint, 200, Sample.class, () -> {
			executions.incrementAndGet();
			return new Sample("en");
		});
		guard.execute("key-4", fingerprint, 200, Sample.class, () -> {
			executions.incrementAndGet();
			return new Sample("en");
		});

		assertThat(executions).hasValue(2);
	}

	@Test
	void theStatusOfTheOriginalAnswerIsKept() {
		IdempotentResponse<Sample> response = guard.execute("key-5", guard.fingerprint("anchor", "ses_1"), 201,
				Sample.class, () -> new Sample("ses_1"));

		assertThat(response.status()).isEqualTo(201);
	}

	@Test
	void theFingerprintDependsOnEveryPartOfThePayload() {
		assertThat(guard.fingerprint("a", "b")).isNotEqualTo(guard.fingerprint("ab", ""));
		assertThat(guard.fingerprint("a", "b")).isEqualTo(guard.fingerprint("a", "b"));
		assertThat(guard.fingerprint("a", null)).isEqualTo(guard.fingerprint("a", ""));
	}

	/**
	 * Response used by the test.
	 *
	 * @param value the stored value
	 */
	private record Sample(String value) {
	}
}
