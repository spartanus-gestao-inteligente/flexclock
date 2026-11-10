package br.com.flexclock.pause;

import java.time.Duration;

import org.springframework.stereotype.Component;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;

/**
 * Pause between two attempts of an OpenCode call.
 *
 * <p>Separated from the retry loop so a test can prove the backoff without waiting: the real sleeper
 * blocks the calling thread, and the test replaces it with a recorder.
 */
@Component
public class Sleeper {

	/**
	 * Waits for the given duration.
	 *
	 * @param duration the time to wait
	 * @throws ApiException with {@link ErrorCode#PAUSE_FAILED} when the thread is interrupted, so an
	 * interrupted pause never leaves the procedure parked without a record
	 */
	public void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ApiException(ErrorCode.PAUSE_FAILED, "opencode",
					"The pause was interrupted before the OpenCode call completed.");
		}
	}
}
