package br.com.flexclock.pause;

import java.util.List;
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

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.common.ErrorResponse;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.common.IdempotencyService;
import br.com.flexclock.common.IdempotentResponse;
import br.com.flexclock.dto.PauseAbortRequest;
import br.com.flexclock.dto.PauseHistoryItem;
import br.com.flexclock.dto.PauseNotification;
import br.com.flexclock.dto.PauseResponse;
import br.com.flexclock.dto.PauseStateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

/**
 * Pause endpoints of a procedure.
 *
 * <p>The pause request is bound to the anchor of the requesting window; the body carries no session
 * identifier on purpose (requirement RF-041). Reads expose the current pause state, the pause history
 * and the timeout notifications (requirements RF-040, RF-037).
 *
 * <p>Controllers stay thin: they bind, delegate and return a record. Every rule lives in
 * {@link PauseService}.
 */
@RestController
@RequestMapping(ContractVersion.API_BASE_PATH)
public class PauseController {

	private final PauseService pauseService;
	private final IdempotencyService idempotencyService;

	public PauseController(PauseService pauseService, IdempotencyService idempotencyService) {
		this.pauseService = pauseService;
		this.idempotencyService = idempotencyService;
	}

	/**
	 * Applies a pause to the session of an anchor.
	 *
	 * @param anchorId the anchor of the requesting window
	 * @param idempotencyKey the mandatory write key
	 * @return the applied pause with status 201
	 */
	@Operation(summary = "Pause the agent of an anchored session")
	@ApiResponse(responseCode = "201", description = "The pause was applied.")
	@ApiResponse(responseCode = "404", description = "The anchor or the session does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The anchor is stale or the pause transition is not allowed.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "502", description = "The abort or the direction turn failed after the retries.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "The OpenCode server is unreachable or the port is busy.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/anchors/{anchorId}/pause")
	public ResponseEntity<PauseResponse> pause(@PathVariable Long anchorId,
			@RequestAttribute(IdempotencyFilter.IDEMPOTENCY_KEY_ATTRIBUTE) String idempotencyKey) {
		IdempotentResponse<PauseResponse> response = idempotencyService.execute(idempotencyKey, "anchors.pause",
				Map.of("anchorId", anchorId), HttpStatus.CREATED.value(), PauseResponse.class,
				() -> pauseService.pause(anchorId));
		return ResponseEntity.status(response.status()).body(response.body());
	}

	/**
	 * Resumes a pause because the continuation answer was observed.
	 *
	 * @param pauseId the pause identifier
	 * @param idempotencyKey the mandatory write key
	 * @return the resumed pause with status 200
	 */
	@Operation(summary = "Resume a paused procedure")
	@ApiResponse(responseCode = "200", description = "The pause was resumed.")
	@ApiResponse(responseCode = "404", description = "The pause does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The pause transition is not allowed.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/pauses/{pauseId}/resume")
	public ResponseEntity<PauseResponse> resume(@PathVariable Long pauseId,
			@RequestAttribute(IdempotencyFilter.IDEMPOTENCY_KEY_ATTRIBUTE) String idempotencyKey) {
		IdempotentResponse<PauseResponse> response = idempotencyService.execute(idempotencyKey, "pauses.resume",
				Map.of("pauseId", pauseId), HttpStatus.OK.value(), PauseResponse.class,
				() -> pauseService.resume(pauseId));
		return ResponseEntity.status(response.status()).body(response.body());
	}

	/**
	 * Ends a pause without resuming the procedure.
	 *
	 * @param pauseId the pause identifier
	 * @param request the abort reason
	 * @param idempotencyKey the mandatory write key
	 * @return the aborted pause with status 200
	 */
	@Operation(summary = "Abort a paused procedure without resuming it")
	@ApiResponse(responseCode = "200", description = "The pause was aborted.")
	@ApiResponse(responseCode = "404", description = "The pause does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The pause transition is not allowed.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/pauses/{pauseId}/abort")
	public ResponseEntity<PauseResponse> abort(@PathVariable Long pauseId,
			@Valid @RequestBody PauseAbortRequest request,
			@RequestAttribute(IdempotencyFilter.IDEMPOTENCY_KEY_ATTRIBUTE) String idempotencyKey) {
		IdempotentResponse<PauseResponse> response = idempotencyService.execute(idempotencyKey, "pauses.abort",
				Map.of("pauseId", pauseId, "reason", request.reason().wireValue()), HttpStatus.OK.value(),
				PauseResponse.class, () -> pauseService.abort(pauseId, request.reason()));
		return ResponseEntity.status(response.status()).body(response.body());
	}

	/**
	 * Reads the current pause of an OpenCode session.
	 *
	 * @param sessionId the OpenCode session identifier
	 * @return the current pause, or 404 when the session has none
	 */
	@Operation(summary = "Read the current pause of a session")
	@ApiResponse(responseCode = "200", description = "The current pause.")
	@ApiResponse(responseCode = "404", description = "The session has no current pause.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/pauses/current")
	public PauseStateResponse current(@RequestParam String sessionId) {
		return pauseService.current(sessionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "sessionId",
				"The session has no current pause."));
	}

	/**
	 * Lists the timeout notifications of the expired and unresolved pauses.
	 *
	 * @return the notifications
	 */
	@Operation(summary = "List the pause timeout notifications")
	@ApiResponse(responseCode = "200", description = "The notifications of the expired pauses.")
	@GetMapping("/pauses/notifications")
	public List<PauseNotification> notifications() {
		return pauseService.notifications();
	}

	/**
	 * Lists the pause history of a procedure, newest first.
	 *
	 * @param procedureId the procedure identifier
	 * @param limit the maximum number of entries, null for the default
	 * @return the bounded history
	 */
	@Operation(summary = "List the pause history of a procedure")
	@ApiResponse(responseCode = "200", description = "The bounded pause history.")
	@ApiResponse(responseCode = "400", description = "The limit is outside the allowed range.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/procedures/{procedureId}/pauses")
	public List<PauseHistoryItem> history(@PathVariable Long procedureId,
			@RequestParam(required = false) Integer limit) {
		return pauseService.history(procedureId, limit);
	}
}
