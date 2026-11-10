package br.com.flexclock.opencode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.platform.SettingKey;
import br.com.flexclock.platform.SettingsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client of the local OpenCode server.
 *
 * <p>It speaks only the documented endpoints: {@code GET /global/health}, {@code GET /session},
 * {@code GET /session/{id}}, {@code POST /session/{id}/abort} and {@code POST /session/{id}/message}.
 * It never starts, signals or kills the OpenCode process, never runs a shell and never reads or writes
 * a file, so it has no process control surface at all (requirement RF-142).
 *
 * <p>The endpoint is read from the settings table on every call and validated again before use, so a
 * row edited outside the product can never point the client away from the loopback (requirement
 * RF-150). The client sends no credentials, no cookie and no authorization header (requirement
 * RF-151). The answer is parsed into closed types with bounded text, because it is untrusted data
 * (requirement RF-152).
 */
@Component
public class OpenCodeClient implements OpenCodePort {

	private static final Logger log = LoggerFactory.getLogger(OpenCodeClient.class);

	/** Maximum number of main sessions returned in one listing, a bound for the response size. */
	public static final int MAX_SESSIONS = 500;

	/** Maximum length of a direction turn, a bound for the payload the product sends. */
	public static final int MAX_DIRECTION_TURN_LENGTH = 2000;

	private static final String SESSION_GONE_DETAIL = "The OpenCode session no longer exists.";

	private static final String UNREACHABLE_DETAIL = "The OpenCode server is unreachable at the configured endpoint.";

	private static final String HEALTH_PATH = "/global/health";
	private static final String SESSION_PATH = "/session";

	private static final Pattern SESSION_ID = Pattern.compile(
			"[A-Za-z0-9_.:-]{1," + OpenCodeSession.MAX_SESSION_ID_LENGTH + "}");

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final BoundedRetry retry;
	private final SettingsService settingsService;

	public OpenCodeClient(RestClient openCodeRestClient, ObjectMapper objectMapper, BoundedRetry openCodeRetry,
			SettingsService settingsService) {
		this.restClient = openCodeRestClient;
		this.objectMapper = objectMapper;
		this.retry = openCodeRetry;
		this.settingsService = settingsService;
	}

	@Override
	public OpenCodeHealth health() {
		String uri = effectiveEndpoint() + HEALTH_PATH;
		try {
			String body = restClient.get().uri(URI.create(uri)).accept(MediaType.APPLICATION_JSON).retrieve()
					.body(String.class);
			return parseHealth(body);
		}
		catch (ResourceAccessException exception) {
			throw unreachable(exception);
		}
		catch (RestClientResponseException exception) {
			throw portBusy(exception.getStatusCode().value());
		}
	}

	@Override
	public List<OpenCodeSession> listMainSessions() {
		String uri = effectiveEndpoint() + SESSION_PATH;
		BoundedRetry.Attempt<String> attempt = withRetry("session.list", ErrorCode.OPENCODE_UNREACHABLE,
				() -> get(uri));
		List<OpenCodeSession> sessions = parseSessions(attempt.value());
		log.debug("OpenCode session.list returned {} main sessions after {} attempt(s)", sessions.size(),
				attempt.attempts());
		return sessions;
	}

	@Override
	public OpenCodeSession getSession(String sessionId) {
		String uri = effectiveEndpoint() + SESSION_PATH + "/" + requireSessionId(sessionId);
		BoundedRetry.Attempt<String> attempt = withRetry("session.get", ErrorCode.OPENCODE_UNREACHABLE,
				() -> get(uri));
		JsonNode node = readTree(attempt.value());
		if (node == null || !node.isObject()) {
			throw new ApiException(ErrorCode.SESSION_GONE, "sessionId", SESSION_GONE_DETAIL);
		}
		return toSession(node);
	}

	@Override
	public void abort(String sessionId) {
		abortAttempt(sessionId);
	}

	@Override
	public void sendDirectionTurn(String sessionId, String directionTurnText) {
		sendDirectionTurnAttempt(sessionId, directionTurnText);
	}

	@Override
	public OpenCodePauseResult pause(String sessionId, String directionTurnText) {
		requireSessionId(sessionId);
		requireDirectionTurn(directionTurnText);

		OpenCodeHealth health;
		try {
			health = health();
		}
		catch (ApiException exception) {
			OpenCodePauseOutcome outcome = exception.errorCode() == ErrorCode.PORT_BUSY
					? OpenCodePauseOutcome.PORT_BUSY
					: OpenCodePauseOutcome.SERVER_DOWN;
			log.warn("Pause refused before the abort, the health check answered {}", exception.errorCode());
			return new OpenCodePauseResult(outcome, false, false, 1, exception.detail());
		}
		if (!health.healthy()) {
			log.warn("Pause refused before the abort, the OpenCode server did not report itself healthy");
			return new OpenCodePauseResult(OpenCodePauseOutcome.PORT_BUSY, false, false, 1,
					"The OpenCode server did not report itself healthy.");
		}

		BoundedRetry.Attempt<Void> abortAttempt;
		try {
			abortAttempt = abortAttempt(sessionId);
		}
		catch (ApiException exception) {
			String detail = exception.detail();
			log.warn("Pause not applied, the abort answered {}: no pause may be recorded", exception.errorCode());
			return new OpenCodePauseResult(outcomeOf(exception.errorCode()), false, false, retry.maxAttempts(),
					detail);
		}

		try {
			BoundedRetry.Attempt<Void> turnAttempt = sendDirectionTurnAttempt(sessionId, directionTurnText);
			return new OpenCodePauseResult(OpenCodePauseOutcome.PAUSED, true, true,
					abortAttempt.attempts() + turnAttempt.attempts(), null);
		}
		catch (ApiException exception) {
			log.warn("Session aborted but the direction turn answered {}", exception.errorCode());
			return new OpenCodePauseResult(OpenCodePauseOutcome.PAUSE_FAILED, true, false,
					abortAttempt.attempts() + retry.maxAttempts(), exception.detail());
		}
	}

	private String effectiveEndpoint() {
		String stored = settingsService.effective(SettingKey.OPENCODE_ENDPOINT);
		try {
			return OpenCodeEndpoint.require(stored);
		}
		catch (OpenCodeEndpoint.InvalidEndpointException exception) {
			throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, "opencode.endpoint", exception.getMessage());
		}
	}

	private BoundedRetry.Attempt<Void> abortAttempt(String sessionId) {
		String uri = effectiveEndpoint() + SESSION_PATH + "/" + requireSessionId(sessionId) + "/abort";
		return withRetry("session.abort", ErrorCode.PAUSE_FAILED, () -> {
			String body = post(uri, null);
			if (!isConfirmed(body)) {
				throw new ApiException(ErrorCode.PAUSE_FAILED, "sessionId",
						"The OpenCode server did not confirm the abort.");
			}
			return null;
		});
	}

	private BoundedRetry.Attempt<Void> sendDirectionTurnAttempt(String sessionId, String directionTurnText) {
		requireDirectionTurn(directionTurnText);
		String uri = effectiveEndpoint() + SESSION_PATH + "/" + requireSessionId(sessionId) + "/message";
		PromptRequest request = new PromptRequest(List.of(new PromptPart("text", directionTurnText)));
		return withRetry("session.prompt", ErrorCode.PAUSE_FAILED, () -> {
			post(uri, request);
			return null;
		});
	}

	private <T> BoundedRetry.Attempt<T> withRetry(String operation, ErrorCode exhaustedCode, Supplier<T> action) {
		try {
			return retry.execute(operation, action);
		}
		catch (BoundedRetry.TransientOpenCodeException exception) {
			if (exception.getCause() instanceof ResourceAccessException) {
				throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, null, UNREACHABLE_DETAIL);
			}
			throw new ApiException(exhaustedCode, null,
					"The OpenCode server kept answering a temporary failure for " + operation + ".");
		}
	}

	private String get(String uri) {
		try {
			return restClient.get().uri(URI.create(uri)).accept(MediaType.APPLICATION_JSON).retrieve()
					.body(String.class);
		}
		catch (ResourceAccessException exception) {
			throw new BoundedRetry.TransientOpenCodeException(UNREACHABLE_DETAIL, exception);
		}
		catch (RestClientResponseException exception) {
			throw finalOrTransient(exception);
		}
	}

	private String post(String uri, Object body) {
		try {
			RestClient.RequestBodySpec request = restClient.post().uri(URI.create(uri))
					.accept(MediaType.APPLICATION_JSON);
			if (body == null) {
				return request.retrieve().body(String.class);
			}
			return request.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
		}
		catch (ResourceAccessException exception) {
			throw new BoundedRetry.TransientOpenCodeException(UNREACHABLE_DETAIL, exception);
		}
		catch (RestClientResponseException exception) {
			throw finalOrTransient(exception);
		}
	}

	private RuntimeException finalOrTransient(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		if (status == 404) {
			return new ApiException(ErrorCode.SESSION_GONE, "sessionId", SESSION_GONE_DETAIL);
		}
		if (status == 408 || status == 429 || status >= 500) {
			return new BoundedRetry.TransientOpenCodeException(
					"The OpenCode server answered with the status " + status + ".", exception);
		}
		return new ApiException(ErrorCode.PAUSE_FAILED, null,
				"The OpenCode server refused the request with the status " + status + ".");
	}

	private OpenCodePauseOutcome outcomeOf(ErrorCode code) {
		return switch (code) {
			case SESSION_GONE -> OpenCodePauseOutcome.SESSION_GONE;
			case PORT_BUSY -> OpenCodePauseOutcome.PORT_BUSY;
			case OPENCODE_UNREACHABLE -> OpenCodePauseOutcome.SERVER_DOWN;
			default -> OpenCodePauseOutcome.PAUSE_FAILED;
		};
	}

	private ApiException unreachable(ResourceAccessException exception) {
		log.debug("The OpenCode endpoint refused the connection or did not answer in time", exception);
		return new ApiException(ErrorCode.OPENCODE_UNREACHABLE, null, UNREACHABLE_DETAIL);
	}

	private OpenCodeHealth parseHealth(String body) {
		JsonNode node;
		try {
			node = readTree(body);
		}
		catch (ApiException exception) {
			throw portBusy(200);
		}
		if (node == null || !node.isObject() || !node.has("healthy")) {
			throw portBusy(200);
		}
		boolean healthy = node.get("healthy").asBoolean(false);
		String version = node.hasNonNull("version")
				? OpenCodeSession.bound(node.get("version").asString(""), 128)
				: null;
		return new OpenCodeHealth(healthy, version);
	}

	private List<OpenCodeSession> parseSessions(String body) {
		JsonNode node = readTree(body);
		if (node == null || !node.isArray()) {
			throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, null,
					"The OpenCode server did not return a session list.");
		}
		List<OpenCodeSession> sessions = new ArrayList<>();
		for (JsonNode element : node) {
			if (!element.isObject()) {
				continue;
			}
			OpenCodeSession session = toSession(element);
			if (session.isMain() && !session.sessionId().isBlank()) {
				sessions.add(session);
				if (sessions.size() == MAX_SESSIONS) {
					break;
				}
			}
		}
		return List.copyOf(sessions);
	}

	private OpenCodeSession toSession(JsonNode node) {
		String sessionId = OpenCodeSession.bound(text(node, "id"), OpenCodeSession.MAX_SESSION_ID_LENGTH);
		String title = OpenCodeSession.bound(text(node, "title"), OpenCodeSession.MAX_TITLE_LENGTH);
		String directory = OpenCodeSession.bound(text(node, "directory"), OpenCodeSession.MAX_DIRECTORY_LENGTH);
		String parentId = OpenCodeSession.bound(text(node, "parentID"), OpenCodeSession.MAX_SESSION_ID_LENGTH);
		return new OpenCodeSession(sessionId, title, directory, parentId.isBlank() ? null : parentId);
	}

	private String text(JsonNode node, String field) {
		return node.hasNonNull(field) ? node.get(field).asString("") : "";
	}

	private JsonNode readTree(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(body);
		}
		catch (RuntimeException exception) {
			throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, null,
					"The OpenCode server returned a payload that is not valid JSON.");
		}
	}

	private boolean isConfirmed(String body) {
		JsonNode node = readTree(body);
		return node != null && node.isBoolean() && node.asBoolean(false);
	}

	private ApiException portBusy(int status) {
		return new ApiException(ErrorCode.PORT_BUSY, null,
				"The port of the OpenCode endpoint answered with the status " + status
						+ " instead of a healthy service.");
	}

	private String requireSessionId(String sessionId) {
		if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "sessionId",
					"The session identifier is malformed.");
		}
		return sessionId;
	}

	private void requireDirectionTurn(String directionTurnText) {
		if (directionTurnText == null || directionTurnText.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "directionTurn",
					"The direction turn must not be blank.");
		}
		if (directionTurnText.length() > MAX_DIRECTION_TURN_LENGTH) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "directionTurn",
					"The direction turn is longer than " + MAX_DIRECTION_TURN_LENGTH + " characters.");
		}
	}

	/**
	 * Payload of one prompt, the shape the OpenCode server accepts.
	 *
	 * @param parts the parts of the message
	 */
	private record PromptRequest(List<PromptPart> parts) {
	}

	/**
	 * One text part of a prompt.
	 *
	 * @param type the part type, always {@code text}
	 * @param text the text of the part
	 */
	private record PromptPart(String type, String text) {
	}
}
