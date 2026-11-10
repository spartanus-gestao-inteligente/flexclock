package br.com.flexclock.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the single idempotency engine: the same key with the same payload is answered once, the same
 * key with another payload or another operation is refused, and a write without a body replays an empty
 * answer.
 */
class IdempotencyServiceTest {

	private final IdempotencyService guard = new IdempotencyService(new InMemoryIdempotencyStore(),
			JsonMapper.builder().build());

	@Test
	void theSameKeyAndPayloadRunsTheWriteOnceAndReplaysTheAnswer() {
		AtomicInteger executions = new AtomicInteger();
		Map<String, Object> payload = Map.of("language", "pt-BR", "actor", "agent");

		IdempotentResponse<Sample> first = guard.execute("key-1", "settings.language", payload, 200, Sample.class,
				() -> {
					executions.incrementAndGet();
					return new Sample("pt-BR");
				});
		IdempotentResponse<Sample> second = guard.execute("key-1", "settings.language", payload, 200, Sample.class,
				() -> {
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
		guard.execute("key-2", "settings.language", Map.of("language", "en"), 200, Sample.class,
				() -> new Sample("en"));

		assertThatThrownBy(() -> guard.execute("key-2", "settings.language", Map.of("language", "es"), 200,
				Sample.class, () -> new Sample("es"))).isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@Test
	void theSameKeyOnAnotherOperationIsRefused() {
		guard.execute("key-3", "anchors.create", Map.of("sessionId", "ses_1"), 201, Sample.class,
				() -> new Sample("anchor"));

		assertThatThrownBy(() -> guard.execute("key-3", "procedures.complete", Map.of("sessionId", "ses_1"), 200,
				Sample.class, () -> new Sample("procedure"))).isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@Test
	void differentKeysRunTheWriteEachTime() {
		AtomicInteger executions = new AtomicInteger();
		Map<String, Object> payload = Map.of("language", "en");

		guard.execute("key-4", "settings.language", payload, 200, Sample.class, () -> {
			executions.incrementAndGet();
			return new Sample("en");
		});
		guard.execute("key-5", "settings.language", payload, 200, Sample.class, () -> {
			executions.incrementAndGet();
			return new Sample("en");
		});

		assertThat(executions).hasValue(2);
	}

	@Test
	void theStatusOfTheOriginalAnswerIsKept() {
		IdempotentResponse<Sample> response = guard.execute("key-6", "anchors.create", Map.of("sessionId", "ses_1"),
				201, Sample.class, () -> new Sample("ses_1"));

		assertThat(response.status()).isEqualTo(201);
	}

	@Test
	void aWriteWithoutABodyReplaysAnEmptyAnswer() {
		guard.execute("key-7", "anchors.heartbeat", 42L, 204, Void.class, () -> null);

		IdempotentResponse<Void> replay = guard.execute("key-7", "anchors.heartbeat", 42L, 204, Void.class, () -> {
			throw new IllegalStateException("the write must not run again");
		});

		assertThat(replay.status()).isEqualTo(204);
		assertThat(replay.body()).isNull();
	}

	/**
	 * Response used by the test.
	 *
	 * @param value the stored value
	 */
	private record Sample(String value) {
	}
}
