package br.com.flexclock.opencode;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded retry for the calls to the local OpenCode server.
 *
 * <p>Decision 0046 fixes the shape: three attempts with an exponential backoff at one, two and four
 * seconds, for transient failures only. A failure that is not transient (a refusal of the server, a
 * session that no longer exists, a malformed answer) is raised immediately, because repeating it would
 * only delay the message the user has to read.
 *
 * <p>The sleeper is injected so the tests prove the attempt count and the backoff without waiting for
 * real seconds, and so the production bean can stay at the values of the decision.
 */
public final class BoundedRetry {

	private static final Logger log = LoggerFactory.getLogger(BoundedRetry.class);

	/** Number of attempts of the decision, including the first one. */
	public static final int DEFAULT_MAX_ATTEMPTS = 3;

	/** First backoff of the decision. */
	public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);

	/** Multiplier applied after each attempt, which produces one, two and four seconds. */
	public static final double DEFAULT_MULTIPLIER = 2.0d;

	/**
	 * Result of a bounded call.
	 *
	 * @param value the value produced by the successful attempt
	 * @param attempts the number of attempts that were spent
	 * @param <T> the type of the value
	 */
	public record Attempt<T>(T value, int attempts) {
	}

	/**
	 * Waits between two attempts. Injected so a test never waits for real seconds.
	 */
	@FunctionalInterface
	public interface Sleeper {

		/**
		 * Waits for the given duration.
		 *
		 * @param duration the duration to wait
		 */
		void sleep(Duration duration);
	}

	private final int maxAttempts;
	private final Duration initialBackoff;
	private final double multiplier;
	private final Sleeper sleeper;

	/**
	 * Creates the policy.
	 *
	 * @param maxAttempts the number of attempts, at least one
	 * @param initialBackoff the wait before the second attempt
	 * @param multiplier the factor applied after each attempt
	 * @param sleeper the waiter used between attempts
	 */
	public BoundedRetry(int maxAttempts, Duration initialBackoff, double multiplier, Sleeper sleeper) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("At least one attempt is required.");
		}
		if (initialBackoff == null || initialBackoff.isNegative()) {
			throw new IllegalArgumentException("The initial backoff must not be negative.");
		}
		if (multiplier < 1.0d) {
			throw new IllegalArgumentException("The backoff multiplier must be at least 1.");
		}
		this.maxAttempts = maxAttempts;
		this.initialBackoff = initialBackoff;
		this.multiplier = multiplier;
		this.sleeper = sleeper;
	}

	/**
	 * Returns the configured number of attempts.
	 *
	 * @return the number of attempts
	 */
	public int maxAttempts() {
		return maxAttempts;
	}

	/**
	 * Executes an action with the bounded retry.
	 *
	 * @param operation the English name of the operation, used in the local log
	 * @param action the action to execute
	 * @param <T> the type of the value
	 * @return the value of the successful attempt and the number of attempts spent
	 * @throws TransientOpenCodeException when every attempt failed with a transient failure
	 */
	public <T> Attempt<T> execute(String operation, Supplier<T> action) {
		TransientOpenCodeException lastFailure = null;
		Duration backoff = initialBackoff;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return new Attempt<>(action.get(), attempt);
			}
			catch (TransientOpenCodeException exception) {
				lastFailure = exception;
				if (attempt == maxAttempts) {
					break;
				}
				log.debug("OpenCode operation {} failed on attempt {} of {}, retrying in {}", operation, attempt,
						maxAttempts, backoff);
				sleeper.sleep(backoff);
				backoff = Duration.ofMillis(Math.max(1L, (long) (backoff.toMillis() * multiplier)));
			}
		}
		throw lastFailure;
	}

	/**
	 * Failure of a call to the OpenCode server that is worth repeating.
	 *
	 * <p>A final state, such as a session that no longer exists, is never reported through this type:
	 * it is raised as an {@link br.com.flexclock.common.ApiException} so it is not retried.
	 */
	public static class TransientOpenCodeException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		/**
		 * Creates the failure.
		 *
		 * @param message the English reason
		 * @param cause the transport cause, may be null
		 */
		public TransientOpenCodeException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
