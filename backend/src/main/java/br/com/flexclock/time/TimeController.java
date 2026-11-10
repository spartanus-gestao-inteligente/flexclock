package br.com.flexclock.time;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.ErrorResponse;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.common.IdempotentWrite;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

/**
 * Time endpoints of the clock.
 *
 * <p>Only the agent starts and stops the time, through the MCP sidecar or this REST surface; the
 * dashboard and the clock window never start, stop, restart, switch the mode or change the target
 * (requirement RF-021). Reads never mutate state, and every write carries an Idempotency-Key
 * (architecture contract, section 5).
 *
 * <p>Controllers stay thin: they bind, delegate and return a record. Every rule lives in
 * {@link WorkSessionService}.
 */
@RestController
@RequestMapping(ContractVersion.API_BASE_PATH)
public class TimeController {

	private final WorkSessionService workSessionService;
	private final IdempotentWrite idempotentWrite;

	public TimeController(WorkSessionService workSessionService, IdempotentWrite idempotentWrite) {
		this.workSessionService = workSessionService;
		this.idempotentWrite = idempotentWrite;
	}

	/**
	 * Starts a work session.
	 *
	 * @param request the mode, the optional target, the optional procedure and the optional timezone
	 * @param idempotencyKey the mandatory write key
	 * @return the created session with status 201
	 */
	@Operation(summary = "Start a work session")
	@ApiResponse(responseCode = "201", description = "The work session was created.")
	@ApiResponse(responseCode = "400", description = "The request is invalid.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "404", description = "The procedure does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The procedure already has an open work session.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/work-sessions")
	public ResponseEntity<WorkSessionResponse> start(@Valid @RequestBody WorkSessionRequest request,
			@RequestAttribute(IdempotencyFilter.IDEMPOTENCY_KEY_ATTRIBUTE) String idempotencyKey) {
		return idempotentWrite.execute(idempotencyKey, "work-sessions.start", request, HttpStatus.CREATED,
				WorkSessionResponse.class, () -> workSessionService.start(request));
	}

	/**
	 * Stops a work session and returns the complete result with the midnight split.
	 *
	 * @param workSessionId the session identifier
	 * @param idempotencyKey the mandatory write key
	 * @return the stopped session with status 200
	 */
	@Operation(summary = "Stop a work session")
	@ApiResponse(responseCode = "200", description = "The work session was stopped.")
	@ApiResponse(responseCode = "404", description = "The work session does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The work session is already stopped.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/work-sessions/{workSessionId}/stop")
	public ResponseEntity<WorkSessionResult> stop(@PathVariable Long workSessionId,
			@RequestAttribute(IdempotencyFilter.IDEMPOTENCY_KEY_ATTRIBUTE) String idempotencyKey) {
		return idempotentWrite.execute(idempotencyKey, "work-sessions.stop", Map.of("workSessionId", workSessionId),
				HttpStatus.OK, WorkSessionResult.class, () -> workSessionService.stop(workSessionId));
	}

	/**
	 * Reads the current time state of the clock window.
	 *
	 * @param procedureId the procedure to read, null to read the most recently started open session
	 * @return the current state
	 */
	@Operation(summary = "Read the current time state")
	@ApiResponse(responseCode = "200", description = "The current time state.")
	@GetMapping("/work-sessions/current")
	public ClockState current(@RequestParam(required = false) Long procedureId) {
		return workSessionService.current(procedureId);
	}

	/**
	 * Reads a work session with its derived durations and the midnight split.
	 *
	 * @param workSessionId the session identifier
	 * @return the complete result
	 */
	@Operation(summary = "Read a work session")
	@ApiResponse(responseCode = "200", description = "The work session result.")
	@ApiResponse(responseCode = "404", description = "The work session does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/work-sessions/{workSessionId}")
	public WorkSessionResult get(@PathVariable Long workSessionId) {
		return workSessionService.get(workSessionId);
	}
}
