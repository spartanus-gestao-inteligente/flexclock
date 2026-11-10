package br.com.flexclock.opencode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Proves the bounded retry of decision 0046: three attempts, one then two then four seconds, and only
 * for transient failures.
 */
class BoundedRetryTest {

	private final List<Duration> waits = new ArrayList<>();

	private final BoundedRetry retry = new BoundedRetry(3, Duration.ofSeconds(1), 2.0d, waits::add);

	@Test
	void aSuccessfulCallIsNotRepeated() {
		AtomicInteger calls = new AtomicInteger();

		BoundedRetry.Attempt<String> attempt = retry.execute("test", () -> {
			calls.incrementAndGet();
			return "ok";
		});

		assertThat(attempt.value()).isEqualTo("ok");
		assertThat(attempt.attempts()).isEqualTo(1);
		assertThat(calls).hasValue(1);
		assertThat(waits).isEmpty();
	}

	@Test
	void aTransientFailureIsRepeatedWithTheBackoffOfTheDecision() {
		AtomicInteger calls = new AtomicInteger();

		BoundedRetry.Attempt<String> attempt = retry.execute("test", () -> {
			if (calls.incrementAndGet() < 3) {
				throw new BoundedRetry.TransientOpenCodeException("not yet", null);
			}
			return "ok";
		});

		assertThat(attempt.attempts()).isEqualTo(3);
		assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void theCallGivesUpAfterTheLastAttempt() {
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> retry.execute("test", () -> {
			calls.incrementAndGet();
			throw new BoundedRetry.TransientOpenCodeException("still down", null);
		})).isInstanceOf(BoundedRetry.TransientOpenCodeException.class);

		assertThat(calls).hasValue(3);
		assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void aFinalFailureIsNeverRepeated() {
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> retry.execute("test", () -> {
			calls.incrementAndGet();
			throw new IllegalStateException("final");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(calls).hasValue(1);
	}

	@Test
	void anInvalidPolicyIsRefused() {
		assertThatThrownBy(() -> new BoundedRetry(0, Duration.ofSeconds(1), 2.0d, wait -> {
		})).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedRetry(3, Duration.ofSeconds(-1), 2.0d, wait -> {
		})).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedRetry(3, Duration.ofSeconds(1), 0.5d, wait -> {
		})).isInstanceOf(IllegalArgumentException.class);
	}
}
