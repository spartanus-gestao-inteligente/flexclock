package br.com.flexclock.pause;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Marks a pause as expired sixty minutes after it started.
 *
 * <p>The check is local and independent of the OpenCode server: it works while the server is down and
 * it never resumes the procedure (decision 0044; requirements RF-037, RF-044). The interval and the
 * first delay are configuration, so a test can drive the transition through the service instead of
 * waiting for the scheduler.
 */
@Component
public class PauseExpiryJob {

	private final PauseService pauseService;

	public PauseExpiryJob(PauseService pauseService) {
		this.pauseService = pauseService;
	}

	/**
	 * Runs one expiry check.
	 */
	@Scheduled(fixedDelayString = "${flexclock.pause.expiry-check-interval:60s}",
			initialDelayString = "${flexclock.pause.expiry-check-initial-delay:30s}")
	public void expireDuePauses() {
		pauseService.expireDuePauses();
	}
}
