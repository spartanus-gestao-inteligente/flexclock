package br.com.flexclock.pause;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Calls the local OpenCode server for the pause flow.
 *
 * <p>The server is reached over loopback only, with the REST client configured by the product
 * (decision 0034). The client classifies the health result the way the contract requires: healthy,
 * server unreachable, port held by another process, or session missing (requirements RF-032, RF-033).
 *
 * <p>The abort and the single direction turn are retried by {@link Retrier} on a transient failure.
 * The direction turn is exactly one short prompt that tells the agent to verify the current state and
 * to call the native question tool with the continue option; the backend never sends a second turn
 * (requirement RF-034).
 */
@Component
public class OpenCodePauseClient {

	private static final Logger log = LoggerFactory.getLogger(OpenCodePauseClient.class);

	private static final String HEALTH_PATH = "/global/health";

	private static final String SESSION_PATH = "/session/{sessionId}";

	private static final String ABORT_PATH = "/session/{sessionId}/abort";

	private static final String MESSAGE_PATH = "/session/{sessionId}/message";

	/**
	 * Asynchronous admission of the direction turn.
	 *
	 * <p>The synchronous message endpoint answers only when the agent turn ends, and the turn ends only
	 * when the question is answered. The asynchronous endpoint admits the turn and returns immediately,
	 * so the pause endpoint never blocks on the open question (decision 0034).
	 */
	private static final String PROMPT_ASYNC_PATH = "/session/{sessionId}/prompt_async";

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

	private final RestClient restClient;
	private final Retrier retrier;
	private final ObjectMapper objectMapper;
	private final String providerId;
	private final String modelId;

	public OpenCodePauseClient(RestClient openCodeRestClient, Retrier retrier, ObjectMapper objectMapper,
			@Value("${flexclock.opencode.direction-model:}") String directionModel) {
		this.restClient = openCodeRestClient;
		this.retrier = retrier;
		this.objectMapper = objectMapper;
		String model = directionModel == null ? "" : directionModel.trim();
		int separator = model.indexOf('/');
		if (separator > 0 && separator < model.length() - 1) {
			this.providerId = model.substring(0, separator);
			this.modelId = model.substring(separator + 1);
		}
		else {
			this.providerId = null;
			this.modelId = null;
		}
	}

	/**
	 * Probes the OpenCode health endpoint and classifies the result.
	 *
	 * @throws ApiException with {@link ErrorCode#OPENCODE_UNREACHABLE} when the server does not answer
	 * and with {@link ErrorCode#PORT_BUSY} when another process answers on the port
	 */
	public void requireHealthy() {
		try {
			restClient.get().uri(HEALTH_PATH).retrieve().toBodilessEntity();
		}
		catch (ResourceAccessException exception) {
			throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, "opencode",
					"The OpenCode server did not answer on the configured port.");
		}
		catch (RestClientResponseException exception) {
			if (exception.getStatusCode().is5xxServerError()) {
				throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, "opencode",
						"The OpenCode server reported that it is not healthy.");
			}
			throw new ApiException(ErrorCode.PORT_BUSY, "opencode",
					"Another process answered on the OpenCode port.");
		}
	}

	/**
	 * Reports whether the OpenCode session still exists.
	 *
	 * @param sessionId the OpenCode session identifier
	 * @return true when the session exists
	 * @throws ApiException with {@link ErrorCode#OPENCODE_UNREACHABLE} when the server does not answer
	 */
	public boolean sessionExists(String sessionId) {
		try {
			restClient.get().uri(SESSION_PATH, sessionId).retrieve().toBodilessEntity();
			return true;
		}
		catch (ResourceAccessException exception) {
			throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, "opencode",
					"The OpenCode server did not answer on the configured port.");
		}
		catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 404) {
				return false;
			}
			if (exception.getStatusCode().is5xxServerError()) {
				throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, "opencode",
						"The OpenCode server reported that it is not healthy.");
			}
			throw new ApiException(ErrorCode.PORT_BUSY, "opencode",
					"Another process answered on the OpenCode port.");
		}
	}

	/**
	 * Aborts the running execution of the session, retrying a transient failure.
	 *
	 * @param sessionId the OpenCode session identifier
	 */
	public void abort(String sessionId) {
		retrier.run(() -> post(ABORT_PATH, sessionId, null));
	}

	/**
	 * Sends exactly one short direction turn to the session, retrying a transient failure.
	 *
	 * @param sessionId the OpenCode session identifier
	 */
	public void sendDirectionTurn(String sessionId) {
		retrier.run(() -> post(PROMPT_ASYNC_PATH, sessionId, directionTurnBody()));
	}

	/**
	 * Looks for the continuation answer of a paused session.
	 *
	 * <p>This is the polling fallback allowed by decision 0034: the messages of the session are read and
	 * a user message created after the pause start, other than the direction turn itself, is treated as
	 * the answer that resumes the procedure. The detector is a heuristic over the documented message
	 * shape; it never sends anything and it never resumes by itself, it only reports the instant it saw.
	 *
	 * @param sessionId the OpenCode session identifier
	 * @param since the pause start instant
	 * @return the instant of the continuation answer, empty when there is none or the server is down
	 */
	public Optional<Instant> findContinuationAnswer(String sessionId, Instant since) {
		try {
			ResponseEntity<String> response = restClient.get().uri(MESSAGE_PATH, sessionId).retrieve()
					.toEntity(String.class);
			return earliestAnswerAfter(response.getBody(), since);
		}
		catch (ResourceAccessException | RestClientResponseException exception) {
			log.debug("The continuation of session {} could not be read", sessionId, exception);
			return Optional.empty();
		}
	}

	private Optional<Instant> earliestAnswerAfter(String body, Instant since) {
		if (body == null || body.isBlank()) {
			return Optional.empty();
		}
		JsonNode root = objectMapper.readTree(body);
		if (root == null || !root.isArray()) {
			return Optional.empty();
		}
		Instant earliest = null;
		for (JsonNode message : root.values()) {
			JsonNode info = message.path("info");
			if (!"user".equals(info.path("role").asString(""))) {
				continue;
			}
			long created = info.path("time").path("created").asLong(0L);
			if (created <= since.toEpochMilli()) {
				continue;
			}
			String text = textOf(message.path("parts"));
			if (text.isBlank() || DIRECTION_PROMPT.equals(text.strip())) {
				continue;
			}
			Instant candidate = Instant.ofEpochMilli(created);
			if (earliest == null || candidate.isBefore(earliest)) {
				earliest = candidate;
			}
		}
		return Optional.ofNullable(earliest);
	}

	private String textOf(JsonNode parts) {
		if (!parts.isArray()) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		for (JsonNode part : parts.values()) {
			if ("text".equals(part.path("type").asString(""))) {
				text.append(part.path("text").asString(""));
			}
		}
		return text.toString();
	}

	private Map<String, Object> directionTurnBody() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("parts", List.of(Map.of("type", "text", "text", DIRECTION_PROMPT)));
		if (providerId != null && modelId != null) {
			Map<String, Object> model = new LinkedHashMap<>();
			model.put("providerID", providerId);
			model.put("modelID", modelId);
			body.put("model", model);
		}
		return body;
	}

	private void post(String path, String sessionId, Object body) {
		try {
			RestClient.RequestBodySpec request = restClient.post().uri(path, sessionId);
			if (body == null) {
				request.retrieve().toBodilessEntity();
			}
			else {
				request.body(body).retrieve().toBodilessEntity();
			}
		}
		catch (ResourceAccessException exception) {
			throw new TransientOpenCodeFailure("The OpenCode server did not answer.", exception);
		}
		catch (RestClientResponseException exception) {
			int status = exception.getStatusCode().value();
			if (status == 408 || status == 429 || exception.getStatusCode().is5xxServerError()) {
				throw new TransientOpenCodeFailure("The OpenCode call failed with status " + status, exception);
			}
			if (status == 404) {
				throw new ApiException(ErrorCode.SESSION_GONE, "sessionId",
						"The OpenCode session no longer exists.");
			}
			throw new ApiException(ErrorCode.PAUSE_FAILED, "opencode",
					"The OpenCode server refused the pause call.");
		}
	}
}
