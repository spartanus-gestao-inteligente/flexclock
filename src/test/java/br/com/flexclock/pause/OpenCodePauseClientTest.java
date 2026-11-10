package br.com.flexclock.pause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the OpenCode adapter of the pause flow against a mock HTTP server.
 *
 * <p>The paths and the payload are the V1 server contract: {@code GET /global/health},
 * {@code GET /session/:id}, {@code POST /session/:id/abort}, {@code POST /session/:id/prompt_async}
 * and {@code GET /session/:id/message}. The test covers the health classification, the session check,
 * the retry of a transient abort failure, the single asynchronous direction turn and the continuation
 * detection (decision 0034; requirements RF-032, RF-034, RF-036, RF-042).
 */
class OpenCodePauseClientTest {

	private static final String BASE_URL = "http://127.0.0.1:4096";

	private static final String SESSION = "ses_01";

	private static final Instant T0 = Instant.parse("2026-11-11T02:00:00Z");

	private MockRestServiceServer server;

	private OpenCodePauseClient client;

	private RecordingSleeper sleeper;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		sleeper = new RecordingSleeper();
		client = new OpenCodePauseClient(builder.build(), new Retrier(sleeper), new ObjectMapper(), "");
	}

	@Test
	void aHealthyServerPassesTheProbe() {
		server.expect(requestTo(BASE_URL + "/global/health"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"healthy\":true,\"version\":\"1.0.0\"}", MediaType.APPLICATION_JSON));

		assertThatCode(client::requireHealthy).doesNotThrowAnyException();
		server.verify();
	}

	@Test
	void aServerErrorIsReportedAsUnreachable() {
		server.expect(requestTo(BASE_URL + "/global/health"))
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

		assertThatThrownBy(client::requireHealthy).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.OPENCODE_UNREACHABLE);
	}

	@Test
	void anotherProcessOnThePortIsReportedAsPortBusy() {
		server.expect(requestTo(BASE_URL + "/global/health")).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(client::requireHealthy).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PORT_BUSY);
	}

	@Test
	void anExistingSessionIsReportedPresent() {
		server.expect(requestTo(BASE_URL + "/session/" + SESSION))
				.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		assertThat(client.sessionExists(SESSION)).isTrue();
	}

	@Test
	void aMissingSessionIsReportedGone() {
		server.expect(requestTo(BASE_URL + "/session/" + SESSION)).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThat(client.sessionExists(SESSION)).isFalse();
	}

	@Test
	void aTransientAbortFailureIsRetriedWithTheContractBackoff() {
		server.expect(requestTo(BASE_URL + "/session/" + SESSION + "/abort"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		server.expect(requestTo(BASE_URL + "/session/" + SESSION + "/abort"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		server.expect(requestTo(BASE_URL + "/session/" + SESSION + "/abort"))
				.andRespond(withStatus(HttpStatus.OK));

		client.abort(SESSION);

		assertThat(sleeper.delays).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
		server.verify();
	}

	@Test
	void anAbortOfAMissingSessionIsNotRetried() {
		server.expect(requestTo(BASE_URL + "/session/" + SESSION + "/abort"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> client.abort(SESSION)).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_GONE);
		assertThat(sleeper.delays).isEmpty();
	}

	@Test
	void theDirectionTurnIsAdmittedAsynchronouslyWithTheContractQuestion() {
		server.expect(requestTo(BASE_URL + "/session/" + SESSION + "/prompt_async"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().string(containsString("Deseja continuar o procedimento?")))
				.andExpect(content().string(containsString("SIM")))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		client.sendDirectionTurn(SESSION);

		server.verify();
	}

	@Test
	void theContinuationAnswerIsTheEarliestUserMessageAfterThePauseStart() {
		server.expect(requestTo(BASE_URL + "/session/" + SESSION + "/message"))
				.andRespond(withSuccess(messages(), MediaType.APPLICATION_JSON));

		Optional<Instant> answer = client.findContinuationAnswer(SESSION, T0);

		assertThat(answer).contains(T0.plusSeconds(30));
	}

	@Test
	void aServerWithoutTheMessageEndpointYieldsNoAnswer() {
		server.expect(requestTo(BASE_URL + "/session/" + SESSION + "/message"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThat(client.findContinuationAnswer(SESSION, T0)).isEmpty();
	}

	private String messages() {
		return """
				[
				  {"info":{"role":"user","time":{"created":%d}},"parts":[{"type":"text","text":"older question"}]},
				  {"info":{"role":"assistant","time":{"created":%d}},"parts":[{"type":"text","text":"working"}]},
				  {"info":{"role":"user","time":{"created":%d}},"parts":[{"type":"text","text":%s}]},
				  {"info":{"role":"user","time":{"created":%d}},"parts":[{"type":"text","text":"SIM"}]},
				  {"info":{"role":"user","time":{"created":%d}},"parts":[{"type":"text","text":"SIM, continue"}]}
				]
				""".formatted(T0.minusSeconds(10).toEpochMilli(), T0.plusSeconds(5).toEpochMilli(),
				T0.plusSeconds(20).toEpochMilli(), jsonText(OpenCodePauseClient.DIRECTION_PROMPT),
				T0.plusSeconds(30).toEpochMilli(), T0.plusSeconds(40).toEpochMilli());
	}

	private String jsonText(String value) {
		return new ObjectMapper().writeValueAsString(value);
	}

	private static final class RecordingSleeper extends Sleeper {

		private final List<Duration> delays = new ArrayList<>();

		@Override
		public void sleep(Duration duration) {
			delays.add(duration);
		}
	}
}
