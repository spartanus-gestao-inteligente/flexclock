package br.com.flexclock.sessions;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.ErrorResponse;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.common.IdempotentWrite;
import br.com.flexclock.dto.AnchorRequest;
import br.com.flexclock.dto.AnchorResponse;
import br.com.flexclock.dto.AnchorSummary;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

/**
 * Anchoring of the OpenCode sessions, one anchor per Clock window.
 *
 * <p>Anchoring creates the anchor and creates or links the procedure; the heartbeat keeps the anchor
 * alive and the list feeds the tray menu and the dashboard. The controller is thin: every rule lives
 * in {@link AnchorService}, and every write is idempotent.
 */
@RestController
@RequestMapping(ContractVersion.API_BASE_PATH + "/anchors")
public class AnchorController {


	private final AnchorService anchorService;
	private final IdempotentWrite idempotentWrite;

	public AnchorController(AnchorService anchorService, IdempotentWrite idempotentWrite) {
		this.anchorService = anchorService;
		this.idempotentWrite = idempotentWrite;
	}

	/**
	 * Anchors one session to the requesting window.
	 *
	 * @param request the session chosen in the selector
	 * @param idempotencyKey the idempotency key of the write
	 * @return the anchor, the procedure and the clock state
	 */
	@ApiResponse(responseCode = "201", description = "The session was anchored.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = AnchorResponse.class)))
	@ApiResponse(responseCode = "404", description = "The OpenCode session does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The session is already anchored.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "The OpenCode server is unreachable.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping
	public ResponseEntity<AnchorResponse> create(@Valid @RequestBody AnchorRequest request,
			@RequestHeader(name = IdempotencyFilter.IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
		return idempotentWrite.execute(idempotencyKey, "anchors.create", request, HttpStatus.CREATED,
				AnchorResponse.class, () -> anchorService.create(request));
	}

	/**
	 * Refreshes the heartbeat of one anchor.
	 *
	 * @param anchorId the anchor identifier
	 * @param idempotencyKey the idempotency key of the write
	 * @return an empty response
	 */
	@ApiResponse(responseCode = "204", description = "The heartbeat was refreshed.")
	@ApiResponse(responseCode = "404", description = "The anchor does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/{anchorId}/heartbeat")
	public ResponseEntity<Void> heartbeat(@PathVariable Long anchorId,
			@RequestHeader(name = IdempotencyFilter.IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
		return idempotentWrite.execute(idempotencyKey, "anchors.heartbeat", anchorId, HttpStatus.NO_CONTENT,
				Void.class, () -> {
					anchorService.heartbeat(anchorId);
					return null;
				});
	}

	/**
	 * Lists every anchored session with its procedure and pause state.
	 *
	 * @return the anchors, oldest first
	 */
	@ApiResponse(responseCode = "200", description = "Every anchored session.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					array = @ArraySchema(schema = @Schema(implementation = AnchorSummary.class))))
	@GetMapping
	public List<AnchorSummary> list() {
		return anchorService.list();
	}
}
