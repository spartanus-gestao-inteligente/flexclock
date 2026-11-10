package br.com.flexclock.pause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;

/**
 * Proves the retry rule of the pause calls: three retries with a one, two and four second backoff, and
 * a stable failure when every attempt fails (decision 0046; requirement RF-042).
 */
class RetrierTest {

	@Test
	void aTransientFailureIsRetriedThreeTimesWithTheContractBackoff() {
		RecordingSleeper sleeper = new RecordingSleeper();
		Retrier retrier = new Retrier(sleeper);
		AtomicInteger calls = new AtomicInteger();

		String result = retrier.call(() -> {
			if (calls.incrementAndGet() < 4) {
				throw new TransientOpenCodeFailure("connection reset", null);
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(calls).hasValue(4);
		assertThat(sleeper.delays).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
	}

	@Test
	void anExhaustedRetryBecomesAStablePauseFailure() {
		RecordingSleeper sleeper = new RecordingSleeper();
		Retrier retrier = new Retrier(sleeper);
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> retrier.call(() -> {
			calls.incrementAndGet();
			throw new TransientOpenCodeFailure("timeout", null);
		})).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAUSE_FAILED);

		assertThat(calls).hasValue(4);
	}

	@Test
	void aFinalFailureIsNotRetried() {
		RecordingSleeper sleeper = new RecordingSleeper();
		Retrier retrier = new Retrier(sleeper);
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> retrier.call(() -> {
			calls.incrementAndGet();
			throw new ApiException(ErrorCode.SESSION_GONE, "sessionId", "gone");
		})).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_GONE);

		assertThat(calls).hasValue(1);
		assertThat(sleeper.delays).isEmpty();
	}

	private static final class RecordingSleeper extends Sleeper {

		private final List<Duration> delays = new ArrayList<>();

		@Override
		public void sleep(Duration duration) {
			delays.add(duration);
		}
	}
}
