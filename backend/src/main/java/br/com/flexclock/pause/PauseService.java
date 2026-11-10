package br.com.flexclock.pause;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.dto.PauseHistoryItem;
import br.com.flexclock.dto.PauseNotification;
import br.com.flexclock.dto.PauseResponse;
import br.com.flexclock.dto.PauseStateResponse;
import br.com.flexclock.enums.PauseReason;
import br.com.flexclock.enums.PauseStatus;
import br.com.flexclock.model.AgentPause;
import br.com.flexclock.opencode.OpenCodePauseOutcome;
import br.com.flexclock.opencode.OpenCodePauseResult;
import br.com.flexclock.opencode.OpenCodePort;

/**
 * Pause flow of a procedure.
 *
 * <p>A pause is always bound to the session of the requesting window anchor; the client never chooses
 * the session (requirement RF-041). Before applying a pause the flow checks the anchor and the anchor
 * freshness, and the OpenCode transport then checks the server health, aborts the running session and
 * admits the direction turn. The flow refuses with the specific stable code when any check fails,
 * without writing a record (requirements RF-032, RF-033).
 *
 * <p>The pause row is written only after the transport confirmed the abort; when the direction turn
 * then failed, that row is marked aborted with the reason pause_failed, so no ghost pause survives
 * (requirements RF-034, RF-042). Every call resolves the OpenCode endpoint from the settings table, so
 * there is a single transport and a pause can never target a server different from the one the session
 * listing came from (decision 0046).
 *
 * <p>The 60 minute limit is computed locally from the pause start. Expiry marks the record and
 * publishes the notification contract, and a resume is always an explicit transition (decision 0035;
 * requirements RF-037, RF-044).
 */
@Service
public class PauseService {

	private static final Logger log = LoggerFactory.getLogger(PauseService.class);

	private static final Set<PauseStatus> OPEN = EnumSet.of(PauseStatus.PAUSED, PauseStatus.EXPIRED);

	/** Default bound of the pause history. */
	static final int DEFAULT_HISTORY_LIMIT = 50;

	/** Largest accepted history bound. */
	static final int MAX_HISTORY_LIMIT = 200;

	/**
	 * The single direction turn of a pause.
	 *
	 * <p>The question itself is the one fixed by decision 0034 and it is shown to the owner in the
	 * OpenCode chat, so it stays in Portuguese; the instruction around it and every identifier of the
	 * backend stay in English.
	 */
	static final String DIRECTION_PROMPT = """
			The pause was applied. Before continuing, verify the current procedure state and call the \
			native question tool with the question "Deseja continuar o procedimento?" and the SIM option. \
			Do not perform any other action before the answer.""".strip();

	private final PauseRecorder recorder;
	private final AgentPauseRepository pauses;
	private final PauseLookup lookup;
	private final OpenCodePort openCode;
	private final Clock clock;
	private final Duration timeout;
	private final Duration notificationRepeat;

	public PauseService(PauseRecorder recorder, AgentPauseRepository pauses, PauseLookup lookup,
			OpenCodePort openCode, Clock clock, @Value("${flexclock.pause.timeout:60m}") Duration timeout,
			@Value("${flexclock.pause.notification-repeat:15m}") Duration notificationRepeat) {
		this.recorder = recorder;
		this.pauses = pauses;
		this.lookup = lookup;
		this.openCode = openCode;
		this.clock = clock;
		this.timeout = timeout;
		this.notificationRepeat = notificationRepeat;
	}

	/**
	 * Applies a pause to the session of an anchor.
	 *
	 * @param anchorId the anchor of the requesting window
	 * @return the applied pause
	 * @throws ApiException with the specific code of the failed check
	 */
	public PauseResponse pause(Long anchorId) {
		PauseLookup.AnchorSnapshot anchor = lookup.requirePausableAnchor(anchorId);
		if (anchor.procedureId() == null) {
			throw new ApiException(ErrorCode.NO_SESSION_ANCHORED, "anchorId",
					"No procedure is linked to the anchored session.");
		}
		if (pauses.findFirstByProcedureIdAndStatusInOrderByPauseStartDesc(anchor.procedureId(), OPEN).isPresent()) {
			throw new ApiException(ErrorCode.INVALID_PAUSE_TRANSITION, "anchorId",
					"The procedure already has an open pause.");
		}

		OpenCodePauseResult attempt = openCode.pause(anchor.sessionId(), DIRECTION_PROMPT);
		if (!attempt.abortApplied()) {
			throw refusal(attempt);
		}

		Instant pauseStart = clock.instant();
		AgentPause pause;
		try {
			pause = recorder.open(anchor.procedureId(), anchor.sessionId(), pauseStart);
		}
		catch (DataIntegrityViolationException exception) {
			throw new ApiException(ErrorCode.INVALID_PAUSE_TRANSITION, "anchorId",
					"The procedure already has an open pause.");
		}

		if (!attempt.directionTurnSent()) {
			PauseReason reason = attempt.outcome() == OpenCodePauseOutcome.SESSION_GONE
					? PauseReason.SESSION_GONE
					: PauseReason.PAUSE_FAILED;
			recorder.abort(pause.getId(), reason, clock.instant());
			throw refusal(attempt);
		}
		return new PauseResponse(pause.getStatus(), pause.getPauseStart());
	}

	/**
	 * Resumes a pause because the continuation answer was observed.
	 *
	 * @param pauseId the pause identifier
	 * @return the resumed pause
	 */
	public PauseResponse resume(Long pauseId) {
		return resume(pauseId, clock.instant());
	}

	/**
	 * Resumes a pause at an explicit instant, used by the continuation detector.
	 *
	 * @param pauseId the pause identifier
	 * @param resumedAt the instant the answer was observed
	 * @return the resumed pause
	 */
	public PauseResponse resume(Long pauseId, Instant resumedAt) {
		AgentPause pause = recorder.resume(pauseId, resumedAt);
		return new PauseResponse(pause.getStatus(), pause.getPauseStart());
	}

	/**
	 * Ends a pause without resuming the procedure.
	 *
	 * @param pauseId the pause identifier
	 * @param reason the reason of the abort
	 * @return the aborted pause
	 */
	public PauseResponse abort(Long pauseId, PauseReason reason) {
		if (reason == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "reason", "The abort reason is required.");
		}
		AgentPause pause = recorder.abort(pauseId, reason, clock.instant());
		return new PauseResponse(pause.getStatus(), pause.getPauseStart());
	}

	/**
	 * Reads the current pause of an OpenCode session.
	 *
	 * @param sessionId the OpenCode session identifier
	 * @return the current pause, empty when the session has none
	 */
	@Transactional(readOnly = true)
	public Optional<PauseStateResponse> current(String sessionId) {
		Instant now = clock.instant();
		return pauses.findFirstBySessionIdAndStatusInOrderByPauseStartDesc(sessionId, OPEN)
				.map(pause -> stateOf(pause, now));
	}

	/**
	 * Lists the pause history of a procedure, newest first.
	 *
	 * @param procedureId the procedure identifier
	 * @param limit the maximum number of entries, null for the default
	 * @return the bounded history
	 */
	@Transactional(readOnly = true)
	public List<PauseHistoryItem> history(Long procedureId, Integer limit) {
		int bounded = limit == null ? DEFAULT_HISTORY_LIMIT : limit;
		if (bounded < 1 || bounded > MAX_HISTORY_LIMIT) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, "limit",
					"The limit must be between 1 and " + MAX_HISTORY_LIMIT + ".");
		}
		return pauses.findByProcedureIdOrderByPauseStartDesc(procedureId, PageRequest.of(0, bounded)).stream()
				.map(pause -> new PauseHistoryItem(pause.getId(), pause.getProcedureId(), pause.getSessionId(),
						pause.getStatus(), pause.getPauseStart(), pause.getResumeAt(), pause.getDurationMs(),
						pause.getReason()))
				.toList();
	}

	/**
	 * Builds the timeout notifications of every expired and unresolved pause.
	 *
	 * @return the notifications due now or later, ordered by expiry
	 */
	@Transactional(readOnly = true)
	public List<PauseNotification> notifications() {
		Instant now = clock.instant();
		long intervalMs = notificationRepeat.toMillis();
		List<PauseNotification> notifications = new ArrayList<>();
		for (AgentPause pause : pauses.findByStatus(PauseStatus.EXPIRED)) {
			Instant expiredAt = pause.getPauseStart().plus(timeout);
			long overdueMs = Math.max(0L, Duration.between(expiredAt, now).toMillis());
			long steps = (overdueMs + intervalMs - 1) / intervalMs;
			Instant nextDueAt = expiredAt.plusMillis(steps * intervalMs);
			String title = lookup.procedureTitle(pause.getProcedureId());
			String message = "The pause on " + title + " reached " + timeout.toMinutes()
					+ " minutes. Answer SIM in the OpenCode chat to resume.";
			notifications.add(new PauseNotification(pause.getId(), pause.getProcedureId(), pause.getSessionId(),
					title, expiredAt, nextDueAt, notificationRepeat.toMinutes(),
					PauseNotification.CODE_PAUSE_EXPIRED, PauseNotification.TITLE_PAUSE_EXPIRED, message,
					Map.of("procedureTitle", title, "timeoutMinutes", timeout.toMinutes(),
							"repeatIntervalMinutes", notificationRepeat.toMinutes())));
		}
		notifications.sort((left, right) -> left.expiredAt().compareTo(right.expiredAt()));
		return notifications;
	}

	/**
	 * Runs the expiry check.
	 *
	 * @return the number of pauses that changed state
	 */
	public int expireDuePauses() {
		int expired = recorder.expireDue(clock.instant());
		if (expired > 0) {
			log.info("Marked {} pause(s) as expired after the timeout", expired);
		}
		return expired;
	}

	/**
	 * Looks for the continuation answer of one open pause.
	 *
	 * @param pause the open pause
	 * @return the instant of the answer, empty when there is none
	 */
	Optional<Instant> continuationAnswer(AgentPause pause) {
		return openCode.findContinuationAnswer(pause.getSessionId(), pause.getPauseStart(), DIRECTION_PROMPT);
	}

	/**
	 * Lists the open pauses, used by the continuation detector.
	 *
	 * @return the pauses in the paused or expired state
	 */
	@Transactional(readOnly = true)
	public List<AgentPause> openPauses() {
		return pauses.findByStatusIn(OPEN);
	}

	private ApiException refusal(OpenCodePauseResult attempt) {
		ErrorCode code = attempt.outcome().errorCode();
		String detail = attempt.detail() == null || attempt.detail().isBlank()
				? "The OpenCode server refused the pause."
				: attempt.detail();
		return new ApiException(code == null ? ErrorCode.PAUSE_FAILED : code, "opencode", detail);
	}

	private PauseStateResponse stateOf(AgentPause pause, Instant now) {
		Instant expiresAt = pause.getPauseStart().plus(timeout);
		boolean expired = pause.getStatus() == PauseStatus.EXPIRED;
		boolean expiryDue = pause.getStatus() == PauseStatus.PAUSED && !now.isBefore(expiresAt);
		long pausedElapsed = pause.getResumeAt() != null
				? Duration.between(pause.getPauseStart(), pause.getResumeAt()).toMillis()
				: Math.max(0L, Duration.between(pause.getPauseStart(), now).toMillis());
		return new PauseStateResponse(pause.getId(), pause.getProcedureId(), pause.getSessionId(), pause.getStatus(),
				pause.getPauseStart(), pause.getResumeAt(), pause.getDurationMs(), pausedElapsed, expiresAt,
				expiryDue, expired, pause.getReason());
	}
}
