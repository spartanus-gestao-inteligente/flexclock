package br.com.flexclock.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.flexclock.enums.AnchorStatus;
import br.com.flexclock.model.AgentAnchor;
import br.com.flexclock.opencode.OpenCodeSession;
import br.com.flexclock.opencode.OpenCodeSessionGateway;
import br.com.flexclock.opencode.OpenCodeUnavailableException;
import br.com.flexclock.support.MutableClock;

/**
 * Proves the two maintenance rules of the anchors.
 *
 * <p>A session reported as missing marks the anchor stale and a session that is back revives it; an
 * anchor without a heartbeat for longer than the time to live is deleted. An unreachable OpenCode
 * server never invalidates an anchor and never blocks the local cleanup (decision 0046; requirements
 * RF-017, RF-044, RN-008 and RN-009).
 */
@ExtendWith(MockitoExtension.class)
class AnchorMaintenanceServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

	@Mock
	private AgentAnchorRepository anchorRepository;

	@Mock
	private OpenCodeSessionGateway openCodeGateway;

	private MutableClock clock;
	private AnchorMaintenanceService maintenanceService;

	@BeforeEach
	void setUp() {
		clock = MutableClock.at(NOW);
		maintenanceService = new AnchorMaintenanceService(anchorRepository, openCodeGateway, clock,
				Duration.ofMinutes(2));
	}

	@Test
	void withoutAnchorsTheServerIsNeverCalled() {
		when(anchorRepository.count()).thenReturn(0L);

		Optional<Set<String>> alive = maintenanceService.readAliveSessionIds();

		assertThat(alive).contains(Set.of());
		verifyNoInteractions(openCodeGateway);
	}

	@Test
	void theAliveSessionsAreTheMainSessionsOfTheServer() {
		when(anchorRepository.count()).thenReturn(1L);
		when(openCodeGateway.listSessions()).thenReturn(List.of(
				new OpenCodeSession("ses_1", "Main", "/tmp", null),
				new OpenCodeSession("ses_child", "Child", "/tmp", "ses_1")));

		Optional<Set<String>> alive = maintenanceService.readAliveSessionIds();

		assertThat(alive).contains(Set.of("ses_1"));
	}

	@Test
	void anUnreachableServerSkipsTheStalenessRule() {
		when(anchorRepository.count()).thenReturn(1L);
		when(openCodeGateway.listSessions()).thenThrow(new OpenCodeUnavailableException("down"));

		assertThat(maintenanceService.readAliveSessionIds()).isEmpty();
		verify(anchorRepository, never()).save(any(AgentAnchor.class));
	}

	@Test
	void aSessionThatDisappearedMarksTheAnchorStaleAndKeepsTheCachedTitle() {
		AgentAnchor anchor = anchor(AnchorStatus.ACTIVE);
		when(anchorRepository.findAll()).thenReturn(List.of(anchor));
		when(anchorRepository.save(any(AgentAnchor.class))).thenAnswer(invocation -> invocation.getArgument(0));

		int changed = maintenanceService.applyStaleness(Set.of("ses_other"));

		assertThat(changed).isEqualTo(1);
		assertThat(anchor.getStatus()).isEqualTo(AnchorStatus.STALE);
		assertThat(anchor.getTitle()).isEqualTo("Implement the clock");
	}

	@Test
	void aSessionThatIsBackRevivesTheAnchor() {
		AgentAnchor anchor = anchor(AnchorStatus.STALE);
		when(anchorRepository.findAll()).thenReturn(List.of(anchor));
		when(anchorRepository.save(any(AgentAnchor.class))).thenAnswer(invocation -> invocation.getArgument(0));

		int changed = maintenanceService.applyStaleness(Set.of("ses_1"));

		assertThat(changed).isEqualTo(1);
		assertThat(anchor.getStatus()).isEqualTo(AnchorStatus.ACTIVE);
	}

	@Test
	void anAnchorThatIsAlreadyInTheRightStatusIsNotWritten() {
		AgentAnchor anchor = anchor(AnchorStatus.ACTIVE);
		when(anchorRepository.findAll()).thenReturn(List.of(anchor));

		assertThat(maintenanceService.applyStaleness(Set.of("ses_1"))).isZero();
		verify(anchorRepository, never()).save(any(AgentAnchor.class));
	}

	@Test
	void theOrphanCleanupUsesTheHeartbeatTimeToLive() {
		when(anchorRepository.deleteByUpdatedAtBefore(NOW.minus(Duration.ofMinutes(2)))).thenReturn(1);

		int removed = maintenanceService.removeOrphans();

		assertThat(removed).isEqualTo(1);
	}

	private static AgentAnchor anchor(AnchorStatus status) {
		AgentAnchor anchor = new AgentAnchor();
		anchor.setId(7L);
		anchor.setSessionId("ses_1");
		anchor.setTitle("Implement the clock");
		anchor.setDirectory("/home/user/project");
		anchor.setStatus(status);
		anchor.setAnchoredAt(NOW.minus(Duration.ofHours(1)));
		anchor.setUpdatedAt(NOW.minus(Duration.ofSeconds(30)));
		return anchor;
	}
}
