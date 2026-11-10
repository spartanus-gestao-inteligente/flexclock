package br.com.flexclock.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.dto.ProcedureResponse;
import br.com.flexclock.enums.ProcedureStatus;
import br.com.flexclock.model.Procedure;
import br.com.flexclock.support.MutableClock;

/**
 * Proves the procedure lifecycle: active to completed, active to aborted, and nothing else.
 *
 * <p>The end instant is written once, so a procedure that already ended rejects every further
 * transition. The abort semantics come from decision 0045: the pause flow aborts only when the user
 * cancels or the session is gone; a pause that failed keeps the procedure active and never calls this
 * service.
 */
@ExtendWith(MockitoExtension.class)
class ProcedureServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

	@Mock
	private ProcedureRepository procedureRepository;

	private ProcedureService procedureService;

	@BeforeEach
	void setUp() {
		procedureService = new ProcedureService(procedureRepository, MutableClock.at(NOW));
	}

	@Test
	void anActiveProcedureBecomesCompletedWithOneEndInstant() {
		Procedure procedure = procedure(ProcedureStatus.ACTIVE, null);
		when(procedureRepository.findById(11L)).thenReturn(Optional.of(procedure));
		when(procedureRepository.save(any(Procedure.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ProcedureResponse response = procedureService.complete(11L);

		assertThat(response.status()).isEqualTo(ProcedureStatus.COMPLETED);
		assertThat(response.endedAt()).isEqualTo(NOW);
		assertThat(procedure.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void anActiveProcedureBecomesAbortedWithOneEndInstant() {
		Procedure procedure = procedure(ProcedureStatus.ACTIVE, null);
		when(procedureRepository.findById(11L)).thenReturn(Optional.of(procedure));
		when(procedureRepository.save(any(Procedure.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ProcedureResponse response = procedureService.abort(11L);

		assertThat(response.status()).isEqualTo(ProcedureStatus.ABORTED);
		assertThat(response.endedAt()).isEqualTo(NOW);
	}

	@Test
	void aCompletedProcedureRejectsASecondCompletion() {
		Instant endedAt = NOW.minus(Duration.ofHours(1));
		when(procedureRepository.findById(11L)).thenReturn(Optional.of(procedure(ProcedureStatus.COMPLETED, endedAt)));

		assertThatThrownBy(() -> procedureService.complete(11L))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.INVALID_PROCEDURE_TRANSITION));
		verify(procedureRepository, never()).save(any(Procedure.class));
	}

	@Test
	void anAbortedProcedureRejectsACompletion() {
		when(procedureRepository.findById(11L))
				.thenReturn(Optional.of(procedure(ProcedureStatus.ABORTED, NOW.minus(Duration.ofMinutes(5)))));

		assertThatThrownBy(() -> procedureService.complete(11L))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.INVALID_PROCEDURE_TRANSITION));
	}

	@Test
	void theTransitionOperationAppliesOnlyFinalStatuses() {
		assertThatThrownBy(() -> procedureService.transition(11L, ProcedureStatus.ACTIVE))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.INVALID_PROCEDURE_TRANSITION));
		verify(procedureRepository, never()).findById(any());
	}

	@Test
	void theTransitionOperationCompletesAnActiveProcedure() {
		Procedure procedure = procedure(ProcedureStatus.ACTIVE, null);
		when(procedureRepository.findById(11L)).thenReturn(Optional.of(procedure));
		when(procedureRepository.save(any(Procedure.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ProcedureResponse response = procedureService.transition(11L, ProcedureStatus.COMPLETED);

		assertThat(response.status()).isEqualTo(ProcedureStatus.COMPLETED);
	}

	@Test
	void anUnknownProcedureIsNotFound() {
		when(procedureRepository.findById(11L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> procedureService.complete(11L))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void theHistoryIsFilteredByStatus() {
		when(procedureRepository.findByStatusOrderByStartedAtAsc(ProcedureStatus.ABORTED))
				.thenReturn(List.of(procedure(ProcedureStatus.ABORTED, NOW.minus(Duration.ofMinutes(5)))));

		List<ProcedureResponse> procedures = procedureService.list(ProcedureStatus.ABORTED);

		assertThat(procedures).hasSize(1);
		assertThat(procedures.getFirst().status()).isEqualTo(ProcedureStatus.ABORTED);
	}

	@Test
	void aNewProcedureStartsActiveAtTheAnchoringInstant() {
		Procedure procedure = procedureService.newActiveProcedure("ses_1", "Title", "/home/user/project");

		assertThat(procedure.getSessionId()).isEqualTo("ses_1");
		assertThat(procedure.getTitle()).isEqualTo("Title");
		assertThat(procedure.getStatus()).isEqualTo(ProcedureStatus.ACTIVE);
		assertThat(procedure.getStartedAt()).isEqualTo(NOW);
		assertThat(procedure.getCreatedAt()).isEqualTo(NOW);
		assertThat(procedure.getEndedAt()).isNull();
	}

	private static Procedure procedure(ProcedureStatus status, Instant endedAt) {
		Procedure procedure = new Procedure();
		procedure.setId(11L);
		procedure.setSessionId("ses_1");
		procedure.setTitle("Implement the clock");
		procedure.setDirectory("/home/user/project");
		procedure.setStatus(status);
		procedure.setStartedAt(NOW.minus(Duration.ofHours(2)));
		procedure.setEndedAt(endedAt);
		procedure.setCreatedAt(NOW.minus(Duration.ofHours(2)));
		procedure.setUpdatedAt(NOW.minus(Duration.ofHours(2)));
		return procedure;
	}
}
