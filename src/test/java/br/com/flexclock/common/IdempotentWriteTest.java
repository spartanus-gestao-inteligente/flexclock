package br.com.flexclock.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the three cases of the idempotency rule on a write.
 *
 * <p>The first use of a key runs the write and remembers the answer; the same key with the same
 * payload returns the remembered answer without running the write again; the same key with a different
 * payload is a conflict (architecture contract, section 5; requirement RF-174). The operation name is
 * part of the fingerprint, so two endpoints can never share a remembered answer by accident.
 */
class IdempotentWriteTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);

	private IdempotentWrite idempotentWrite;

	@BeforeEach
	void setUp() {
		idempotentWrite = new IdempotentWrite(new InMemoryIdempotencyStore(), JsonMapper.builder().build(), CLOCK);
	}

	@Test
	void theFirstWriteRunsAndRemembersTheAnswer() {
		AtomicInteger executions = new AtomicInteger();

		ResponseEntity<String> write = idempotentWrite.execute("key-1", "anchors.create", "payload",
				HttpStatus.CREATED, String.class, () -> {
					executions.incrementAndGet();
					return "answer";
				});

		assertThat(write.getStatusCode().value()).isEqualTo(201);
		assertThat(write.getBody()).isEqualTo("answer");
		assertThat(executions).hasValue(1);
	}

	@Test
	void theSameKeyAndPayloadReplaysTheOriginalAnswerWithoutRunningTheWrite() {
		AtomicInteger executions = new AtomicInteger();
		idempotentWrite.execute("key-1", "anchors.create", "payload", HttpStatus.CREATED, String.class, () -> {
			executions.incrementAndGet();
			return "first";
		});

		ResponseEntity<String> replay = idempotentWrite.execute("key-1", "anchors.create", "payload",
				HttpStatus.CREATED, String.class, () -> {
					executions.incrementAndGet();
					return "second";
				});

		assertThat(replay.getStatusCode().value()).isEqualTo(201);
		assertThat(replay.getBody()).isEqualTo("first");
		assertThat(executions).hasValue(1);
	}

	@Test
	void theSameKeyWithADifferentPayloadIsAConflict() {
		idempotentWrite.execute("key-1", "anchors.create", "payload", HttpStatus.CREATED, String.class,
				() -> "first");

		assertThatThrownBy(() -> idempotentWrite.execute("key-1", "anchors.create", "other", HttpStatus.CREATED,
				String.class, () -> "second"))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

	@Test
	void theSameKeyOnAnotherOperationIsAConflict() {
		idempotentWrite.execute("key-1", "anchors.create", "payload", HttpStatus.CREATED, String.class,
				() -> "first");

		assertThatThrownBy(() -> idempotentWrite.execute("key-1", "procedures.complete", "payload",
				HttpStatus.OK, String.class, () -> "second"))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

	@Test
	void aWriteWithoutABodyReplaysAnEmptyAnswer() {
		idempotentWrite.execute("key-1", "anchors.heartbeat", 42L, HttpStatus.NO_CONTENT, Void.class, () -> null);

		ResponseEntity<Void> replay = idempotentWrite.execute("key-1", "anchors.heartbeat", 42L,
				HttpStatus.NO_CONTENT, Void.class, () -> {
					throw new IllegalStateException("the write must not run again");
				});

		assertThat(replay.getStatusCode().value()).isEqualTo(204);
		assertThat(replay.getBody()).isNull();
	}
}
