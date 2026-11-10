package br.com.flexclock.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import br.com.flexclock.common.InstanceDescriptor;
import br.com.flexclock.dto.ClockState;
import br.com.flexclock.dto.WorkSessionResponse;
import br.com.flexclock.dto.WorkSessionResult;
import br.com.flexclock.enums.TimeMode;
import br.com.flexclock.support.MutableClock;
import br.com.flexclock.support.SqliteIntegrationTest;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the time endpoints over the real filters, the real SQLite file and a controllable clock.
 *
 * <p>The tests cover the stopwatch, the project countdown, the timer completion, the stop, the midnight
 * split, the paused time subtraction, the idempotency of a write and the stable errors (requirements
 * RF-020 to RF-027, RF-174).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WorkSessionEndpointTest.ClockConfiguration.class)
class WorkSessionEndpointTest extends SqliteIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-11-11T02:00:00Z");

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

	@BeforeEach
	void resetState() {
		clock.set(T0);
		jdbcTemplate.update("delete from agent_pause");
		jdbcTemplate.update("delete from work_session");
		jdbcTemplate.update("delete from procedure");
		jdbcTemplate.update("delete from agent_anchor");
	}

	@Test
	void aStopwatchCountsUpFromTheRecordedInstants() throws Exception {
		WorkSessionResponse started = start("{\"mode\":\"stopwatch\"}", newKey());

		assertThat(started.mode()).isEqualTo(TimeMode.STOPWATCH);
		assertThat(started.startedAt()).isEqualTo(T0);

		ClockState initialState = current(null);
		assertThat(initialState.running()).isTrue();
		assertThat(initialState.mode()).isEqualTo(TimeMode.STOPWATCH);
		assertThat(initialState.elapsedMs()).isZero();
		assertThat(initialState.remainingMs()).isNull();

		clock.advance(Duration.ofSeconds(90));

		ClockState later = current(null);
		assertThat(later.elapsedMs()).isEqualTo(90_000L);
		assertThat(later.running()).isTrue();
	}

	@Test
	void aProjectCountdownCompletesWithoutStoppingTheSession() throws Exception {
		start("{\"mode\":\"project_stopwatch\",\"targetSeconds\":60}", newKey());

		clock.advance(Duration.ofSeconds(30));
		ClockState halfway = current(null);
		assertThat(halfway.elapsedMs()).isEqualTo(30_000L);
		assertThat(halfway.remainingMs()).isEqualTo(30_000L);
		assertThat(halfway.running()).isTrue();

		clock.advance(Duration.ofSeconds(30));
		ClockState completed = current(null);
		assertThat(completed.remainingMs()).isZero();
		assertThat(completed.running()).isTrue();

		clock.advance(Duration.ofSeconds(30));
		ClockState overdue = current(null);
		assertThat(overdue.remainingMs()).isZero();
		assertThat(overdue.running()).isTrue();
	}

	@Test
	void stoppingFreezesTheTotalPausedAndActiveTime() throws Exception {
		WorkSessionResponse started = start("{\"mode\":\"stopwatch\"}", newKey());
		clock.advance(Duration.ofSeconds(90));

		WorkSessionResult stopped = stop(started.workSessionId(), newKey());

		assertThat(stopped.running()).isFalse();
		assertThat(stopped.totalMs()).isEqualTo(90_000L);
		assertThat(stopped.pausedMs()).isZero();
		assertThat(stopped.activeMs()).isEqualTo(90_000L);
		assertThat(stopped.endedAt()).isEqualTo(T0.plusSeconds(90));
		assertThat(stopped.timerCompleted()).isFalse();

		ClockState afterStop = current(null);
		assertThat(afterStop.running()).isFalse();
		assertThat(afterStop.mode()).isNull();
		assertThat(afterStop.elapsedMs()).isZero();
	}

	@Test
	void aSessionThatCrossesMidnightIsSplitIntoOneSegmentPerLocalDay() throws Exception {
		start("{\"mode\":\"stopwatch\",\"timezone\":\"America/Sao_Paulo\"}", newKey());
		clock.advance(Duration.ofHours(2));

		WorkSessionResult stopped = stop(lastOpenSessionId(), newKey());

		assertThat(stopped.totalMs()).isEqualTo(Duration.ofHours(2).toMillis());
		assertThat(stopped.segments()).hasSize(2);
		assertThat(stopped.segments().get(0).date()).isEqualTo(LocalDate.of(2026, 11, 10));
		assertThat(stopped.segments().get(0).totalMs()).isEqualTo(3_600_000L);
		assertThat(stopped.segments().get(1).date()).isEqualTo(LocalDate.of(2026, 11, 11));
		assertThat(stopped.segments().get(1).totalMs()).isEqualTo(3_600_000L);
	}

	@Test
	void thePausedTimeIsSubtractedAndSplitAcrossMidnight() throws Exception {
		insertProcedure(8001L, "ses_ws");
		insertResumedPause(8001L, "ses_ws", T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofMinutes(90)));

		start("{\"mode\":\"stopwatch\",\"procedureId\":8001,\"timezone\":\"America/Sao_Paulo\"}", newKey());
		clock.advance(Duration.ofHours(2));

		WorkSessionResult stopped = stop(lastOpenSessionId(), newKey());

		assertThat(stopped.totalMs()).isEqualTo(Duration.ofHours(2).toMillis());
		assertThat(stopped.pausedMs()).isEqualTo(Duration.ofHours(1).toMillis());
		assertThat(stopped.activeMs()).isEqualTo(Duration.ofHours(1).toMillis());
		assertThat(stopped.segments()).hasSize(2);
		assertThat(stopped.segments().get(0).pausedMs()).isEqualTo(1_800_000L);
		assertThat(stopped.segments().get(0).activeMs()).isEqualTo(1_800_000L);
		assertThat(stopped.segments().get(1).pausedMs()).isEqualTo(1_800_000L);
		assertThat(stopped.segments().get(1).activeMs()).isEqualTo(1_800_000L);
	}

	@Test
	void aSecondStartForTheSameProcedureIsRefused() throws Exception {
		insertProcedure(8002L, "ses_ws_two");
		start("{\"mode\":\"stopwatch\",\"procedureId\":8002}", newKey());

		mockMvc.perform(write(HttpMethod.POST, "/work-sessions", newKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\":\"stopwatch\",\"procedureId\":8002}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WORK_SESSION_ALREADY_RUNNING"));
	}

	@Test
	void stoppingAnAlreadyStoppedSessionIsRefused() throws Exception {
		WorkSessionResponse started = start("{\"mode\":\"stopwatch\"}", newKey());
		stop(started.workSessionId(), newKey());

		mockMvc.perform(write(HttpMethod.POST, "/work-sessions/" + started.workSessionId() + "/stop", newKey()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WORK_SESSION_NOT_RUNNING"));
	}

	@Test
	void aRepeatedKeyReturnsTheOriginalResultWithoutDuplicatingTheSession() throws Exception {
		String key = newKey();

		WorkSessionResponse first = start("{\"mode\":\"stopwatch\"}", key);
		WorkSessionResponse repeated = start("{\"mode\":\"stopwatch\"}", key);

		assertThat(repeated.workSessionId()).isEqualTo(first.workSessionId());
		assertThat(jdbcTemplate.queryForObject("select count(*) from work_session", Integer.class)).isEqualTo(1);
	}

	@Test
	void theSameKeyWithADifferentPayloadIsRefused() throws Exception {
		String key = newKey();
		start("{\"mode\":\"stopwatch\"}", key);

		mockMvc.perform(write(HttpMethod.POST, "/work-sessions", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\":\"project_stopwatch\",\"targetSeconds\":60}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
	}

	@Test
	void aWriteWithoutTheIdempotencyKeyIsRefused() throws Exception {
		mockMvc.perform(apiRequest(HttpMethod.POST, "/work-sessions", instanceDescriptor.instanceId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\":\"stopwatch\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
	}

	@Test
	void aProjectCountdownRequiresAPositiveTarget() throws Exception {
		mockMvc.perform(write(HttpMethod.POST, "/work-sessions", newKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\":\"project_stopwatch\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		mockMvc.perform(write(HttpMethod.POST, "/work-sessions", newKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\":\"project_stopwatch\",\"targetSeconds\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALUE_OUT_OF_RANGE"));
	}

	@Test
	void anUnknownProcedureIsRefused() throws Exception {
		mockMvc.perform(write(HttpMethod.POST, "/work-sessions", newKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\":\"stopwatch\",\"procedureId\":999999}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void anInvalidTimezoneIsRefused() throws Exception {
		mockMvc.perform(write(HttpMethod.POST, "/work-sessions", newKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\":\"stopwatch\",\"timezone\":\"Mars/Olympus\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void aStopwatchIgnoresTheTargetAndNeverCountsDown() throws Exception {
		WorkSessionResponse started = start("{\"mode\":\"stopwatch\",\"targetSeconds\":60}", newKey());

		WorkSessionResult result = readWorkSession(started.workSessionId());
		assertThat(result.targetSeconds()).isNull();
		assertThat(result.timerCompleted()).isFalse();
		assertThat(current(null).remainingMs()).isNull();
	}

	@Test
	void theCurrentStateCanBeScopedToOneProcedure() throws Exception {
		insertProcedure(8003L, "ses_ws_three");
		insertProcedure(8004L, "ses_ws_four");
		start("{\"mode\":\"stopwatch\",\"procedureId\":8003}", newKey());
		start("{\"mode\":\"project_stopwatch\",\"targetSeconds\":120,\"procedureId\":8004}", newKey());

		ClockState first = current(8003L);
		ClockState second = current(8004L);

		assertThat(first.mode()).isEqualTo(TimeMode.STOPWATCH);
		assertThat(first.procedureId()).isEqualTo(8003L);
		assertThat(second.mode()).isEqualTo(TimeMode.PROJECT_STOPWATCH);
		assertThat(second.remainingMs()).isEqualTo(120_000L);
	}

	private WorkSessionResponse start(String body, String key) throws Exception {
		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/work-sessions", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), WorkSessionResponse.class);
	}

	private WorkSessionResult stop(Long workSessionId, String key) throws Exception {
		MvcResult result = mockMvc.perform(write(HttpMethod.POST, "/work-sessions/" + workSessionId + "/stop", key))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), WorkSessionResult.class);
	}

	private WorkSessionResult readWorkSession(Long workSessionId) throws Exception {
		MvcResult result = mockMvc
				.perform(apiRequest(HttpMethod.GET, "/work-sessions/" + workSessionId,
						instanceDescriptor.instanceId()))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), WorkSessionResult.class);
	}

	private ClockState current(Long procedureId) throws Exception {
		String path = procedureId == null ? "/work-sessions/current"
				: "/work-sessions/current?procedureId=" + procedureId;
		MvcResult result = mockMvc.perform(apiRequest(HttpMethod.GET, path, instanceDescriptor.instanceId()))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), ClockState.class);
	}

	private Long lastOpenSessionId() {
		return jdbcTemplate.queryForObject(
				"select id from work_session where ended_at is null order by started_at desc limit 1", Long.class);
	}

	private void insertProcedure(Long id, String sessionId) {
		jdbcTemplate.update("""
				insert into procedure (id, session_id, title, directory, status, started_at, created_at, updated_at)
				values (?, ?, 'Session', '/tmp/project', 'active', ?, ?, ?)
				""", id, sessionId, T0.toEpochMilli(), T0.toEpochMilli(), T0.toEpochMilli());
	}

	private void insertResumedPause(Long procedureId, String sessionId, Instant start, Instant end) {
		jdbcTemplate.update("""
				insert into agent_pause (procedure_id, session_id, status, pause_start, resume_at, duration_ms)
				values (?, ?, 'resumed', ?, ?, ?)
				""", procedureId, sessionId, start.toEpochMilli(), end.toEpochMilli(),
				Duration.between(start, end).toMillis());
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
