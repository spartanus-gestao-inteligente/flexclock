package br.com.flexclock.pause;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.model.AgentPause;

/**
 * Detects the continuation answer of an open pause and resumes it.
 *
 * <p>The detection is the polling fallback allowed by decision 0034: it reads the messages of the
 * session and reports a user message created after the pause start. The detector never sends a turn,
 * never resumes by itself and never touches a pause whose session has no answer; the resume is the same
 * explicit transition the REST endpoint applies (requirement RF-036).
 *
 * <p>The poller is enabled by default and disabled in the tests that drive the transition directly.
 */
@Component
public class PauseContinuationPoller {

	private static final Logger log = LoggerFactory.getLogger(PauseContinuationPoller.class);

	private final PauseService pauseService;
	private final boolean enabled;

	public PauseContinuationPoller(PauseService pauseService,
			@Value("${flexclock.pause.continuation-poll-enabled:true}") boolean enabled) {
		this.pauseService = pauseService;
		this.enabled = enabled;
	}

	/**
	 * Runs one detection pass over the open pauses.
	 */
	@Scheduled(fixedDelayString = "${flexclock.pause.continuation-poll-interval:15s}",
			initialDelayString = "${flexclock.pause.continuation-poll-initial-delay:20s}")
	public void poll() {
		if (!enabled) {
			return;
		}
		for (AgentPause pause : pauseService.openPauses()) {
			pauseService.continuationAnswer(pause).ifPresent(answer -> resume(pause, answer));
		}
	}

	private void resume(AgentPause pause, Instant answer) {
		try {
			pauseService.resume(pause.getId(), answer);
			log.info("Pause {} resumed after the continuation answer", pause.getId());
		}
		catch (ApiException exception) {
			log.debug("Pause {} could not be resumed: {}", pause.getId(), exception.errorCode());
		}
	}
}
