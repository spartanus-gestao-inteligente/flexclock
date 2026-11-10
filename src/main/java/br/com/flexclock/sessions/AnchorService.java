package br.com.flexclock.sessions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.dto.AnchorRequest;
import br.com.flexclock.dto.AnchorResponse;
import br.com.flexclock.dto.AnchorSummary;
import br.com.flexclock.enums.AnchorStatus;
import br.com.flexclock.enums.PauseStatus;
import br.com.flexclock.model.AgentAnchor;
import br.com.flexclock.model.AgentPause;
import br.com.flexclock.model.Procedure;
import br.com.flexclock.opencode.OpenCodeSession;
import br.com.flexclock.opencode.OpenCodeSessionGateway;
import br.com.flexclock.opencode.OpenCodeUnavailableException;
import br.com.flexclock.procedure.ProcedureRepository;
import br.com.flexclock.procedure.ProcedureService;
import br.com.flexclock.time.WorkSessionService;

/**
 * Anchors one OpenCode session to one Clock window.
 *
 * <p>The backend owns the OpenCode session identifier: the client only sends the identifier chosen in
 * the selector, the backend resolves the title and the directory from the OpenCode server and writes
 * the anchor and the procedure in the same transaction (decision 0043; requirements RF-014, RF-016,
 * RF-028 and RN-011).
 *
 * <p>One session equals one anchor equals one window. Anchoring twice is rejected with
 * {@code ANCHOR_EXISTS} and the client loads the existing window. A subagent session is never
 * anchored. When the OpenCode server reports the session as missing the request fails with
 * {@code SESSION_GONE}; when the server is unreachable it fails with {@code OPENCODE_UNREACHABLE} and
 * the anchor is not created, so no window is anchored to a session nobody confirmed (decision 0046;
 * requirements RF-012, RF-017 and RN-007).
 */
@Service
public class AnchorService {

	private final AgentAnchorRepository anchorRepository;
	private final ProcedureRepository procedureRepository;
	private final PauseLookupRepository pauseLookupRepository;
	private final ProcedureService procedureService;
	private final WorkSessionService workSessionService;
	private final OpenCodeSessionGateway openCodeGateway;
	private final Clock clock;

	public AnchorService(AgentAnchorRepository anchorRepository, ProcedureRepository procedureRepository,
			PauseLookupRepository pauseLookupRepository, ProcedureService procedureService,
			WorkSessionService workSessionService, OpenCodeSessionGateway openCodeGateway, Clock clock) {
		this.anchorRepository = anchorRepository;
		this.procedureRepository = procedureRepository;
		this.pauseLookupRepository = pauseLookupRepository;
		this.procedureService = procedureService;
		this.workSessionService = workSessionService;
		this.openCodeGateway = openCodeGateway;
		this.clock = clock;
	}

	/**
	 * Anchors one session and creates or links its procedure.
	 *
	 * @param request the session chosen in the selector
	 * @return the anchor, the procedure and the clock state of the window
	 * @throws ApiException with {@link ErrorCode#ANCHOR_EXISTS} when the session is already anchored,
	 * {@link ErrorCode#SESSION_GONE} when the OpenCode server reports the session as missing,
	 * {@link ErrorCode#OPENCODE_UNREACHABLE} when the server cannot answer and
	 * {@link ErrorCode#VALIDATION_ERROR} when the session is a subagent
	 */
	@Transactional
	public AnchorResponse create(AnchorRequest request) {
		String sessionId = request.sessionId().trim();
		if (anchorRepository.findBySessionId(sessionId).isPresent()) {
			throw new ApiException(ErrorCode.ANCHOR_EXISTS, "sessionId", "The session is already anchored.");
		}

		OpenCodeSession session = resolveSession(sessionId);
		Instant now = clock.instant();
		String title = titleOf(session, sessionId);
		String directory = directoryOf(session);

		Procedure procedure = procedureRepository.findBySessionId(sessionId)
				.map(existing -> refresh(existing, title, directory, now))
				.orElseGet(() -> procedureService.newActiveProcedure(sessionId, title, directory));
		procedureRepository.save(procedure);

		AgentAnchor anchor = new AgentAnchor();
		anchor.setSessionId(sessionId);
		anchor.setDirectory(directory);
		anchor.setTitle(title);
		anchor.setStatus(AnchorStatus.ACTIVE);
		anchor.setAnchoredAt(now);
		anchor.setUpdatedAt(now);
		anchorRepository.save(anchor);

		return new AnchorResponse(anchor.getId(), procedure.getId(), sessionId, title,
				workSessionService.current(procedure.getId()));
	}

	/**
	 * Refreshes the heartbeat of one anchor.
	 *
	 * <p>The window beats every 30 seconds and the maintenance deletes an anchor without a heartbeat
	 * for more than 2 minutes (requirement RF-016). The heartbeat never revives a stale anchor by
	 * itself: revival requires the OpenCode server to confirm that the session exists again (requirement
	 * RF-017).
	 *
	 * @param anchorId the anchor identifier
	 * @throws ApiException with {@link ErrorCode#ANCHOR_NOT_FOUND} when the anchor does not exist
	 */
	@Transactional
	public void heartbeat(Long anchorId) {
		AgentAnchor anchor = anchorRepository.findById(anchorId)
				.orElseThrow(() -> new ApiException(ErrorCode.ANCHOR_NOT_FOUND, "anchorId",
						"There is no anchor with id " + anchorId + "."));
		anchor.setUpdatedAt(clock.instant());
		anchorRepository.save(anchor);
	}

	/**
	 * Lists every anchored session with its procedure and pause state.
	 *
	 * @return the anchors, oldest first
	 */
	@Transactional(readOnly = true)
	public List<AnchorSummary> list() {
		List<AgentAnchor> anchors = anchorRepository.findAllByOrderByAnchoredAtAsc();
		if (anchors.isEmpty()) {
			return List.of();
		}

		List<String> sessionIds = anchors.stream().map(AgentAnchor::getSessionId).toList();
		Map<String, Procedure> proceduresBySession = new HashMap<>();
		for (Procedure procedure : procedureRepository.findBySessionIdIn(sessionIds)) {
			proceduresBySession.put(procedure.getSessionId(), procedure);
		}

		List<Long> procedureIds = proceduresBySession.values().stream().map(Procedure::getId).toList();
		Map<Long, AgentPause> latestPauseByProcedure = latestPauseByProcedure(procedureIds);
		Instant now = clock.instant();

		return anchors.stream()
				.map(anchor -> toSummary(anchor, proceduresBySession.get(anchor.getSessionId()),
						latestPauseByProcedure, now))
				.toList();
	}

	private Map<Long, AgentPause> latestPauseByProcedure(List<Long> procedureIds) {
		Map<Long, AgentPause> latest = new HashMap<>();
		if (procedureIds.isEmpty()) {
			return latest;
		}
		for (AgentPause pause : pauseLookupRepository.findByProcedureIdInOrderByPauseStartDesc(procedureIds)) {
			latest.putIfAbsent(pause.getProcedureId(), pause);
		}
		return latest;
	}

	private AnchorSummary toSummary(AgentAnchor anchor, Procedure procedure, Map<Long, AgentPause> latestPauses,
			Instant now) {
		AgentPause pause = procedure == null ? null : latestPauses.get(procedure.getId());
		PauseStatus pauseStatus = pause == null ? null : pause.getStatus();
		return new AnchorSummary(anchor.getId(), anchor.getSessionId(), anchor.getTitle(), anchor.getDirectory(),
				anchor.getStatus(), anchor.getAnchoredAt(), anchor.getUpdatedAt(),
				procedure == null ? null : procedure.getId(),
				procedure == null ? null : procedure.getStatus(), pauseStatus,
				pause == null ? null : pause.getPauseStart(), pauseElapsedMs(pause, now));
	}

	/**
	 * Elapsed pause time of the window: while the pause is open it grows with the clock, and once the
	 * pause ended the stored duration is the answer. The pause flow writes that duration when it
	 * resolves the pause, so this projection never invents a value.
	 *
	 * @param pause the latest pause of the procedure, may be null
	 * @param now the current instant
	 * @return the elapsed pause milliseconds, null when the pause never started
	 */
	private Long pauseElapsedMs(AgentPause pause, Instant now) {
		if (pause == null) {
			return null;
		}
		if (pause.getStatus() == PauseStatus.PAUSED) {
			return Math.max(0L, Duration.between(pause.getPauseStart(), now).toMillis());
		}
		return pause.getDurationMs();
	}

	private OpenCodeSession resolveSession(String sessionId) {
		OpenCodeSession session;
		try {
			session = openCodeGateway.findSession(sessionId)
					.orElseThrow(() -> new ApiException(ErrorCode.SESSION_GONE, "sessionId",
							"The OpenCode session no longer exists."));
		}
		catch (OpenCodeUnavailableException exception) {
			throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, "opencode",
					"The OpenCode server at the configured endpoint is unreachable.");
		}

		if (!session.isMain()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "sessionId",
					"The session is a subagent and cannot be anchored.");
		}
		return session;
	}

	private Procedure refresh(Procedure procedure, String title, String directory, Instant now) {
		procedure.setTitle(title);
		procedure.setDirectory(directory);
		procedure.setUpdatedAt(now);
		return procedure;
	}

	private static String titleOf(OpenCodeSession session, String sessionId) {
		return Optional.ofNullable(session.title()).filter(title -> !title.isBlank()).orElse(sessionId);
	}

	private static String directoryOf(OpenCodeSession session) {
		return Optional.ofNullable(session.directory()).filter(directory -> !directory.isBlank()).orElse("");
	}
}
