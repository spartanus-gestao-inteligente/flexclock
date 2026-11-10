package br.com.flexclock.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import br.com.flexclock.support.FlywayTestDatabase;
import jakarta.persistence.EntityManager;

/**
 * Proves the upgrade path of an installation that was created by Phase 0.
 *
 * <p>The fixture is the schema Hibernate itself created for the fourteen entities, captured from a
 * Phase 0 database, plus two rows of real data. Flyway finds a schema that already holds the tables
 * and no history, so it baselines it at version 1: V1 is marked as applied instead of being replayed
 * over existing tables, the forward migrations then run on top of it, no row is lost and the entity
 * manager validates the result. A migration replayed over existing tables would fail on the first
 * {@code CREATE TABLE}, which is exactly what this test rules out. The settings catalogue of V2 is
 * seeded on the upgraded database without touching the Phase 0 rows (decision 0058).
 *
 * <p>Architecture contract, sections 4.5 and 11.1; decisions 0017, 0049 and 0058.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayPhase0BaselineTest {

	private static final Path DIRECTORY = Path.of("target", "flexclock-flyway");

	private static final Path PHASE0_DATABASE = DIRECTORY.resolve("phase0.db");

	private static final String SEEDED_SETTING_KEY = "software.language";

	static {
		FlywayTestDatabase.reset(PHASE0_DATABASE);
		FlywayTestDatabase.executeScript(PHASE0_DATABASE, "phase-0/schema.sql");
		seedPhase0Rows();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> FlywayTestDatabase.url(PHASE0_DATABASE));
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Test
	void theExistingPhase0DatabaseIsBaselinedInsteadOfReplayingTheBaselineMigration() {
		Integer baselines = jdbcTemplate.queryForObject("""
				select count(*) from flyway_schema_history
				where type = 'BASELINE' and version = '1' and success = 1
				""", Integer.class);
		Integer replayedBaseline = jdbcTemplate.queryForObject("""
				select count(*) from flyway_schema_history where type = 'SQL' and version = '1'
				""", Integer.class);
		Integer failedMigrations = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where success = 0", Integer.class);

		// Exactly one baseline row, at version 1, and no replay of V1 over the existing tables. The
		// number of forward migrations is not pinned, because each workstream adds its own (decision
		// 0058); every migration the classpath offers is proven applied below, the append only triggers
		// of WS-B4 among them.
		assertThat(baselines).isEqualTo(1);
		assertThat(replayedBaseline).isZero();
		assertThat(failedMigrations).isZero();
		Flyway flyway = FlywayTestDatabase.flyway(PHASE0_DATABASE);
		assertThat(flyway.info().pending()).isEmpty();
		List<String> forwardVersions = jdbcTemplate.queryForList(
				"select version from flyway_schema_history where type = 'SQL' order by installed_rank", String.class);
		assertThat(forwardVersions).isNotEmpty();
		assertThat(forwardVersions).doesNotContain("1");
		assertThat(forwardVersions).isSorted();
	}

	@Test
	void thePhase0RowsSurviveTheBaseline() {
		assertThat(jdbcTemplate.queryForObject("select count(*) from settings where key = ?", Integer.class,
				SEEDED_SETTING_KEY)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("select count(*) from work_session", Integer.class)).isEqualTo(1);
	}

	@Test
	void theEntitiesStillMatchThePhase0SchemaAfterTheBaseline() {
		assertThat(entityManager.createQuery("select count(s) from Setting s", Long.class).getSingleResult())
				.isEqualTo(1L + br.com.flexclock.platform.SettingKey.values().length);
		assertThat(entityManager.createQuery("select count(w) from WorkSession w", Long.class).getSingleResult())
				.isEqualTo(1L);
	}

	@Test
	void theBaselineMigrationReproducesThePhase0SchemaExactly() throws SQLException {
		Path migratedDatabase = FlywayTestDatabase.reset(DIRECTORY.resolve("migrated-from-empty.db"));
		Flyway flyway = FlywayTestDatabase.flyway(migratedDatabase, "1");

		// V1 is applied on the empty database and every forward migration follows it, so the assertion
		// only fixes that at least the baseline ran and that nothing is left pending.
		assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);
		assertThat(flyway.info().pending()).isEmpty();

		// The Phase 0 reference is rebuilt from the captured script, because the live fixture database
		// has already received the forward migrations of the workstreams during the context startup.
		Path phase0Reference = FlywayTestDatabase.reset(DIRECTORY.resolve("phase0-reference.db"));
		FlywayTestDatabase.executeScript(phase0Reference, "phase-0/schema.sql");

		Map<String, List<String>> migrated = describeSchema(migratedDatabase);
		Map<String, List<String>> phase0 = describeSchema(phase0Reference);
		assertThat(migrated).hasSize(14);
		assertThat(phase0).hasSize(14);
		assertThat(migrated).isEqualTo(phase0);
	}

	private static void seedPhase0Rows() {
		try (Connection connection = DriverManager.getConnection(FlywayTestDatabase.url(PHASE0_DATABASE));
				Statement statement = connection.createStatement()) {
			statement.execute("""
					insert into settings (key, value_type, value, owner, updated_by, updated_at)
					values ('%s', 'enum', 'pt-BR', 'agent', 'installer', '2026-11-10 09:00:00')
					""".formatted(SEEDED_SETTING_KEY));
			statement.execute("""
					insert into work_session (mode, target_seconds, started_at, total_ms, paused_ms, active_ms, timezone)
					values ('project_stopwatch', 1500, '2026-11-10 09:00:00', 0, 0, 0, 'America/Sao_Paulo')
					""");
		}
		catch (SQLException exception) {
			throw new IllegalStateException("Could not seed the Phase 0 fixture database", exception);
		}
	}

	/**
	 * Describes the fourteen contract tables in a comparable form: every column with its declared type,
	 * its nullability and its position in the primary key, plus every unique constraint as the set of
	 * columns it covers. Column order inside a table is not compared, because the order of the two files
	 * is an artefact of how each one was generated.
	 */
	private static Map<String, List<String>> describeSchema(Path databaseFile) throws SQLException {
		Map<String, List<String>> schema = new TreeMap<>();
		try (Connection connection = DriverManager.getConnection(FlywayTestDatabase.url(databaseFile))) {
			for (String table : contractTables(connection)) {
				List<String> facts = new ArrayList<>(describeColumns(connection, table));
				facts.addAll(describeUniqueConstraints(connection, table));
				schema.put(table, facts);
			}
		}
		return schema;
	}

	private static List<String> contractTables(Connection connection) throws SQLException {
		List<String> tables = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						select name from sqlite_master
						where type = 'table' and name not like 'sqlite_%' and name <> 'flyway_schema_history'
						order by name
						""")) {
			while (resultSet.next()) {
				tables.add(resultSet.getString(1));
			}
		}
		return tables;
	}

	private static List<String> describeColumns(Connection connection, String table) throws SQLException {
		List<String> columns = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("pragma table_info('%s')".formatted(table))) {
			while (resultSet.next()) {
				columns.add("column %s type=%s notnull=%d primaryKey=%d".formatted(resultSet.getString("name"),
						resultSet.getString("type"), resultSet.getInt("notnull"), resultSet.getInt("pk")));
			}
		}
		Collections.sort(columns);
		return columns;
	}

	private static List<String> describeUniqueConstraints(Connection connection, String table) throws SQLException {
		List<String> indexNames = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement
						.executeQuery("pragma index_list('%s')".formatted(table))) {
			while (resultSet.next()) {
				if (resultSet.getInt("unique") == 1) {
					indexNames.add(resultSet.getString("name"));
				}
			}
		}
		List<String> uniqueConstraints = new ArrayList<>();
		for (String indexName : indexNames) {
			List<String> indexColumns = new ArrayList<>();
			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement
							.executeQuery("pragma index_info('%s')".formatted(indexName))) {
				while (resultSet.next()) {
					indexColumns.add(resultSet.getString("name"));
				}
			}
			uniqueConstraints.add("unique(" + String.join(",", indexColumns) + ")");
		}
		Collections.sort(uniqueConstraints);
		return uniqueConstraints;
	}
}
