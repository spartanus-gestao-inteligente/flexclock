package br.com.flexclock.sessions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.flexclock.enums.AnchorStatus;
import br.com.flexclock.model.AgentAnchor;
import br.com.flexclock.opencode.OpenCodeSession;
import br.com.flexclock.opencode.OpenCodeSessionGateway;
import br.com.flexclock.opencode.OpenCodeUnavailableException;

/**
 * Keeps the anchors honest: staleness when a session disappears and cleanup when a window dies.
 *
 * <p>Two independent rules run on the local timer:
 *
 * <ol>
 * <li>A session reported as missing marks the anchor stale and keeps the cached title; the pause is
 * disabled for that window and is enabled again when the session exists once more (requirement
 * RF-017).</li>
 * <li>An anchor without a heartbeat for more than two minutes is deleted, so a crashed window can
 * never block a session forever (requirements RF-016 and RN-009). The procedure, the pauses and the
 * report data survive the deletion (requirement RF-018, RN-010).</li>
 * </ol>
 *
 * <p>An unreachable OpenCode server never invalidates an anchor: the staleness rule is skipped and
 * only the local cleanup runs, because the local timer never depends on the server (decision 0046;
 * requirements RF-044 and RN-008).
 */
@Service
public class AnchorMaintenanceService {

	private static final Logger log = LoggerFactory.getLogger(AnchorMaintenanceService.class);

	private final AgentAnchorRepository anchorRepository;
	private final OpenCodeSessionGateway openCodeGateway;
	private final Clock clock;
	private final Duration staleAfter;

	public AnchorMaintenanceService(AgentAnchorRepository anchorRepository, OpenCodeSessionGateway openCodeGateway,
			Clock clock, @Value("${flexclock.anchor.stale-after:2m}") Duration staleAfter) {
		this.anchorRepository = anchorRepository;
		this.openCodeGateway = openCodeGateway;
		this.clock = clock;
		this.staleAfter = staleAfter;
	}

	/**
	 * Reads the identifiers of the sessions the OpenCode server still knows.
	 *
	 * @return the identifiers, or an empty optional when the server cannot answer and the staleness rule
	 * must be skipped
	 */
	public Optional<Set<String>> readAliveSessionIds() {
		if (anchorRepository.count() == 0) {
			return Optional.of(Set.of());
		}
		try {
			return Optional.of(openCodeGateway.listSessions().stream()
					.filter(OpenCodeSession::isMain)
					.map(OpenCodeSession::sessionId)
					.collect(Collectors.toUnmodifiableSet()));
		}
		catch (OpenCodeUnavailableException exception) {
			log.debug("Skipping the anchor staleness rule because the OpenCode server is unreachable");
			return Optional.empty();
		}
	}

	/**
	 * Marks the anchors whose session disappeared as stale and revives the ones whose session is back.
	 *
	 * @param aliveSessionIds the identifiers of the sessions the server still knows
	 * @return the number of anchors whose status changed
	 */
	@Transactional
	public int applyStaleness(Set<String> aliveSessionIds) {
		List<AgentAnchor> anchors = anchorRepository.findAll();
		int changed = 0;
		for (AgentAnchor anchor : anchors) {
			AnchorStatus target = aliveSessionIds.contains(anchor.getSessionId()) ? AnchorStatus.ACTIVE
					: AnchorStatus.STALE;
			if (anchor.getStatus() != target) {
				anchor.setStatus(target);
				anchorRepository.save(anchor);
				changed++;
			}
		}
		return changed;
	}

	/**
	 * Deletes the anchors without a heartbeat for longer than the configured time to live.
	 *
	 * @return the number of deleted anchors
	 */
	@Transactional
	public int removeOrphans() {
		Instant threshold = clock.instant().minus(staleAfter);
		return anchorRepository.deleteByUpdatedAtBefore(threshold);
	}
}
