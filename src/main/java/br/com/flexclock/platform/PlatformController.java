package br.com.flexclock.platform;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.ErrorResponse;
import br.com.flexclock.dto.HealthResponse;
import br.com.flexclock.dto.MetadataResponse;
import br.com.flexclock.dto.VersionResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Lifecycle endpoints of the backend.
 *
 * <p>Controllers stay thin: they bind, delegate and return a record. Every business rule lives in
 * {@link PlatformService}. The paths are part of the frozen contract; the OpenAPI document is the
 * authority for their shapes.
 */
@RestController
@RequestMapping(ContractVersion.API_BASE_PATH)
public class PlatformController {

	private final PlatformService platformService;

	public PlatformController(PlatformService platformService) {
		this.platformService = platformService;
	}

	/**
	 * Readiness and runtime metadata. Answers 200 when the database responds and 503 otherwise.
	 *
	 * @return the health payload with the matching status
	 */
	@ApiResponse(responseCode = "400", description = "The request is not valid for the loopback surface.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "403", description = "The instance identifier does not match the live process.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/health")
	public ResponseEntity<HealthResponse> health() {
		HealthResponse health = platformService.health();
		HttpStatus status = HealthResponse.STATUS_UP.equals(health.status()) ? HttpStatus.OK
				: HttpStatus.SERVICE_UNAVAILABLE;
		return ResponseEntity.status(status).body(health);
	}

	/**
	 * Product metadata of the live backend.
	 *
	 * @return the metadata payload
	 */
	@ApiResponse(responseCode = "400", description = "The request is not valid for the loopback surface.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "403", description = "The instance identifier does not match the live process.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/metadata")
	public MetadataResponse metadata() {
		return platformService.metadata();
	}

	/**
	 * Contract version used by the compatibility handshake of the MCP and event clients.
	 *
	 * @return the version payload
	 */
	@ApiResponse(responseCode = "400", description = "The request is not valid for the loopback surface.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "403", description = "The instance identifier does not match the live process.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/version")
	public VersionResponse version() {
		return platformService.version();
	}
}
