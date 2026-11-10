package br.com.flexclock.opencode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.platform.SettingKey;
import br.com.flexclock.platform.SettingsService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the contract of the OpenCode client against a real local HTTP server: the endpoints it calls,
 * the filtering of subagent sessions, the bounded text of the untrusted payload, the retry of decision
 * 0046 and the absence of a phantom pause.
 */
class OpenCodeClientTest {

	private static final String DIRECTION_TURN = "Ask the user whether to continue.";

	private final SettingsService settingsService = mock(SettingsService.class);

	private final AtomicInteger waits = new AtomicInteger();

	private StubOpenCodeServer server;

	private OpenCodeClient client;

	@BeforeEach
	void startServer() throws IOException {
		server = new StubOpenCodeServer();
		client = newClient(server.baseUrl());
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void theHealthPayloadIsReadFromTheServer() {
		server.reply("GET", "/global/health", 200, "{\"healthy\":true,\"version\":\"1.0.7\"}");

		OpenCodeHealth health = client.health();

		assertThat(health.healthy()).isTrue();
		assertThat(health.version()).isEqualTo("1.0.7");
	}

	@Test
	void anUnreachableServerIsReportedAsUnreachable() {
		OpenCodeClient unreachable = newClient("http://127.0.0.1:" + closedPort());

		assertThatThrownBy(unreachable::health).isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.OPENCODE_UNREACHABLE);
	}

	@Test
	void anotherProcessOnThePortIsReportedAsPortBusy() {
		server.reply("GET", "/global/health", 200, "<html>not opencode</html>");

		assertThatThrownBy(client::health).isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.PORT_BUSY);
	}

	@Test
	void onlyTheMainSessionsAreListed() {
		server.reply("GET", "/session", 200, """
				[
				  {"id":"ses_main","title":"Main","directory":"/home/user/project","parentID":null},
				  {"id":"ses_child","title":"Child","directory":"/home/user/project","parentID":"ses_main"},
				  {"id":"ses_other","title":"Other","directory":"/tmp/other"}
				]
				""");

		List<OpenCodeSession> sessions = client.listMainSessions();

		assertThat(sessions).extracting(OpenCodeSession::sessionId).containsExactly("ses_main", "ses_other");
		assertThat(sessions.getFirst().title()).isEqualTo("Main");
		assertThat(sessions.getFirst().directory()).isEqualTo("/home/user/project");
	}

	@Test
	void anUntrustedTitleIsBoundedBeforeItCanBeStored() {
		String longTitle = "x".repeat(OpenCodeSession.MAX_TITLE_LENGTH + 200);
		server.reply("GET", "/session/ses_main", 200,
				"{\"id\":\"ses_main\",\"title\":\"" + longTitle + "\",\"directory\":\"/tmp\"}");

		OpenCodeSession session = client.getSession("ses_main");

		assertThat(session.title()).hasSize(OpenCodeSession.MAX_TITLE_LENGTH);
	}

	@Test
	void aSessionThatNoLongerExistsIsReportedAsGone() {
		server.reply("GET", "/session/ses_gone", 404, "{\"error\":\"not found\"}");

		assertThatThrownBy(() -> client.getSession("ses_gone")).isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.SESSION_GONE);
	}

	@Test
	void aMalformedSessionIdentifierIsRefusedBeforeAnyCall() {
		assertThatThrownBy(() -> client.getSession("../../etc/passwd")).isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(server.requests()).isEmpty();
	}

	@Test
	void theAbortIsRepeatedWhileTheServerAnswersATransientFailure() {
		server.reply("GET", "/global/health", 200, "{\"healthy\":true}");
		server.reply("POST", "/session/ses_main/abort", 500, "{}");
		server.reply("POST", "/session/ses_main/abort", 503, "{}");
		server.reply("POST", "/session/ses_main/abort", 200, "true");
		server.reply("POST", "/session/ses_main/prompt_async", 200, "{}");

		OpenCodePauseResult result = client.pause("ses_main", "Ask the user whether to continue.");

		assertThat(result.outcome()).isEqualTo(OpenCodePauseOutcome.PAUSED);
		assertThat(server.count("POST /session/ses_main/abort")).isEqualTo(3);
	}

	@Test
	void anAbortThatTheServerDoesNotConfirmIsAFailure() {
		server.reply("GET", "/global/health", 200, "{\"healthy\":true}");
		server.reply("POST", "/session/ses_main/abort", 200, "false");

		OpenCodePauseResult result = client.pause("ses_main", "Ask the user whether to continue.");

		assertThat(result.isPaused()).isFalse();
		assertThat(result.abortApplied()).isFalse();
		assertThat(result.outcome()).isEqualTo(OpenCodePauseOutcome.PAUSE_FAILED);
		assertThat(server.count("POST /session/ses_main/prompt_async")).isZero();
	}

	@Test
	void aPauseIsReportedOnlyWhenTheAbortAndTheDirectionTurnSucceeded() {
		server.reply("GET", "/global/health", 200, "{\"healthy\":true,\"version\":\"1.0.7\"}");
		server.reply("POST", "/session/ses_main/abort", 200, "true");
		server.reply("POST", "/session/ses_main/prompt_async", 200, "{}");

		OpenCodePauseResult result = client.pause("ses_main", "Ask the user whether to continue.");

		assertThat(result.outcome()).isEqualTo(OpenCodePauseOutcome.PAUSED);
		assertThat(result.abortApplied()).isTrue();
		assertThat(result.directionTurnSent()).isTrue();
		assertThat(result.isPaused()).isTrue();
		// The turn is admitted asynchronously, so the pause never blocks on the open question.
		assertThat(server.count("POST /session/ses_main/prompt_async")).isEqualTo(1);
		assertThat(server.count("POST /session/ses_main/message")).isZero();
	}

	@Test
	void aRefusedAbortNeverProducesAPhantomPause() {
		server.reply("GET", "/global/health", 200, "{\"healthy\":true}");
		server.reply("POST", "/session/ses_main/abort", 200, "false");

		OpenCodePauseResult result = client.pause("ses_main", "Ask the user whether to continue.");

		assertThat(result.isPaused()).isFalse();
		assertThat(result.outcome()).isEqualTo(OpenCodePauseOutcome.PAUSE_FAILED);
		assertThat(result.abortApplied()).isFalse();
		assertThat(server.count("POST /session/ses_main/prompt_async")).isZero();
	}

	@Test
	void aServerThatIsDownRefusesThePauseBeforeTheAbort() {
		server.reply("GET", "/global/health", 503, "{}");

		OpenCodePauseResult result = client.pause("ses_main", "Ask the user whether to continue.");

		assertThat(result.outcome()).isEqualTo(OpenCodePauseOutcome.PORT_BUSY);
		assertThat(result.abortApplied()).isFalse();
		assertThat(server.count("POST /session/ses_main/abort")).isZero();
	}

	@Test
	void aServerThatDoesNotReportItselfHealthyRefusesThePause() {
		server.reply("GET", "/global/health", 200, "{\"healthy\":false}");

		OpenCodePauseResult result = client.pause("ses_main", "Ask the user whether to continue.");

		assertThat(result.outcome()).isEqualTo(OpenCodePauseOutcome.PORT_BUSY);
		assertThat(result.abortApplied()).isFalse();
		assertThat(server.count("POST /session/ses_main/abort")).isZero();
	}

	@Test
	void anUnreachableServerRefusesThePauseAsServerDown() {
		OpenCodeClient unreachable = newClient("http://127.0.0.1:" + closedPort());

		OpenCodePauseResult result = unreachable.pause("ses_main", "Ask the user whether to continue.");

		assertThat(result.outcome()).isEqualTo(OpenCodePauseOutcome.SERVER_DOWN);
		assertThat(result.isPaused()).isFalse();
	}

	@Test
	void anAbortThatNeverSucceedsIsReportedAfterTheBoundedRetry() {
		server.reply("GET", "/global/health", 200, "{\"healthy\":true}");
		for (int attempt = 0; attempt < 3; attempt++) {
			server.reply("POST", "/session/ses_main/abort", 504, "{}");
		}

		OpenCodePauseResult result = client.pause("ses_main", "Ask the user whether to continue.");

		assertThat(result.outcome()).isEqualTo(OpenCodePauseOutcome.PAUSE_FAILED);
		assertThat(result.attempts()).isEqualTo(3);
		assertThat(server.count("POST /session/ses_main/abort")).isEqualTo(3);
	}

	@Test
	void aDirectionTurnThatFailsAfterTheAbortKeepsTheFactsApart() {
		server.reply("GET", "/global/health", 200, "{\"healthy\":true}");
		server.reply("POST", "/session/ses_main/abort", 200, "true");
		for (int attempt = 0; attempt < 3; attempt++) {
			server.reply("POST", "/session/ses_main/prompt_async", 500, "{}");
		}

		OpenCodePauseResult result = client.pause("ses_main", "Ask the user whether to continue.");

		assertThat(result.isPaused()).isFalse();
		assertThat(result.abortApplied()).isTrue();
		assertThat(result.directionTurnSent()).isFalse();
	}

	@Test
	void aDirectionTurnOutsideTheBoundIsRefusedBeforeAnyCall() {
		assertThatThrownBy(() -> client.pause("ses_main",
				"x".repeat(OpenCodeClient.MAX_DIRECTION_TURN_LENGTH + 1)))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
		assertThat(server.requests()).isEmpty();
	}

	@Test
	void aStoredEndpointOutsideTheLoopbackIsRefusedOnUse() {
		when(settingsService.effective(SettingKey.OPENCODE_ENDPOINT)).thenReturn("http://10.0.0.5:4096");

		assertThatThrownBy(client::health).isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.OPENCODE_UNREACHABLE);
	}

	@Test
	void everyCallResolvesTheEndpointFromTheSettingsSoTheChangeHitsTheSecondServer() throws IOException {
		StubOpenCodeServer second = new StubOpenCodeServer();
		try {
			second.reply("GET", "/global/health", 200, "{\"healthy\":true}");
			second.reply("POST", "/session/ses_main/abort", 200, "true");
			second.reply("POST", "/session/ses_main/prompt_async", 200, "{}");
			when(settingsService.effective(SettingKey.OPENCODE_ENDPOINT)).thenReturn(second.baseUrl());

			OpenCodePauseResult result = client.pause("ses_main", "Ask the user whether to continue.");

			assertThat(result.isPaused()).isTrue();
			assertThat(second.requests()).containsExactly("GET /global/health",
					"POST /session/ses_main/abort", "POST /session/ses_main/prompt_async");
			assertThat(server.requests()).isEmpty();
		}
		finally {
			second.close();
		}
	}

	@Test
	void theContinuationAnswerIsTheEarliestUserMessageAfterThePauseStart() {
		Instant since = Instant.parse("2026-11-11T02:00:00Z");
		server.reply("GET", "/session/ses_main/message", 200, messages(since));

		Optional<Instant> answer = client.findContinuationAnswer("ses_main", since, DIRECTION_TURN);

		assertThat(answer).contains(since.plusSeconds(30));
	}

	@Test
	void aServerWithoutTheMessageEndpointYieldsNoAnswer() {
		Instant since = Instant.parse("2026-11-11T02:00:00Z");
		server.reply("GET", "/session/ses_main/message", 500, "{}");

		assertThat(client.findContinuationAnswer("ses_main", since, DIRECTION_TURN)).isEmpty();
	}

	@Test
	void aMalformedMessagePayloadYieldsNoAnswer() {
		Instant since = Instant.parse("2026-11-11T02:00:00Z");
		server.reply("GET", "/session/ses_main/message", 200, "<html>not a message list</html>");

		assertThat(client.findContinuationAnswer("ses_main", since, DIRECTION_TURN)).isEmpty();
	}

	private String messages(Instant since) {
		String directionTurn = JsonMapper.builder().build().writeValueAsString(DIRECTION_TURN);
		return """
				[
				  {"info":{"role":"user","time":{"created":%d}},"parts":[{"type":"text","text":"older question"}]},
				  {"info":{"role":"assistant","time":{"created":%d}},"parts":[{"type":"text","text":"working"}]},
				  {"info":{"role":"user","time":{"created":%d}},"parts":[{"type":"text","text":%s}]},
				  {"info":{"role":"user","time":{"created":%d}},"parts":[{"type":"text","text":"SIM"}]},
				  {"info":{"role":"user","time":{"created":%d}},"parts":[{"type":"text","text":"SIM, continue"}]}
				]
				""".formatted(since.minusSeconds(10).toEpochMilli(), since.plusSeconds(5).toEpochMilli(),
				since.plusSeconds(20).toEpochMilli(), directionTurn, since.plusSeconds(30).toEpochMilli(),
				since.plusSeconds(40).toEpochMilli());
	}

	private OpenCodeClient newClient(String endpoint) {
		when(settingsService.effective(SettingKey.OPENCODE_ENDPOINT)).thenReturn(endpoint);
		BoundedRetry retry = new BoundedRetry(3, Duration.ofMillis(1), 2.0d, wait -> waits.incrementAndGet());
		return new OpenCodeClient(RestClient.builder().build(), JsonMapper.builder().build(), retry, settingsService);
	}

	private int closedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not allocate a closed port for the test.", exception);
		}
	}

	/**
	 * Minimal OpenCode server used by the test: it answers a scripted sequence per method and path and
	 * records every request, so a test can prove what the client called and how often.
	 */
	private static final class StubOpenCodeServer implements AutoCloseable {

		private final HttpServer server;

		private final Map<String, Deque<Reply>> scripted = new ConcurrentHashMap<>();

		private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

		private StubOpenCodeServer() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", this::handle);
			server.start();
		}

		private void reply(String method, String path, int status, String body) {
			scripted.computeIfAbsent(method + " " + path, key -> new ArrayDeque<>())
					.add(new Reply(status, body));
		}

		private String baseUrl() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		private List<String> requests() {
			synchronized (requests) {
				return List.copyOf(requests);
			}
		}

		private int count(String methodAndPath) {
			synchronized (requests) {
				return (int) requests.stream().filter(methodAndPath::equals).count();
			}
		}

		private void handle(HttpExchange exchange) throws IOException {
			String methodAndPath = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
			requests.add(methodAndPath);
			exchange.getRequestBody().readAllBytes();
			Deque<Reply> script = scripted.get(methodAndPath);
			Reply reply = script == null ? null : script.poll();
			if (reply == null) {
				write(exchange, 500, "{\"error\":\"no scripted reply for " + methodAndPath + "\"}");
				return;
			}
			write(exchange, reply.status(), reply.body());
		}

		private void write(HttpExchange exchange, int status, String body) throws IOException {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(bytes);
			}
		}

		@Override
		public void close() {
			server.stop(0);
		}

		private record Reply(int status, String body) {
		}
	}
}
