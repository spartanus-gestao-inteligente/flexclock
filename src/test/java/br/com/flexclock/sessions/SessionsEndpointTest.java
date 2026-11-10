package br.com.flexclock.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.common.InstanceDescriptor;
import br.com.flexclock.dto.AnchorResponse;
import br.com.flexclock.dto.AnchorSummary;
import br.com.flexclock.dto.ProcedureResponse;
import br.com.flexclock.dto.SessionSummary;
import br.com.flexclock.opencode.OpenCodeSession;
import br.com.flexclock.support.FlywayTestDatabase;
import br.com.flexclock.support.MutableClock;
import br.com.flexclock.support.StubOpenCodePort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the sessions, anchors, procedure and clock lifecycle over the real HTTP surface, against a
 * real SQLite file.
 *
 * <p>The requests go through the whole filter chain, so the loopback allowlist, the instance
 * identifier and the idempotency rule are exercised, not bypassed. The OpenCode port is the stub of the
 * platform workstream and the clock is injected, so the test asserts exact instants and durations
 * (requirements RNF-054 and RNF-056).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SessionsEndpointTest.TestSupport.class)
class SessionsEndpointTest {

	private static final Path DATABASE = Path.of("target", "flexclock-ws-b2", "sessions-endpoint.db");

	private static final int TEST_PORT = 18080;

	private static final String ALLOWED_HOST = "localhost:" + TEST_PORT;

	private static final Instant BASE = Instant.parse("2026-11-10T12:00:00Z");

	static {
		FlywayTestDatabase.reset(DATABASE);
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> FlywayTestDatabase.url(DATABASE));
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private InstanceDescriptor instanceDescriptor;

	@Autowired
	private MutableClock clock;

	@Autowired
	private StubOpenCodePort openCode;

	@BeforeEach
	void setUp() {
		clock.set(BASE);
		openCode.setAvailable(true);
	}

	@Test
	void theSelectorListsOnlyTheMainSessions() throws Exception {
		openCode.add(new OpenCodeSession("ses_main", "Main session", "/home/user/project", null));
		openCode.add(new OpenCodeSession("ses_child", "Subagent", "/home/user/project", "ses_main"));

		List<SessionSummary> sessions = readList(get("/opencode/sessions"), SessionSummary.class);

		assertThat(sessions).extracting(SessionSummary::sessionId).contains("ses_main").doesNotContain("ses_child");
	}

	@Test
	void anchoringASessionCreatesTheAnchorAndTheProcedure() throws Exception {
		givenSession("ses_a1", "Implement the clock");

		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/anchors", "anchor-a1")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"ses_a1\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		AnchorResponse anchor = read(result, AnchorResponse.class);
		assertThat(anchor.anchorId()).isNotNull();
		assertThat(anchor.procedureId()).isNotNull();
		assertThat(anchor.sessionId()).isEqualTo("ses_a1");
		assertThat(anchor.title()).isEqualTo("Implement the clock");
		assertThat(anchor.clockState().running()).isFalse();

		List<AnchorSummary> anchors = readList(get("/anchors"), AnchorSummary.class);
		assertThat(anchors).anySatisfy(summary -> {
			assertThat(summary.anchorId()).isEqualTo(anchor.anchorId());
			assertThat(summary.procedureStatus().wireValue()).isEqualTo("active");
			assertThat(summary.directory()).isEqualTo("/home/user/project");
		});
	}

	@Test
	void anchoringTwiceIsRejectedAndKeepsTheSingleAnchor() throws Exception {
		givenSession("ses_a2", "Implement the clock");
		anchor("ses_a2", "anchor-a2-first");

		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/anchors", "anchor-a2-second")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"ses_a2\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(result)).isEqualTo("ANCHOR_EXISTS");
	}

	@Test
	void theSameIdempotencyKeyReplaysTheOriginalAnswer() throws Exception {
		givenSession("ses_a3", "Implement the clock");
		AnchorResponse first = anchor("ses_a3", "anchor-a3");

		MvcResult replay = mockMvc.perform(write(HttpMethod.POST, "/anchors", "anchor-a3")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"ses_a3\"}"))
				.andReturn();

		assertThat(replay.getResponse().getStatus()).isEqualTo(201);
		assertThat(read(replay, AnchorResponse.class).anchorId()).isEqualTo(first.anchorId());
	}

	@Test
	void theSameIdempotencyKeyWithADifferentPayloadIsAConflict() throws Exception {
		givenSession("ses_a4", "Implement the clock");
		givenSession("ses_a5", "Another session");
		anchor("ses_a4", "anchor-a4");

		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/anchors", "anchor-a4")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"ses_a5\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(result)).isEqualTo("IDEMPOTENCY_CONFLICT");
	}

	@Test
	void aWriteWithoutTheIdempotencyKeyIsRejected() throws Exception {
		givenSession("ses_a6", "Implement the clock");

		MvcResult rejected = mockMvc.perform(api(HttpMethod.POST, "/anchors")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"ses_a6\"}"))
				.andReturn();

		assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(rejected)).isEqualTo("IDEMPOTENCY_KEY_MISSING");
	}

	@Test
	void aSubagentSessionIsNeverAnchored() throws Exception {
		openCode.add(new OpenCodeSession("ses_child_2", "Subagent", "/home/user/project", "ses_main"));

		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/anchors", "anchor-child")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"ses_child_2\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
	}

	@Test
	void anchoringASessionTheServerDoesNotKnowIsSessionGone() throws Exception {
		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/anchors", "anchor-missing")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"ses_missing\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(code(result)).isEqualTo("SESSION_GONE");
	}

	@Test
	void anchoringWhileTheServerIsDownIsUnreachable() throws Exception {
		givenSession("ses_a7", "Implement the clock");
		openCode.setAvailable(false);

		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/anchors", "anchor-down")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"ses_a7\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(result)).isEqualTo("OPENCODE_UNREACHABLE");
	}

	@Test
	void theHeartbeatRefreshesTheAnchor() throws Exception {
		givenSession("ses_a8", "Implement the clock");
		AnchorResponse anchor = anchor("ses_a8", "anchor-a8");
		clock.advance(Duration.ofSeconds(30));

		MvcResult beat = mockMvc.perform(write(HttpMethod.POST, "/anchors/" + anchor.anchorId() + "/heartbeat",
				"beat-a8")).andReturn();

		assertThat(beat.getResponse().getStatus()).isEqualTo(204);
		List<AnchorSummary> anchors = readList(get("/anchors"), AnchorSummary.class);
		assertThat(anchors).anySatisfy(summary -> {
			assertThat(summary.anchorId()).isEqualTo(anchor.anchorId());
			assertThat(summary.updatedAt()).isEqualTo(BASE.plusSeconds(30));
		});
	}

	@Test
	void theHeartbeatOfAnUnknownAnchorIsNotFound() throws Exception {
		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/anchors/999999/heartbeat", "beat-missing"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(code(result)).isEqualTo("ANCHOR_NOT_FOUND");
	}

	@Test
	void completingAProcedureWritesTheEndInstantOnce() throws Exception {
		givenSession("ses_d1", "Finishing window");
		AnchorResponse anchor = anchor("ses_d1", "anchor-d1");

		MvcResult completed = mockMvc.perform(write(HttpMethod.POST,
				"/procedures/" + anchor.procedureId() + "/complete", "complete-d1")).andReturn();

		assertThat(completed.getResponse().getStatus()).isEqualTo(200);
		ProcedureResponse procedure = read(completed, ProcedureResponse.class);
		assertThat(procedure.status().wireValue()).isEqualTo("completed");
		assertThat(procedure.endedAt()).isEqualTo(BASE);

		MvcResult again = mockMvc.perform(write(HttpMethod.POST,
				"/procedures/" + anchor.procedureId() + "/complete", "complete-d1-again")).andReturn();
		assertThat(again.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(again)).isEqualTo("INVALID_PROCEDURE_TRANSITION");
	}

	@Test
	void abortingAProcedureWritesTheEndInstant() throws Exception {
		givenSession("ses_d2", "Aborted window");
		AnchorResponse anchor = anchor("ses_d2", "anchor-d2");

		MvcResult aborted = mockMvc.perform(write(HttpMethod.POST,
				"/procedures/" + anchor.procedureId() + "/abort", "abort-d2")).andReturn();

		assertThat(aborted.getResponse().getStatus()).isEqualTo(200);
		assertThat(read(aborted, ProcedureResponse.class).status().wireValue()).isEqualTo("aborted");
	}

	@Test
	void theTransitionOperationAppliesOnlyFinalStatuses() throws Exception {
		givenSession("ses_d3", "Forwarded window");
		AnchorResponse anchor = anchor("ses_d3", "anchor-d3");

		MvcResult active = mockMvc.perform(write(HttpMethod.POST,
				"/procedures/" + anchor.procedureId() + "/transition", "transition-active")
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"active\"}"))
				.andReturn();
		assertThat(active.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(active)).isEqualTo("INVALID_PROCEDURE_TRANSITION");

		MvcResult completed = mockMvc.perform(write(HttpMethod.POST,
				"/procedures/" + anchor.procedureId() + "/transition", "transition-completed")
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"completed\"}"))
				.andReturn();
		assertThat(completed.getResponse().getStatus()).isEqualTo(200);
		assertThat(read(completed, ProcedureResponse.class).status().wireValue()).isEqualTo("completed");
	}

	@Test
	void theHistoryIsFilteredByStatus() throws Exception {
		givenSession("ses_d4", "History window");
		AnchorResponse anchor = anchor("ses_d4", "anchor-d4");
		mockMvc.perform(write(HttpMethod.POST, "/procedures/" + anchor.procedureId() + "/abort", "abort-d4"))
				.andReturn();

		List<ProcedureResponse> procedures = readList(get("/procedures").param("status", "aborted"), ProcedureResponse.class);

		assertThat(procedures).anySatisfy(procedure -> {
			assertThat(procedure.procedureId()).isEqualTo(anchor.procedureId());
			assertThat(procedure.status().wireValue()).isEqualTo("aborted");
		});

		MvcResult outsideTheClosedSet = mockMvc.perform(get("/procedures").param("status", "unknown")).andReturn();
		assertThat(outsideTheClosedSet.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(outsideTheClosedSet)).isEqualTo("INVALID_ENUM_VALUE");
	}

	private void givenSession(String sessionId, String title) {
		openCode.add(new OpenCodeSession(sessionId, title, "/home/user/project", null));
	}

	private AnchorResponse anchor(String sessionId, String idempotencyKey) throws Exception {
		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/anchors", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"" + sessionId + "\"}"))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return read(result, AnchorResponse.class);
	}

	private MockHttpServletRequestBuilder api(HttpMethod method, String path) {
		return request(method, ContractVersion.API_BASE_PATH + path)
				.header(HttpHeaders.HOST, ALLOWED_HOST)
				.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId())
				.header(ContractVersion.VERSION_HEADER, ContractVersion.VERSION);
	}

	private MockHttpServletRequestBuilder get(String path) {
		return api(HttpMethod.GET, path);
	}

	private MockHttpServletRequestBuilder write(HttpMethod method, String path, String idempotencyKey) {
		return api(method, path).header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
	}

	private <T> T read(MvcResult result, Class<T> type) throws Exception {
		return objectMapper.readValue(result.getResponse().getContentAsString(), type);
	}

	private <T> List<T> readList(MockHttpServletRequestBuilder requestBuilder, Class<T> type) throws Exception {
		MvcResult result = mockMvc.perform(requestBuilder).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
		List<T> values = new ArrayList<>();
		for (JsonNode node : array) {
			values.add(objectMapper.readValue(node.toString(), type));
		}
		return values;
	}

	private String code(MvcResult result) throws Exception {
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return body.get("code").asString();
	}

	/**
	 * Test double of the platform workstream port and of the machine clock.
	 */
	@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
	static class TestSupport {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return MutableClock.at(BASE);
		}

		@Bean
		@Primary
		StubOpenCodePort stubOpenCodePort() {
			return new StubOpenCodePort();
		}
	}
}
