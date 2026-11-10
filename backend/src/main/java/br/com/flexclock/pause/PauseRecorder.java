package br.com.flexclock.pause;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.enums.PauseReason;
import br.com.flexclock.enums.PauseStatus;
import br.com.flexclock.model.AgentPause;
import br.com.flexclock.procedure.ProcedureStateWriter;

/**
 * Transactional writes of the pause state machine.
 *
 * <p>The allowed transitions are exactly the ones frozen by decision 0045: {@code paused} to
 * {@code resumed}, {@code expired} or {@code aborted}, and {@code expired} to {@code resumed} or
 * {@code aborted}. Every other transition is rejected with
 * {@link ErrorCode#INVALID_PAUSE_TRANSITION} and the transaction rolls back, so a failed pause never
 * leaves a partial state (requirements RF-039, RF-155).
 *
 * <p>The pause row and the procedure row are written in the same transaction. A user cancel or a
 * session that disappeared ends the procedure as aborted; a pause failure keeps the procedure active
 * (decision 0045; requirement RF-038).
 */
@Service
public class PauseRecorder {

	private static final Set<PauseStatus> OPEN = EnumSet.of(PauseStatus.PAUSED, PauseStatus.EXPIRED);

	private final AgentPauseRepository pauses;
	private final ProcedureStateWriter procedureState;
	private final Duration timeout;

	public PauseRecorder(AgentPauseRepository pauses, ProcedureStateWriter procedureState,
			@Value("${flexclock.pause.timeout:60m}") Duration timeout) {
		this.pauses = pauses;
		this.procedureState = procedureState;
		this.timeout = timeout;
	}

	/**
	 * Records a pause that was just applied.
	 *
	 * @param procedureId the procedure identifier
	 * @param sessionId the OpenCode session identifier
	 * @param pauseStart the instant the pause started
	 * @return the persisted pause, with status paused
	 */
	@Transactional
	public AgentPause open(Long procedureId, String sessionId, Instant pauseStart) {
		return pauses.saveAndFlush(AgentPause.open(procedureId, sessionId, pauseStart));
	}

	/**
	 * Resumes a pause from the paused or the expired state.
	 *
	 * @param pauseId the pause identifier
	 * @param resumedAt the instant the continuation answer was observed
	 * @return the persisted pause, with status resumed
	 * @throws ApiException when the transition is not allowed
	 */
	@Transactional
	public AgentPause resume(Long pauseId, Instant resumedAt) {
		AgentPause pause = requirePause(pauseId);
		requireOpen(pause, "resume");
		Instant end = notBeforeStart(pause, resumedAt);
		pause.setResumeAt(end);
		pause.setDurationMs(Duration.between(pause.getPauseStart(), end).toMillis());
		pause.setStatus(PauseStatus.RESUMED);
		procedureState.touchActive(pause.getProcedureId(), end);
		return pauses.save(pause);
	}

	/**
	 * Ends a pause without resuming the procedure.
	 *
	 * @param pauseId the pause identifier
	 * @param reason the reason of the abort
	 * @param abortedAt the instant the pause ended
	 * @return the persisted pause, with status aborted
	 * @throws ApiException when the transition is not allowed
	 */
	@Transactional
	public AgentPause abort(Long pauseId, PauseReason reason, Instant abortedAt) {
		AgentPause pause = requirePause(pauseId);
		requireOpen(pause, "abort");
		Instant end = notBeforeStart(pause, abortedAt);
		pause.setResumeAt(end);
		pause.setDurationMs(Duration.between(pause.getPauseStart(), end).toMillis());
		pause.setStatus(PauseStatus.ABORTED);
		pause.setReason(reason);
		if (reason == PauseReason.USER_CANCEL || reason == PauseReason.SESSION_GONE) {
			procedureState.abort(pause.getProcedureId(), end);
		}
		return pauses.save(pause);
	}

	/**
	 * Marks every pause whose 60 minute limit is due as expired.
	 *
	 * <p>Expiry never resumes the procedure and never sends a turn to the agent: the pause stays
	 * resolvable by a later answer (decision 0035; requirement RF-037).
	 *
	 * @param now the instant of the check
	 * @return the number of pauses that changed state
	 */
	@Transactional
	public int expireDue(Instant now) {
		Instant threshold = now.minus(timeout);
		List<AgentPause> due = pauses.findByStatusAndPauseStartLessThanEqual(PauseStatus.PAUSED, threshold);
		for (AgentPause pause : due) {
			Instant expiredAt = pause.getPauseStart().plus(timeout);
			pause.setStatus(PauseStatus.EXPIRED);
			pause.setDurationMs(Duration.between(pause.getPauseStart(), expiredAt).toMillis());
		}
		pauses.saveAll(due);
		return due.size();
	}

	/**
	 * Reports whether a status is unresolved.
	 *
	 * @param status the pause status
	 * @return true for paused and expired
	 */
	public static boolean isOpen(PauseStatus status) {
		return OPEN.contains(status);
	}

	private AgentPause requirePause(Long pauseId) {
		return pauses.findById(pauseId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "pauseId",
						"The pause does not exist."));
	}

	private void requireOpen(AgentPause pause, String transition) {
		if (!isOpen(pause.getStatus())) {
			throw new ApiException(ErrorCode.INVALID_PAUSE_TRANSITION, "pauseId",
					"The pause cannot " + transition + " from the status " + pause.getStatus().wireValue() + ".");
		}
	}

	private Instant notBeforeStart(AgentPause pause, Instant instant) {
		return instant.isBefore(pause.getPauseStart()) ? pause.getPauseStart() : instant;
	}
}
