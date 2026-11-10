package br.com.flexclock.sessions;

import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Local timer of the anchor maintenance.
 *
 * <p>The timer is local and never depends on the OpenCode server: the cleanup of a dead window runs
 * even when the server is down (requirements RF-016, RF-044 and RN-009). The two steps are called
 * through the service proxy so each one runs in its own transaction and the HTTP read never holds a
 * database connection.
 */
@Component
public class AnchorMaintenanceJob {

	private static final Logger log = LoggerFactory.getLogger(AnchorMaintenanceJob.class);

	private final AnchorMaintenanceService maintenanceService;

	public AnchorMaintenanceJob(AnchorMaintenanceService maintenanceService) {
		this.maintenanceService = maintenanceService;
	}

	/**
	 * Applies the staleness rule and the orphan cleanup once per interval.
	 */
	@Scheduled(fixedDelayString = "${flexclock.anchor.maintenance-interval-ms:30000}",
			initialDelayString = "${flexclock.anchor.maintenance-initial-delay-ms:30000}")
	public void maintain() {
		Optional<Set<String>> aliveSessionIds = maintenanceService.readAliveSessionIds();
		aliveSessionIds.ifPresent(maintenanceService::applyStaleness);
		int removed = maintenanceService.removeOrphans();
		if (aliveSessionIds.isPresent() || removed > 0) {
			log.debug("Anchor maintenance finished, stalenessApplied={}, orphansRemoved={}",
					aliveSessionIds.isPresent(), removed);
		}
	}
}
