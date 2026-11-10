package br.com.flexclock.procedure;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.ErrorResponse;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.common.IdempotentWrite;
import br.com.flexclock.dto.ProcedureResponse;
import br.com.flexclock.dto.ProcedureTransitionRequest;
import br.com.flexclock.enums.ProcedureStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

/**
 * Lifecycle and history of the procedures.
 *
 * <p>Complete and abort are the two final transitions of the product; the transition operation is the
 * one the event service forwards, and it accepts only the same two final statuses. Every write is
 * idempotent and returns the procedure after the change.
 */
@RestController
@RequestMapping(ContractVersion.API_BASE_PATH + "/procedures")
public class ProcedureController {


	private final ProcedureService procedureService;
	private final IdempotentWrite idempotentWrite;

	public ProcedureController(ProcedureService procedureService, IdempotentWrite idempotentWrite) {
		this.procedureService = procedureService;
		this.idempotentWrite = idempotentWrite;
	}

	/**
	 * Lists the procedures of the history, optionally filtered by status.
	 *
	 * @param status the status to filter by, absent to list every procedure
	 * @return the procedures, oldest first
	 */
	@ApiResponse(responseCode = "200", description = "The procedures of the history.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					array = @ArraySchema(schema = @Schema(implementation = ProcedureResponse.class))))
	@GetMapping
	public List<ProcedureResponse> list(@RequestParam(name = "status", required = false) ProcedureStatus status) {
		return procedureService.list(status);
	}

	/**
	 * Completes a procedure.
	 *
	 * @param procedureId the procedure identifier
	 * @param idempotencyKey the idempotency key of the write
	 * @return the procedure after the transition
	 */
	@ApiResponse(responseCode = "200", description = "The procedure was completed.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ProcedureResponse.class)))
	@ApiResponse(responseCode = "404", description = "The procedure does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The transition is not allowed.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/{procedureId}/complete")
	public ResponseEntity<ProcedureResponse> complete(@PathVariable Long procedureId,
			@RequestHeader(name = IdempotencyFilter.IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
		return idempotentWrite.execute(idempotencyKey, "procedures.complete", procedureId, HttpStatus.OK,
				ProcedureResponse.class, () -> procedureService.complete(procedureId));
	}

	/**
	 * Aborts a procedure that ended without conclusion.
	 *
	 * @param procedureId the procedure identifier
	 * @param idempotencyKey the idempotency key of the write
	 * @return the procedure after the transition
	 */
	@ApiResponse(responseCode = "200", description = "The procedure was aborted.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ProcedureResponse.class)))
	@ApiResponse(responseCode = "404", description = "The procedure does not exist.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The transition is not allowed.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/{procedureId}/abort")
	public ResponseEntity<ProcedureResponse> abort(@PathVariable Long procedureId,
			@RequestHeader(name = IdempotencyFilter.IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
		return idempotentWrite.execute(idempotencyKey, "procedures.abort", procedureId, HttpStatus.OK,
				ProcedureResponse.class, () -> procedureService.abort(procedureId));
	}

	/**
	 * Applies the transition forwarded by the event service.
	 *
	 * @param procedureId the procedure identifier
	 * @param request the requested final status
	 * @param idempotencyKey the idempotency key of the write
	 * @return the procedure after the transition
	 */
	@ApiResponse(responseCode = "200", description = "The transition was applied.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ProcedureResponse.class)))
	@ApiResponse(responseCode = "400", description = "The request is not valid.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The transition is not allowed.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/{procedureId}/transition")
	public ResponseEntity<ProcedureResponse> transition(@PathVariable Long procedureId,
			@Valid @RequestBody ProcedureTransitionRequest request,
			@RequestHeader(name = IdempotencyFilter.IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
		return idempotentWrite.execute(idempotencyKey, "procedures.transition", request, HttpStatus.OK,
				ProcedureResponse.class, () -> procedureService.transition(procedureId, request.status()));
	}
}
