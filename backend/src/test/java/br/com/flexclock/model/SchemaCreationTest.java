package br.com.flexclock.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import br.com.flexclock.enums.AnchorStatus;
import br.com.flexclock.enums.SettingOwner;
import br.com.flexclock.enums.SettingValueType;
import br.com.flexclock.enums.TimeMode;
import br.com.flexclock.support.SqliteIntegrationTest;
import jakarta.persistence.EntityManager;

/**
 * Proves that the fourteen contract entities produce the fourteen SQLite tables and that the closed
 * enumerators are stored as their canonical wire tokens.
 *
 * <p>The test runs against a real SQLite file, not an in-memory database, because the write ahead log,
 * the foreign keys and the community dialect are part of what the contract requires (architecture
 * contract, section 4.1; requirements RNF-010, RNF-016).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchemaCreationTest extends SqliteIntegrationTest {

	private static final List<String> CONTRACT_TABLES = List.of("settings", "agent_anchor", "procedure",
			"agent_pause", "work_session", "failure_event", "error_detail", "hallucination_event",
			"quality_evaluation", "graph_usage_event", "token_economy_event", "source_event", "download_event",
			"upload_event");

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void theSchemaHoldsTheFourteenContractTables() {
		List<String> tables = jdbcTemplate.queryForList(
				"select name from sqlite_master where type = 'table' and name not like 'sqlite_%'", String.class);
		assertThat(tables).containsAll(CONTRACT_TABLES);
	}

	@Test
	void aSettingKeepsNullableRangeColumnsAndStoresWireTokens() {
		Setting setting = new Setting();
		setting.setKey("software.language");
		setting.setValueType(SettingValueType.ENUM);
		setting.setValue("en");
		setting.setOwner(SettingOwner.AGENT);
		setting.setUpdatedBy("installer");
		setting.setUpdatedAt(Instant.parse("2026-11-10T12:00:00Z"));

		entityManager.persist(setting);
		entityManager.flush();

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"select value_type, owner, value, min_value, max_value from settings where key = ?",
				"software.language");
		assertThat(row.get("value_type")).isEqualTo("enum");
		assertThat(row.get("owner")).isEqualTo("agent");
		assertThat(row.get("value")).isEqualTo("en");
		assertThat(row.get("min_value")).isNull();
		assertThat(row.get("max_value")).isNull();

		entityManager.clear();
		Setting reloaded = entityManager.find(Setting.class, setting.getId());
		assertThat(reloaded.getValueType()).isEqualTo(SettingValueType.ENUM);
		assertThat(reloaded.getOwner()).isEqualTo(SettingOwner.AGENT);
		assertThat(reloaded.getMinValue()).isNull();
	}

	@Test
	void instantsAndEnumsRoundTripThroughSqlite() {
		Instant startedAt = Instant.parse("2026-11-10T09:30:00Z");
		WorkSession workSession = new WorkSession();
		workSession.setMode(TimeMode.PROJECT_STOPWATCH);
		workSession.setTargetSeconds(1500);
		workSession.setStartedAt(startedAt);
		workSession.setTotalMs(0L);
		workSession.setPausedMs(0L);
		workSession.setActiveMs(0L);
		workSession.setTimezone("America/Sao_Paulo");

		entityManager.persist(workSession);
		entityManager.flush();
		entityManager.clear();

		WorkSession reloaded = entityManager.find(WorkSession.class, workSession.getId());
		assertThat(reloaded.getMode()).isEqualTo(TimeMode.PROJECT_STOPWATCH);
		assertThat(reloaded.getStartedAt()).isEqualTo(startedAt);
		assertThat(reloaded.getEndedAt()).isNull();
		assertThat(reloaded.getProcedureId()).isNull();
	}

	@Test
	void anAnchorKeepsTheHeartbeatColumns() {
		Instant anchoredAt = Instant.parse("2026-11-10T10:00:00Z");
		AgentAnchor anchor = new AgentAnchor();
		anchor.setSessionId("ses_01");
		anchor.setDirectory("/home/user/project");
		anchor.setTitle("Implement the clock");
		anchor.setStatus(AnchorStatus.ACTIVE);
		anchor.setAnchoredAt(anchoredAt);
		anchor.setUpdatedAt(anchoredAt);

		entityManager.persist(anchor);
		entityManager.flush();

		String storedStatus = jdbcTemplate.queryForObject(
				"select status from agent_anchor where session_id = ?", String.class, "ses_01");
		assertThat(storedStatus).isEqualTo("active");
	}
}
