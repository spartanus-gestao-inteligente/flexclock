package br.com.flexclock.platform;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.ErrorResponse;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.common.IdempotentResponse;
import br.com.flexclock.dto.LanguageResponse;
import br.com.flexclock.dto.LanguageUpdateRequest;
import br.com.flexclock.dto.SettingResponse;
import br.com.flexclock.dto.SettingUpdateRequest;
import br.com.flexclock.dto.SettingsResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

/**
 * Settings and language of the product.
 *
 * <p>The controller binds, validates the shape, delegates and returns a record. Every rule lives in
 * {@link SettingsService}. The paths are part of the frozen contract; the OpenAPI document is the
 * authority for their shapes.
 *
 * <p>A write declares its principal in the {@value SettingActor#ACTOR_HEADER} header, because a key
 * owned by the user is never writable by the agent (requirement RF-162, rule RN-051). A write without
 * the header is treated as the agent, the least privileged principal.
 */
@RestController
@RequestMapping(ContractVersion.API_BASE_PATH)
public class SettingsController {

	private final SettingsService settingsService;

	public SettingsController(SettingsService settingsService) {
		this.settingsService = settingsService;
	}

	/**
	 * Reads the complete settings surface.
	 *
	 * @return every key of the closed catalogue with its current value
	 */
	@ApiResponse(responseCode = "200", description = "The complete settings surface.")
	@GetMapping("/settings")
	public SettingsResponse settings() {
		return settingsService.readAll();
	}

	/**
	 * Changes one setting of the closed catalogue.
	 *
	 * @param key the closed key
	 * @param actorHeader the principal performing the write, the agent when absent
	 * @param idempotencyKey the key of the write
	 * @param request the new value
	 * @return the stored setting
	 */
	@ApiResponse(responseCode = "200", description = "The setting was stored.")
	@ApiResponse(responseCode = "403", description = "The key is owned by the user.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "404", description = "The key is not part of the closed catalogue.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PutMapping("/settings/{key}")
	public ResponseEntity<SettingResponse> updateSetting(@PathVariable String key,
			@RequestHeader(value = SettingActor.ACTOR_HEADER, required = false) String actorHeader,
			@RequestHeader(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
			@Valid @RequestBody SettingUpdateRequest request) {
		IdempotentResponse<SettingResponse> response = settingsService.update(key, request.value(),
				SettingActor.fromHeader(actorHeader), idempotencyKey);
		return ResponseEntity.status(response.status()).body(response.body());
	}

	/**
	 * Reads the effective language and the closed list of supported languages.
	 *
	 * @return the language payload
	 */
	@ApiResponse(responseCode = "200", description = "The effective language and the supported languages.")
	@GetMapping("/language")
	public LanguageResponse language() {
		return settingsService.language();
	}

	/**
	 * Changes the default language, the operation decision 0019 reserves for the agent.
	 *
	 * @param actorHeader the principal performing the write, the agent when absent
	 * @param idempotencyKey the key of the write
	 * @param request the new language tag
	 * @return the effective language after the change
	 */
	@ApiResponse(responseCode = "200", description = "The language was stored.")
	@ApiResponse(responseCode = "400", description = "The language tag is not part of the closed list.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PutMapping("/language")
	public ResponseEntity<LanguageResponse> updateLanguage(
			@RequestHeader(value = SettingActor.ACTOR_HEADER, required = false) String actorHeader,
			@RequestHeader(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
			@Valid @RequestBody LanguageUpdateRequest request) {
		IdempotentResponse<LanguageResponse> response = settingsService.updateLanguage(request.language(),
				SettingActor.fromHeader(actorHeader), idempotencyKey);
		return ResponseEntity.status(response.status()).body(response.body());
	}

}
