package br.com.flexclock.pause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.common.InstanceDescriptor;
import br.com.flexclock.enums.PauseReason;
import br.com.flexclock.enums.PauseStatus;
import br.com.flexclock.support.MutableClock;
import br.com.flexclock.support.SqliteIntegrationTest;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the pause flow over the real filters, the real SQLite file and a controllable clock.
 *
 * <p>The OpenCode server is replaced by a mock, because the flow must be proven without a live agent:
 * the health classification, the abort, the single direction turn and the continuation detection are
 * boundaries. The tests cover the applied pause, the refusals without a ghost record, the failure of
 * the direction turn, the resume, the 60 minute expiry, the notification contract, the abort reasons,
 * the history and the idempotency (decisions 0034, 0035, 0044, 0045; requirements RF-032 to RF-042).
 */
@SpringBootTest(properties = { "flexclock.pause.continuation-poll-enabled=false",
		"flexclock.pause.expiry-check-initial-delay=1h" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PauseFlowEndpointTest.ClockConfiguration.class)
class PauseFlowEndpointTest extends SqliteIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-11-11T02:00:00Z");

	private static final String SESSION = "ses_pause";

	private static final long ANCHOR_ID = 9101L;

	private static final long PROCEDURE_ID = 9101L;

	private final AtomicLong pauseIdSequence = new AtomicLong(50_000L);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InstanceDescriptor instanceDescriptor;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PauseService pauseService;

	@MockitoBean
	private OpenCodePauseClient openCode;

	@BeforeEach
	void resetState() {
		clock.set(T0);
		jdbcTemplate.update("delete from agent_pause");
		jdbcTemplate.update("delete from work_session");
		jdbcTemplate.update("delete from procedure");
		jdbcTemplate.update("delete from agent_anchor");
		insertAnchorAndProcedure(ANCHOR_ID, SESSION);
		when(openCode.sessionExists(SESSION)).thenReturn(true);
	}

	@Test
	void anAppliedPauseIsPersistedAndExposedAsTheCurrentState() throws Exception {
		PauseResponse pause = pause(ANCHOR_ID, newKey());

		assertThat(pause.status()).isEqualTo(PauseStatus.PAUSED);
		assertThat(pause.pauseStart()).isEqualTo(T0);
		assertThat(jdbcTemplate.queryForObject("select count(*) from agent_pause", Integer.class)).isEqualTo(1);
		assertThat(pauseStatus()).isEqualTo("paused");

		PauseStateResponse state = current(SESSION);
		assertThat(state.status()).isEqualTo(PauseStatus.PAUSED);
		assertThat(state.pausedElapsedMs()).isZero();
		assertThat(state.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
		assertThat(state.expired()).isFalse();
		assertThat(state.expiryDue()).isFalse();

		clock.advance(Duration.ofMinutes(5));
		assertThat(current(SESSION).pausedElapsedMs()).isEqualTo(Duration.ofMinutes(5).toMillis());
	}

	@Test
	void aPauseWithoutAHealthyServerIsRefusedAndWritesNoRecord() throws Exception {
		doThrow(new ApiException(ErrorCode.OPENCODE_UNREACHABLE, "opencode", "down")).when(openCode)
				.requireHealthy();

		mockMvc.perform(write(HttpMethod.POST, "/anchors/" + ANCHOR_ID + "/pause", newKey()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("OPENCODE_UNREACHABLE"));

		assertThat(jdbcTemplate.queryForObject("select count(*) from agent_pause", Integer.class)).isZero();
	}

	@Test
	void aPauseWhenAnotherProcessHoldsThePortIsRefused() throws Exception {
		doThrow(new ApiException(ErrorCode.PORT_BUSY, "opencode", "busy")).when(openCode).requireHealthy();

		mockMvc.perform(write(HttpMethod.POST, "/anchors/" + ANCHOR_ID + "/pause", newKey()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("PORT_BUSY"));

		assertThat(jdbcTemplate.queryForObject("select count(*) from agent_pause", Integer.class)).isZero();
	}

	@Test
	void aPauseWhenTheSessionDisappearedIsRefused() throws Exception {
		when(openCode.sessionExists(SESSION)).thenReturn(false);

		mockMvc.perform(write(HttpMethod.POST, "/anchors/" + ANCHOR_ID + "/pause", newKey()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SESSION_GONE"));

		assertThat(jdbcTemplate.queryForObject("select count(*) from agent_pause", Integer.class)).isZero();
	}

	@Test
	void aPauseWhoseDirectionTurnFailsIsRecordedAsAborted() throws Exception {
		doThrow(new ApiException(ErrorCode.PAUSE_FAILED, "opencode", "refused")).when(openCode)
				.sendDirectionTurn(SESSION);

		mockMvc.perform(write(HttpMethod.POST, "/anchors/" + ANCHOR_ID + "/pause", newKey()))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("PAUSE_FAILED"));

		Map<String, Object> row = jdbcTemplate.queryForMap("select status, reason from agent_pause");
		assertThat(row.get("status")).isEqualTo("aborted");
		assertThat(row.get("reason")).isEqualTo("pause_failed");
		assertThat(procedureStatus()).isEqualTo("active");
	}

	@Test
	void aSecondPauseOfTheSameProcedureIsRefused() throws Exception {
		pause(ANCHOR_ID, newKey());

		mockMvc.perform(write(HttpMethod.POST, "/anchors/" + ANCHOR_ID + "/pause", newKey()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_PAUSE_TRANSITION"));

		assertThat(jdbcTemplate.queryForObject("select count(*) from agent_pause", Integer.class)).isEqualTo(1);
	}

	@Test
	void aPauseOnAStaleAnchorIsRefused() throws Exception {
		jdbcTemplate.update("update agent_anchor set status = 'stale' where id = ?", ANCHOR_ID);

		mockMvc.perform(write(HttpMethod.POST, "/anchors/" + ANCHOR_ID + "/pause", newKey()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ANCHOR_STALE"));
	}

	@Test
	void aPauseOnAMissingAnchorIsRefused() throws Exception {
		mockMvc.perform(write(HttpMethod.POST, "/anchors/999999/pause", newKey()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANCHOR_NOT_FOUND"));
	}

	@Test
	void aPauseWithoutAProcedureIsRefused() throws Exception {
		insertAnchorOnly(9102L, "ses_orphan");

		mockMvc.perform(write(HttpMethod.POST, "/anchors/9102/pause", newKey()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_SESSION_ANCHORED"));
	}

	@Test
	void resumingFromPausedWritesTheDurationAndKeepsTheProcedureActive() throws Exception {
		pause(ANCHOR_ID, newKey());
		clock.advance(Duration.ofMinutes(5));

		PauseResponse resumed = resume(lastPauseId(), newKey());

		assertThat(resumed.status()).isEqualTo(PauseStatus.RESUMED);
		Map<String, Object> row = jdbcTemplate.queryForMap("select status, duration_ms, resume_at from agent_pause");
		assertThat(row.get("status")).isEqualTo("resumed");
		assertThat(((Number) row.get("duration_ms")).longValue()).isEqualTo(Duration.ofMinutes(5).toMillis());
		assertThat(((Number) row.get("resume_at")).longValue()).isEqualTo(T0.plus(Duration.ofMinutes(5)).toEpochMilli());
		assertThat(procedureStatus()).isEqualTo("active");
	}

	@Test
	void resumingFromExpiredIsAllowed() throws Exception {
		long pauseId = insertPause(PROCEDURE_ID, SESSION, "expired", T0.minus(Duration.ofMinutes(90)), null, 3_600_000L,
				null);
		clock.set(T0);

		PauseResponse resumed = resume(pauseId, newKey());

		assertThat(resumed.status()).isEqualTo(PauseStatus.RESUMED);
		assertThat(pauseStatus()).isEqualTo("resumed");
	}

	@Test
	void resumingFromAbortedIsRefused() throws Exception {
		long pauseId = insertPause(PROCEDURE_ID, SESSION, "aborted", T0.minus(Duration.ofMinutes(10)),
				T0.minus(Duration.ofMinutes(5)), 300_000L, "user_cancel");

		mockMvc.perform(write(HttpMethod.POST, "/pauses/" + pauseId + "/resume", newKey()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_PAUSE_TRANSITION"));
	}

	@Test
	void theExpiryJobMarksThePauseExpiredWithoutResumingTheProcedure() throws Exception {
		Instant pauseStart = T0.minus(Duration.ofMinutes(61));
		long pauseId = insertPause(PROCEDURE_ID, SESSION, "paused", pauseStart, null, null, null);

		int expired = pauseService.expireDuePauses();

		assertThat(expired).isEqualTo(1);
		Map<String, Object> row = jdbcTemplate
				.queryForMap("select status, duration_ms, resume_at from agent_pause where id = ?", pauseId);
		assertThat(row.get("status")).isEqualTo("expired");
		assertThat(((Number) row.get("duration_ms")).longValue()).isEqualTo(Duration.ofMinutes(60).toMillis());
		assertThat(row.get("resume_at")).isNull();
		assertThat(procedureStatus()).isEqualTo("active");

		PauseStateResponse state = current(SESSION);
		assertThat(state.expired()).isTrue();
		assertThat(state.expiryDue()).isFalse();
		assertThat(state.pausedElapsedMs()).isEqualTo(Duration.ofMinutes(61).toMillis());

		List<PauseNotification> notifications = notifications();
		assertThat(notifications).hasSize(1);
		PauseNotification notification = notifications.getFirst();
		assertThat(notification.pauseId()).isEqualTo(pauseId);
		assertThat(notification.code()).isEqualTo(PauseNotification.CODE_PAUSE_EXPIRED);
		assertThat(notification.title()).isEqualTo(PauseNotification.TITLE_PAUSE_EXPIRED);
		assertThat(notification.expiredAt()).isEqualTo(pauseStart.plus(Duration.ofMinutes(60)));
		assertThat(notification.repeatIntervalMinutes()).isEqualTo(15);
		assertThat(notification.nextDueAt())
				.isEqualTo(pauseStart.plus(Duration.ofMinutes(60)).plus(Duration.ofMinutes(15)));
		assertThat(notification.procedureTitle()).isEqualTo("Pause session");
	}

	@Test
	void abortingByUserCancelAlsoEndsTheProcedure() throws Exception {
		pause(ANCHOR_ID, newKey());
		clock.advance(Duration.ofMinutes(5));

		PauseResponse aborted = abort(lastPauseId(), PauseReason.USER_CANCEL, newKey());

		assertThat(aborted.status()).isEqualTo(PauseStatus.ABORTED);
		Map<String, Object> row = jdbcTemplate.queryForMap("select status, reason, duration_ms from agent_pause");
		assertThat(row.get("status")).isEqualTo("aborted");
		assertThat(row.get("reason")).isEqualTo("user_cancel");
		assertThat(((Number) row.get("duration_ms")).longValue()).isEqualTo(Duration.ofMinutes(5).toMillis());

		Map<String, Object> procedure = jdbcTemplate.queryForMap("select status, ended_at from procedure");
		assertThat(procedure.get("status")).isEqualTo("aborted");
		assertThat(((Number) procedure.get("ended_at")).longValue())
				.isEqualTo(T0.plus(Duration.ofMinutes(5)).toEpochMilli());
	}

	@Test
	void abortingByPauseFailureKeepsTheProcedureActive() throws Exception {
		pause(ANCHOR_ID, newKey());

		PauseResponse aborted = abort(lastPauseId(), PauseReason.PAUSE_FAILED, newKey());

		assertThat(aborted.status()).isEqualTo(PauseStatus.ABORTED);
		assertThat(jdbcTemplate.queryForObject("select reason from agent_pause", String.class)).isEqualTo("pause_failed");
		assertThat(procedureStatus()).isEqualTo("active");
		assertThat(jdbcTemplate.queryForObject("select ended_at from procedure", Object.class)).isNull();
	}

	@Test
	void theHistoryIsOrderedNewestFirstAndBounded() throws Exception {
		for (int index = 0; index < 3; index++) {
			pause(ANCHOR_ID, newKey());
			resume(lastPauseId(), newKey());
			clock.advance(Duration.ofMinutes(10));
		}

		MvcResult result = mockMvc
				.perform(apiRequest(HttpMethod.GET, "/procedures/" + PROCEDURE_ID + "/pauses?limit=2",
						instanceDescriptor.instanceId()))
				.andExpect(status().isOk())
				.andReturn();
		PauseHistoryItem[] history = objectMapper.readValue(result.getResponse().getContentAsString(),
				PauseHistoryItem[].class);

		assertThat(history).hasSize(2);
		assertThat(history[0].pauseStart()).isAfter(history[1].pauseStart());
		assertThat(history[0].status()).isEqualTo(PauseStatus.RESUMED);
	}

	@Test
	void aRepeatedKeyReturnsTheOriginalPauseWithoutDuplicatingTheRecord() throws Exception {
		String key = newKey();

		PauseResponse first = pause(ANCHOR_ID, key);
		PauseResponse repeated = pause(ANCHOR_ID, key);

		assertThat(repeated.status()).isEqualTo(first.status());
		assertThat(repeated.pauseStart()).isEqualTo(first.pauseStart());
		assertThat(jdbcTemplate.queryForObject("select count(*) from agent_pause", Integer.class)).isEqualTo(1);
	}

	@Test
	void theSameKeyWithADifferentAnchorIsRefused() throws Exception {
		insertAnchorAndProcedure(9102L, "ses_pause_two");
		when(openCode.sessionExists("ses_pause_two")).thenReturn(true);
		String key = newKey();
		pause(ANCHOR_ID, key);

		mockMvc.perform(write(HttpMethod.POST, "/anchors/9102/pause", key))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
	}

	@Test
	void aWriteWithoutTheIdempotencyKeyIsRefused() throws Exception {
		mockMvc.perform(apiRequest(HttpMethod.POST, "/anchors/" + ANCHOR_ID + "/pause",
				instanceDescriptor.instanceId()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
	}

	@Test
	void theCurrentStateOfASessionWithoutAPauseIsNotFound() throws Exception {
		mockMvc.perform(apiRequest(HttpMethod.GET, "/pauses/current?sessionId=" + SESSION,
				instanceDescriptor.instanceId()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void theAbortRequestRequiresAReason() throws Exception {
		pause(ANCHOR_ID, newKey());

		mockMvc.perform(write(HttpMethod.POST, "/pauses/" + lastPauseId() + "/abort", newKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	private PauseResponse pause(long anchorId, String key) throws Exception {
		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/anchors/" + anchorId + "/pause", key))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), PauseResponse.class);
	}

	private PauseResponse resume(long pauseId, String key) throws Exception {
		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/pauses/" + pauseId + "/resume", key))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), PauseResponse.class);
	}

	private PauseResponse abort(long pauseId, PauseReason reason, String key) throws Exception {
		MvcResult result = mockMvc
				.perform(write(HttpMethod.POST, "/pauses/" + pauseId + "/abort", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"" + reason.wireValue() + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), PauseResponse.class);
	}

	private PauseStateResponse current(String sessionId) throws Exception {
		MvcResult result = mockMvc
				.perform(apiRequest(HttpMethod.GET, "/pauses/current?sessionId=" + sessionId,
						instanceDescriptor.instanceId()))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), PauseStateResponse.class);
	}

	private List<PauseNotification> notifications() throws Exception {
		MvcResult result = mockMvc
				.perform(apiRequest(HttpMethod.GET, "/pauses/notifications", instanceDescriptor.instanceId()))
				.andExpect(status().isOk())
				.andReturn();
		return List.of(objectMapper.readValue(result.getResponse().getContentAsString(),
				PauseNotification[].class));
	}

	private long lastPauseId() {
		return jdbcTemplate.queryForObject("select id from agent_pause order by id desc limit 1", Long.class);
	}

	private String pauseStatus() {
		return jdbcTemplate.queryForObject("select status from agent_pause order by id desc limit 1", String.class);
	}

	private String procedureStatus() {
		return jdbcTemplate.queryForObject("select status from procedure where id = ?", String.class, PROCEDURE_ID);
	}

	private void insertAnchorAndProcedure(long id, String sessionId) {
		insertAnchorOnly(id, sessionId);
		jdbcTemplate.update("""
				insert into procedure (id, session_id, title, directory, status, started_at, created_at, updated_at)
				values (?, ?, 'Pause session', '/tmp/pause', 'active', ?, ?, ?)
				""", id, sessionId, T0.toEpochMilli(), T0.toEpochMilli(), T0.toEpochMilli());
	}

	private void insertAnchorOnly(long id, String sessionId) {
		jdbcTemplate.update("""
				insert into agent_anchor (id, session_id, directory, title, status, anchored_at, updated_at)
				values (?, ?, '/tmp/pause', 'Pause session', 'active', ?, ?)
				""", id, sessionId, T0.toEpochMilli(), T0.toEpochMilli());
	}

	private long insertPause(long procedureId, String sessionId, String status, Instant start, Instant end,
			Long durationMs, String reason) {
		long id = pauseIdSequence.incrementAndGet();
		jdbcTemplate.update("""
				insert into agent_pause (id, procedure_id, session_id, status, pause_start, resume_at, duration_ms, reason)
				values (?, ?, ?, ?, ?, ?, ?, ?)
				""", id, procedureId, sessionId, status, start.toEpochMilli(),
				end == null ? null : end.toEpochMilli(), durationMs, reason);
		return id;
	}

	private MockHttpServletRequestBuilder write(HttpMethod method, String path, String idempotencyKey) {
		return writeRequest(method, path, instanceDescriptor.instanceId(), idempotencyKey);
	}

	private String newKey() {
		return UUID.randomUUID().toString();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@org.springframework.context.annotation.Primary
		MutableClock mutableClock() {
			return MutableClock.at(T0);
		}
	}
}
