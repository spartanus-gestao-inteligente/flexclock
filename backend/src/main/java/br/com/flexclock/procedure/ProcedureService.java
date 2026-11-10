package br.com.flexclock.procedure;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.dto.ProcedureResponse;
import br.com.flexclock.enums.ProcedureStatus;
import br.com.flexclock.model.Procedure;

/**
 * Lifecycle of the procedure, the root that links the anchor, the pauses and the reports.
 *
 * <p>The procedure starts active when the session is anchored, becomes completed on a normal end and
 * becomes aborted when it ends without conclusion. The end instant is written once: a procedure that
 * already ended rejects any further transition, so the history can never be rewritten (decision 0042;
 * requirements RF-028, RF-029 and RN-016).
 *
 * <p>The abort is not decided here. The pause flow aborts the procedure only when the user cancels or
 * the session is gone; a pause that failed to apply keeps the procedure active (decision 0045).
 */
@Service
public class ProcedureService {

	private final ProcedureRepository procedureRepository;
	private final Clock clock;

	public ProcedureService(ProcedureRepository procedureRepository, Clock clock) {
		this.procedureRepository = procedureRepository;
		this.clock = clock;
	}

	/**
	 * Applies the final status requested by the event service.
	 *
	 * <p>Only {@code completed} and {@code aborted} are accepted; {@code active} is the creation status
	 * (architecture contract, section 8.2).
	 *
	 * @param procedureId the procedure identifier
	 * @param status the requested final status
	 * @return the procedure after the transition
	 * @throws ApiException with {@link ErrorCode#INVALID_PROCEDURE_TRANSITION} when the target is not a
	 * final status or the procedure already ended
	 */
	@Transactional
	public ProcedureResponse transition(Long procedureId, ProcedureStatus status) {
		if (status != ProcedureStatus.COMPLETED && status != ProcedureStatus.ABORTED) {
			throw new ApiException(ErrorCode.INVALID_PROCEDURE_TRANSITION, "status",
					"Only completed or aborted can be applied to a procedure.");
		}
		return finish(procedureId, status);
	}

	/**
	 * Completes a procedure that ended normally.
	 *
	 * @param procedureId the procedure identifier
	 * @return the procedure after the transition
	 */
	@Transactional
	public ProcedureResponse complete(Long procedureId) {
		return finish(procedureId, ProcedureStatus.COMPLETED);
	}

	/**
	 * Aborts a procedure that ended without conclusion.
	 *
	 * @param procedureId the procedure identifier
	 * @return the procedure after the transition
	 */
	@Transactional
	public ProcedureResponse abort(Long procedureId) {
		return finish(procedureId, ProcedureStatus.ABORTED);
	}

	/**
	 * Lists the procedures, optionally filtered by status.
	 *
	 * @param status the status to filter by, null to list every procedure
	 * @return the procedures, oldest first
	 */
	@Transactional(readOnly = true)
	public List<ProcedureResponse> list(ProcedureStatus status) {
		List<Procedure> procedures = status == null ? procedureRepository.findAllByOrderByStartedAtAsc()
				: procedureRepository.findByStatusOrderByStartedAtAsc(status);
		return procedures.stream().map(ProcedureService::toResponse).toList();
	}

	/**
	 * Creates the procedure of a session, active from the anchoring instant.
	 *
	 * <p>Called by the anchor service inside its transaction, so the anchor and the procedure always
	 * exist together (decision 0042; requirement RN-011).
	 *
	 * @param sessionId the OpenCode session identifier
	 * @param title the cached session title
	 * @param directory the working directory of the session
	 * @return the new procedure, not yet persisted by the caller
	 */
	public Procedure newActiveProcedure(String sessionId, String title, String directory) {
		Instant now = clock.instant();
		Procedure procedure = new Procedure();
		procedure.setSessionId(sessionId);
		procedure.setTitle(title);
		procedure.setDirectory(directory);
		procedure.setStatus(ProcedureStatus.ACTIVE);
		procedure.setStartedAt(now);
		procedure.setCreatedAt(now);
		procedure.setUpdatedAt(now);
		return procedure;
	}

	private ProcedureResponse finish(Long procedureId, ProcedureStatus status) {
		Procedure procedure = procedureRepository.findById(procedureId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "procedureId",
						"There is no procedure with id " + procedureId + "."));
		if (procedure.getStatus() != ProcedureStatus.ACTIVE) {
			throw new ApiException(ErrorCode.INVALID_PROCEDURE_TRANSITION, "status",
					"The procedure is already " + procedure.getStatus().wireValue() + " and its end instant is final.");
		}

		Instant now = clock.instant();
		procedure.setStatus(status);
		procedure.setEndedAt(now);
		procedure.setUpdatedAt(now);
		procedureRepository.save(procedure);
		return toResponse(procedure);
	}

	private static ProcedureResponse toResponse(Procedure procedure) {
		return new ProcedureResponse(procedure.getId(), procedure.getSessionId(), procedure.getTitle(),
				procedure.getDirectory(), procedure.getStatus(), procedure.getStartedAt(), procedure.getEndedAt(),
				procedure.getCreatedAt(), procedure.getUpdatedAt());
	}
}
