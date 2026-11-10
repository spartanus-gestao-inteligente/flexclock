package br.com.flexclock.opencode;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.ErrorResponse;
import br.com.flexclock.dto.SessionSummary;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Discovery of the OpenCode sessions offered to the session selector.
 *
 * <p>Read only: it never anchors and never changes the OpenCode server. The controller is thin and
 * delegates the filtering rule to {@link OpenCodeSessionService}.
 */
@RestController
@RequestMapping(ContractVersion.API_BASE_PATH + "/opencode")
public class OpenCodeSessionController {

	private final OpenCodeSessionService openCodeSessionService;

	public OpenCodeSessionController(OpenCodeSessionService openCodeSessionService) {
		this.openCodeSessionService = openCodeSessionService;
	}

	/**
	 * Lists the main OpenCode sessions, that is, the sessions without a parent.
	 *
	 * @return the main sessions with title and directory
	 */
	@ApiResponse(responseCode = "200", description = "The main sessions of the OpenCode server.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					array = @ArraySchema(schema = @Schema(implementation = SessionSummary.class))))
	@ApiResponse(responseCode = "503", description = "The OpenCode server is unreachable.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/sessions")
	public List<SessionSummary> sessions() {
		return openCodeSessionService.listMainSessions();
	}
}
