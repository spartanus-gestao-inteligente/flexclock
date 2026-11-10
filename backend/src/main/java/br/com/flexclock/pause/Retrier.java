package br.com.flexclock.pause;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;

/**
 * Retry loop of the pause calls.
 *
 * <p>A transient OpenCode failure is retried at most three times, with a backoff of one, two and four
 * seconds; the first attempt plus three retries is the bound (decision 0046; requirement RF-042). When
 * every attempt fails the caller receives {@link ErrorCode#PAUSE_FAILED} and the pause flow records no
 * ghost pause.
 */
@Component
public class Retrier {

	private static final Duration[] BACKOFF = { Duration.ofSeconds(1), Duration.ofSeconds(2),
			Duration.ofSeconds(4) };

	private final Sleeper sleeper;

	public Retrier(Sleeper sleeper) {
		this.sleeper = sleeper;
	}

	/**
	 * Runs an action, retrying only a transient failure.
	 *
	 * @param action the OpenCode call
	 * @param <T> the result type
	 * @return the result of the first successful attempt
	 * @throws ApiException with {@link ErrorCode#PAUSE_FAILED} when every attempt failed
	 */
	public <T> T call(Supplier<T> action) {
		for (int attempt = 0; attempt <= BACKOFF.length; attempt++) {
			try {
				return action.get();
			}
			catch (TransientOpenCodeFailure failure) {
				if (attempt < BACKOFF.length) {
					sleeper.sleep(BACKOFF[attempt]);
				}
			}
		}
		throw new ApiException(ErrorCode.PAUSE_FAILED, "opencode",
				"The OpenCode call failed after three retries.");
	}

	/**
	 * Runs an action without a result.
	 *
	 * @param action the OpenCode call
	 */
	public void run(Runnable action) {
		call(() -> {
			action.run();
			return null;
		});
	}
}
