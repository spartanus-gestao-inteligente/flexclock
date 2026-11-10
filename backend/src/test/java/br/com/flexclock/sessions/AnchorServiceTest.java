package br.com.flexclock.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.dto.AnchorRequest;
import br.com.flexclock.dto.AnchorResponse;
import br.com.flexclock.dto.AnchorSummary;
import br.com.flexclock.dto.ClockState;
import br.com.flexclock.enums.AnchorStatus;
import br.com.flexclock.enums.PauseStatus;
import br.com.flexclock.enums.ProcedureStatus;
import br.com.flexclock.model.AgentAnchor;
import br.com.flexclock.model.AgentPause;
import br.com.flexclock.model.Procedure;
import br.com.flexclock.opencode.OpenCodeSession;
import br.com.flexclock.opencode.OpenCodeSessionGateway;
import br.com.flexclock.opencode.OpenCodeUnavailableException;
import br.com.flexclock.procedure.ProcedureRepository;
import br.com.flexclock.procedure.ProcedureService;
import br.com.flexclock.support.MutableClock;
import br.com.flexclock.time.WorkSessionService;

/**
 * Proves the anchor lifecycle: one session, one anchor, one window, and the exact rule for every
 * rejection.
 *
 * <p>The OpenCode port is a stub of the platform workstream, so the test states only what the anchor
 * service must do with the three possible answers of the server: the session exists, the session is
 * gone or the server is unreachable (decision 0046).
 */
@ExtendWith(MockitoExtension.class)
class AnchorServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

	@Mock
	private AgentAnchorRepository anchorRepository;

	@Mock
	private ProcedureRepository procedureRepository;

	@Mock
	private PauseLookupRepository pauseLookupRepository;

	@Mock
	private WorkSessionService workSessionService;

	@Mock
	private OpenCodeSessionGateway openCodeGateway;

	private MutableClock clock;
	private AnchorService anchorService;

	@BeforeEach
	void setUp() {
		clock = MutableClock.at(NOW);
		ProcedureService procedureService = new ProcedureService(procedureRepository, clock);
		anchorService = new AnchorService(anchorRepository, procedureRepository, pauseLookupRepository,
				procedureService, workSessionService, openCodeGateway, clock);
	}

	@Test
	void anchoringCreatesTheAnchorAndTheActiveProcedureInOneCall() {
		givenSessionExists("ses_1", "Implement the clock", "/home/user/project");
		when(anchorRepository.findBySessionId("ses_1")).thenReturn(Optional.empty());
		when(procedureRepository.findBySessionId("ses_1")).thenReturn(Optional.empty());
		when(procedureRepository.save(any(Procedure.class))).thenAnswer(invocation -> {
			Procedure procedure = invocation.getArgument(0);
			procedure.setId(11L);
			return procedure;
		});
		when(anchorRepository.save(any(AgentAnchor.class))).thenAnswer(invocation -> {
			AgentAnchor anchor = invocation.getArgument(0);
			anchor.setId(7L);
			return anchor;
		});
		when(workSessionService.current(11L)).thenReturn(new ClockState(null, 0L, null, false, 11L));

		AnchorResponse response = anchorService.create(new AnchorRequest("ses_1"));

		assertThat(response.anchorId()).isEqualTo(7L);
		assertThat(response.procedureId()).isEqualTo(11L);
		assertThat(response.sessionId()).isEqualTo("ses_1");
		assertThat(response.title()).isEqualTo("Implement the clock");

		ArgumentCaptor<AgentAnchor> anchor = ArgumentCaptor.forClass(AgentAnchor.class);
		verify(anchorRepository).save(anchor.capture());
		assertThat(anchor.getValue().getStatus()).isEqualTo(AnchorStatus.ACTIVE);
		assertThat(anchor.getValue().getDirectory()).isEqualTo("/home/user/project");
		assertThat(anchor.getValue().getAnchoredAt()).isEqualTo(NOW);
		assertThat(anchor.getValue().getUpdatedAt()).isEqualTo(NOW);

		ArgumentCaptor<Procedure> procedure = ArgumentCaptor.forClass(Procedure.class);
		verify(procedureRepository).save(procedure.capture());
		assertThat(procedure.getValue().getStatus()).isEqualTo(ProcedureStatus.ACTIVE);
		assertThat(procedure.getValue().getStartedAt()).isEqualTo(NOW);
		assertThat(procedure.getValue().getEndedAt()).isNull();
	}

	@Test
	void anchoringTwiceIsRejectedBeforeTheServerIsCalled() {
		AgentAnchor existing = anchor(7L, "ses_1");
		when(anchorRepository.findBySessionId("ses_1")).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> anchorService.create(new AnchorRequest("ses_1")))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.ANCHOR_EXISTS);
		verifyNoInteractions(openCodeGateway);
	}

	@Test
	void aSubagentSessionIsNeverAnchored() {
		when(anchorRepository.findBySessionId("ses_child")).thenReturn(Optional.empty());
		when(openCodeGateway.findSession("ses_child"))
				.thenReturn(Optional.of(new OpenCodeSession("ses_child", "Child", "/tmp", "ses_parent")));

		assertThatThrownBy(() -> anchorService.create(new AnchorRequest("ses_child")))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.VALIDATION_ERROR));
		verify(procedureRepository, never()).save(any(Procedure.class));
	}

	@Test
	void aSessionTheServerDoesNotKnowIsGone() {
		when(anchorRepository.findBySessionId("ses_1")).thenReturn(Optional.empty());
		when(openCodeGateway.findSession("ses_1")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> anchorService.create(new AnchorRequest("ses_1")))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.SESSION_GONE));
	}

	@Test
	void anUnreachableServerIsNotASessionGone() {
		when(anchorRepository.findBySessionId("ses_1")).thenReturn(Optional.empty());
		when(openCodeGateway.findSession("ses_1"))
				.thenThrow(new OpenCodeUnavailableException("connection refused"));

		assertThatThrownBy(() -> anchorService.create(new AnchorRequest("ses_1")))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.OPENCODE_UNREACHABLE));
		verify(anchorRepository, never()).save(any(AgentAnchor.class));
	}

	@Test
	void anchoringAgainAfterTheCleanupLinksTheExistingProcedureWithoutRewritingItsEnd() {
		Instant endedAt = NOW.minus(Duration.ofHours(1));
		Procedure existing = procedure(11L, "ses_1", ProcedureStatus.COMPLETED, endedAt);
		givenSessionExists("ses_1", "Renamed session", "/home/user/project");
		when(anchorRepository.findBySessionId("ses_1")).thenReturn(Optional.empty());
		when(procedureRepository.findBySessionId("ses_1")).thenReturn(Optional.of(existing));
		when(procedureRepository.save(any(Procedure.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(anchorRepository.save(any(AgentAnchor.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(workSessionService.current(11L)).thenReturn(new ClockState(null, 0L, null, false, 11L));

		AnchorResponse response = anchorService.create(new AnchorRequest("ses_1"));

		assertThat(response.procedureId()).isEqualTo(11L);
		assertThat(existing.getTitle()).isEqualTo("Renamed session");
		assertThat(existing.getStatus()).isEqualTo(ProcedureStatus.COMPLETED);
		assertThat(existing.getEndedAt()).isEqualTo(endedAt);
	}

	@Test
	void theHeartbeatOnlyMovesTheLastSeenInstant() {
		AgentAnchor existing = anchor(7L, "ses_1");
		existing.setUpdatedAt(NOW.minus(Duration.ofSeconds(30)));
		when(anchorRepository.findById(7L)).thenReturn(Optional.of(existing));
		when(anchorRepository.save(any(AgentAnchor.class))).thenAnswer(invocation -> invocation.getArgument(0));

		anchorService.heartbeat(7L);

		assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
		assertThat(existing.getStatus()).isEqualTo(AnchorStatus.ACTIVE);
	}

	@Test
	void aHeartbeatOfAnUnknownAnchorIsRejected() {
		when(anchorRepository.findById(7L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> anchorService.heartbeat(7L))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.ANCHOR_NOT_FOUND));
	}

	@Test
	void theListJoinsTheAnchorTheProcedureAndTheLatestPause() {
		AgentAnchor anchor = anchor(7L, "ses_1");
		Procedure procedure = procedure(11L, "ses_1", ProcedureStatus.ACTIVE, null);
		AgentPause pause = org.mockito.Mockito.mock(AgentPause.class);
		when(pause.getProcedureId()).thenReturn(11L);
		when(pause.getStatus()).thenReturn(PauseStatus.PAUSED);
		when(pause.getPauseStart()).thenReturn(NOW.minus(Duration.ofSeconds(45)));

		when(anchorRepository.findAllByOrderByAnchoredAtAsc()).thenReturn(List.of(anchor));
		when(procedureRepository.findBySessionIdIn(List.of("ses_1"))).thenReturn(List.of(procedure));
		when(pauseLookupRepository.findByProcedureIdInOrderByPauseStartDesc(List.of(11L)))
				.thenReturn(List.of(pause));

		List<AnchorSummary> summaries = anchorService.list();

		assertThat(summaries).hasSize(1);
		AnchorSummary summary = summaries.getFirst();
		assertThat(summary.anchorStatus()).isEqualTo(AnchorStatus.ACTIVE);
		assertThat(summary.procedureStatus()).isEqualTo(ProcedureStatus.ACTIVE);
		assertThat(summary.pauseStatus()).isEqualTo(PauseStatus.PAUSED);
		assertThat(summary.pauseElapsedMs()).isEqualTo(45_000L);
	}

	private void givenSessionExists(String sessionId, String title, String directory) {
		when(openCodeGateway.findSession(sessionId))
				.thenReturn(Optional.of(new OpenCodeSession(sessionId, title, directory, null)));
	}

	private static AgentAnchor anchor(Long id, String sessionId) {
		AgentAnchor anchor = new AgentAnchor();
		anchor.setId(id);
		anchor.setSessionId(sessionId);
		anchor.setTitle("Implement the clock");
		anchor.setDirectory("/home/user/project");
		anchor.setStatus(AnchorStatus.ACTIVE);
		anchor.setAnchoredAt(NOW);
		anchor.setUpdatedAt(NOW);
		return anchor;
	}

	private static Procedure procedure(Long id, String sessionId, ProcedureStatus status, Instant endedAt) {
		Procedure procedure = new Procedure();
		procedure.setId(id);
		procedure.setSessionId(sessionId);
		procedure.setTitle("Implement the clock");
		procedure.setDirectory("/home/user/project");
		procedure.setStatus(status);
		procedure.setStartedAt(NOW.minus(Duration.ofHours(2)));
		procedure.setEndedAt(endedAt);
		procedure.setCreatedAt(NOW.minus(Duration.ofHours(2)));
		procedure.setUpdatedAt(NOW);
		return procedure;
	}
}
