package br.com.flexclock.time;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.enums.TimeMode;
import br.com.flexclock.model.AgentPause;
import br.com.flexclock.model.WorkSession;
import br.com.flexclock.pause.AgentPauseRepository;
import br.com.flexclock.procedure.ProcedureStateWriter;

/**
 * Time tracking of the clock: start, stop and read a work session.
 *
 * <p>The agent is the only actor that starts and stops the time (requirement RF-021). Every duration is
 * derived from the recorded instants by {@link WorkSessionTimeline}, so the state survives a suspended
 * machine, a resumed process, a clock adjustment or a long pause, and a session that crosses midnight is
 * split into one segment per local date (requirements RF-023, RF-024, RF-027).
 *
 * <p>A write is atomic: the session and the derived durations are committed together, and a rejected
 * stop leaves the session untouched (requirement RF-155).
 */
@Service
public class WorkSessionService {

	/** Smallest accepted countdown target, in seconds. */
	static final int MIN_TARGET_SECONDS = 1;

	/** Largest accepted countdown target, in seconds: thirty one days. */
	static final int MAX_TARGET_SECONDS = 2_678_400;

	private final WorkSessionRepository workSessions;
	private final AgentPauseRepository pauses;
	private final WorkSessionTimeline timeline;
	private final ProcedureStateWriter procedureState;
	private final Clock clock;
	private final String regionTimezone;

	public WorkSessionService(WorkSessionRepository workSessions, AgentPauseRepository pauses,
			WorkSessionTimeline timeline, ProcedureStateWriter procedureState, Clock clock,
			@Value("${flexclock.region.timezone:#{T(java.time.ZoneId).systemDefault().getId()}}") String regionTimezone) {
		this.workSessions = workSessions;
		this.pauses = pauses;
		this.timeline = timeline;
		this.procedureState = procedureState;
		this.clock = clock;
		this.regionTimezone = regionTimezone;
	}

	/**
	 * Starts a work session.
	 *
	 * @param request the mode, the optional countdown target, the optional procedure and the optional
	 * timezone
	 * @return the created session
	 * @throws ApiException when the request is invalid, the procedure does not exist or the procedure is
	 * already counting time
	 */
	@Transactional
	public WorkSessionResponse start(WorkSessionRequest request) {
		String timezone = validate(request);
		Instant now = clock.instant();

		if (request.procedureId() != null
				&& workSessions.findFirstByProcedureIdAndEndedAtIsNullOrderByStartedAtDesc(request.procedureId())
						.isPresent()) {
			throw new ApiException(ErrorCode.WORK_SESSION_ALREADY_RUNNING, "procedureId",
					"The procedure already has an open work session.");
		}

		WorkSession session = WorkSession.start(request.mode(),
				request.mode() == TimeMode.PROJECT_STOPWATCH ? request.targetSeconds() : null, request.procedureId(),
				now, timezone);

		try {
			workSessions.saveAndFlush(session);
		}
		catch (DataIntegrityViolationException exception) {
			throw new ApiException(ErrorCode.WORK_SESSION_ALREADY_RUNNING, "procedureId",
					"The procedure already has an open work session.");
		}
		return new WorkSessionResponse(session.getId(), session.getMode(), session.getStartedAt());
	}

	/**
	 * Stops a work session and freezes its derived durations.
	 *
	 * @param workSessionId the session identifier
	 * @return the complete result, with the midnight split
	 * @throws ApiException when the session does not exist, is already stopped or the recorded instants
	 * would produce an invalid duration
	 */
	@Transactional
	public WorkSessionResult stop(Long workSessionId) {
		WorkSession session = requireSession(workSessionId);
		if (session.getEndedAt() != null) {
			throw new ApiException(ErrorCode.WORK_SESSION_NOT_RUNNING, "workSessionId",
					"The work session is already stopped.");
		}

		Instant now = clock.instant();
		if (now.isBefore(session.getStartedAt())) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "endedAt",
					"The stop instant is before the start instant.");
		}

		List<AgentPause> sessionPauses = pausesOf(session, now);
		long total = timeline.totalMillis(session, now);
		long paused = timeline.pausedMillis(sessionPauses, session.getStartedAt(), now, now);
		if (paused > total) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "pausedMs",
					"The paused time exceeds the session duration.");
		}

		session.setEndedAt(now);
		session.setTotalMs(total);
		session.setPausedMs(paused);
		session.setActiveMs(total - paused);
		workSessions.save(session);

		return resultOf(session, sessionPauses, now);
	}

	/**
	 * Reads the current time state for the clock window.
	 *
	 * @param procedureId the procedure to read, null to read the most recently started open session
	 * @return the current state, with no mode and not running when no session is open
	 */
	@Transactional(readOnly = true)
	public ClockState current(Long procedureId) {
		Optional<WorkSession> open = procedureId == null
				? workSessions.findFirstByEndedAtIsNullOrderByStartedAtDesc()
				: workSessions.findFirstByProcedureIdAndEndedAtIsNullOrderByStartedAtDesc(procedureId);
		if (open.isEmpty()) {
			return new ClockState(null, 0L, null, false, procedureId);
		}

		WorkSession session = open.get();
		Instant now = clock.instant();
		long active = timeline.activeMillis(session, pausesOf(session, now), now);
		return new ClockState(session.getMode(), active, timeline.remainingMillis(session, active), true,
				session.getProcedureId());
	}

	/**
	 * Reads a work session with its derived durations and the midnight split.
	 *
	 * @param workSessionId the session identifier
	 * @return the complete result
	 * @throws ApiException when the session does not exist
	 */
	@Transactional(readOnly = true)
	public WorkSessionResult get(Long workSessionId) {
		WorkSession session = requireSession(workSessionId);
		Instant now = clock.instant();
		return resultOf(session, pausesOf(session, now), now);
	}

	private WorkSessionResult resultOf(WorkSession session, List<AgentPause> sessionPauses, Instant now) {
		Instant end = session.getEndedAt() != null ? session.getEndedAt() : now;
		long total = timeline.totalMillis(session, end);
		long paused = Math.min(total, timeline.pausedMillis(sessionPauses, session.getStartedAt(), end, now));
		long active = total - paused;
		return new WorkSessionResult(session.getId(), session.getMode(), session.getTargetSeconds(),
				session.getProcedureId(), session.getStartedAt(), session.getEndedAt(), total, paused, active,
				session.getTimezone(), session.getEndedAt() == null, timeline.timerCompleted(session, active),
				timeline.segments(session, sessionPauses, end));
	}

	private List<AgentPause> pausesOf(WorkSession session, Instant now) {
		if (session.getProcedureId() == null) {
			return List.of();
		}
		return pauses.findByProcedureIdAndPauseStartLessThanEqualOrderByPauseStartAsc(session.getProcedureId(), now);
	}

	private WorkSession requireSession(Long workSessionId) {
		return workSessions.findById(workSessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "workSessionId",
						"The work session does not exist."));
	}

	private String validate(WorkSessionRequest request) {
		if (request.mode() == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "mode", "The time mode is required.");
		}
		if (request.mode() == TimeMode.PROJECT_STOPWATCH) {
			Integer target = request.targetSeconds();
			if (target == null) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR, "targetSeconds",
						"A project countdown requires a target.");
			}
			if (target < MIN_TARGET_SECONDS || target > MAX_TARGET_SECONDS) {
				throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, "targetSeconds",
						"The target must be between " + MIN_TARGET_SECONDS + " and " + MAX_TARGET_SECONDS
								+ " seconds.");
			}
		}
		if (request.procedureId() != null && !procedureState.exists(request.procedureId())) {
			throw new ApiException(ErrorCode.NOT_FOUND, "procedureId", "The procedure does not exist.");
		}
		return resolveTimezone(request.timezone());
	}

	private String resolveTimezone(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			return regionTimezone;
		}
		try {
			return ZoneId.of(timezone).getId();
		}
		catch (DateTimeException exception) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "timezone",
					"The timezone must be a valid IANA timezone identifier.");
		}
	}
}
