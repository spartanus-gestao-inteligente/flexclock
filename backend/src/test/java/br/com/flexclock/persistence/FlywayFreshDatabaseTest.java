package br.com.flexclock.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import br.com.flexclock.support.FlywayTestDatabase;
import jakarta.persistence.EntityManager;

/**
 * Proves the first install: an empty database is migrated by Flyway and then validated by Hibernate.
 *
 * <p>The context can only start when the migration ran before the entity manager factory was built,
 * because {@code ddl-auto=validate} refuses to work on a schema without tables. The test also proves
 * that the baseline migration carries the canonical wire tokens of the closed enumerators as CHECK
 * constraints, so a value outside the closed set cannot reach a table, and that the forward migration
 * V2 seeds the closed settings catalogue on a fresh installation.
 *
 * <p>Architecture contract, sections 4.3, 4.5 and 11.1; decisions 0017, 0049.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayFreshDatabaseTest {

	private static final Path DIRECTORY = Path.of("target", "flexclock-flyway");

	private static final Path FRESH_DATABASE = DIRECTORY.resolve("fresh.db");

	static {
		FlywayTestDatabase.reset(FRESH_DATABASE);
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> FlywayTestDatabase.url(FRESH_DATABASE));
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Value("${spring.jpa.hibernate.ddl-auto}")
	private String ddlAuto;

	@Test
	void theEmptyDatabaseIsMigratedThroughTheForwardMigrations() {
		List<String> migrations = jdbcTemplate.queryForList(
				"select version || ':' || type || ':' || success from flyway_schema_history order by installed_rank",
				String.class);

		// V1 is the frozen baseline and every later migration is a forward change, so the assertion fixes
		// the baseline entry and accepts the forward ones without pinning their number. What matters is
		// that nothing failed and nothing is pending (decision 0058).
		assertThat(migrations).isNotEmpty();
		assertThat(migrations.getFirst()).isEqualTo("1:SQL:1");
		assertThat(migrations).allSatisfy(entry -> assertThat(entry).endsWith(":SQL:1"));
		assertThat(jdbcTemplate.queryForObject("select count(*) from flyway_schema_history where success = 0",
				Integer.class)).isZero();
		Flyway flyway = FlywayTestDatabase.flyway(FRESH_DATABASE);
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(jdbcTemplate.queryForObject("""
				select count(*) from sqlite_master
				where type = 'table' and name not like 'sqlite_%' and name <> 'flyway_schema_history'
				""", Integer.class)).isEqualTo(14);
	}

	@Test
	void hibernateOnlyValidatesTheSchemaThatFlywayBuilt() {
		assertThat(ddlAuto).isEqualTo("validate");
		assertThat(entityManager.createQuery("select count(s) from Setting s", Long.class).getSingleResult())
				.isEqualTo((long) br.com.flexclock.platform.SettingKey.values().length);
	}

	@Test
	void theMigrationIsIdempotentOnAnAlreadyMigratedDatabase() {
		Flyway flyway = FlywayTestDatabase.flyway(FRESH_DATABASE);

		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(flyway.info().pending()).isEmpty();
	}

	@Test
	void theStatusColumnsOnlyAcceptTheCanonicalWireTokens() {
		String insertAnchor = """
				insert into agent_anchor (session_id, directory, title, status, anchored_at, updated_at)
				values (?, '/home/user/project', 'Anchor', ?, '2026-11-10 09:00:00', '2026-11-10 09:00:00')
				""";

		assertThatThrownBy(() -> jdbcTemplate.update(insertAnchor, "ses_01", "ACTIVE"))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("CHECK");
		assertThat(jdbcTemplate.update(insertAnchor, "ses_01", "active")).isEqualTo(1);
	}

	@Test
	void theTimeModeAndTheWriteSourceOnlyAcceptTheCanonicalWireTokens() {
		String insertWorkSession = """
				insert into work_session (mode, started_at, total_ms, paused_ms, active_ms, timezone)
				values (?, '2026-11-10 09:00:00', 0, 0, 0, 'America/Sao_Paulo')
				""";

		assertThatThrownBy(() -> jdbcTemplate.update(insertWorkSession, "timer"))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("CHECK");
		assertThat(jdbcTemplate.update(insertWorkSession, "project_stopwatch")).isEqualTo(1);

		String insertFailure = """
				insert into failure_event (record_type, failure_type, severity, occurred_at, agent_id, write_source, idempotency_key)
				values ('failure', 'tool_error', 'high', '2026-11-10 09:00:00', 'agent-1', ?, 'key-1')
				""";

		assertThatThrownBy(() -> jdbcTemplate.update(insertFailure, "kafka"))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("CHECK");
		assertThat(jdbcTemplate.update(insertFailure, "event_service")).isEqualTo(1);
	}

	@Test
	void theQualityScoreAndTheLevelsOnlyAcceptTheContractedRange() {
		String insertEvaluation = """
				insert into quality_evaluation (occurred_at, agent_id, score, adherence_level, evidence_level,
					process_level, artifact_level, honesty_level, rationale, authority, write_source, idempotency_key)
				values ('2026-11-10 09:00:00', 'agent-1', ?, 4, 4, 4, 4, 4, 'reason', 'SELF', 'rest', 'key-2')
				""";

		assertThatThrownBy(() -> jdbcTemplate.update(insertEvaluation, 11))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("CHECK");
		assertThat(jdbcTemplate.update(insertEvaluation, 10)).isEqualTo(1);
	}
}
