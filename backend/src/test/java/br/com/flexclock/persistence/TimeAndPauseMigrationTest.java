package br.com.flexclock.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import br.com.flexclock.support.FlywayTestDatabase;

/**
 * Proves the V4 migration of the time and pause workstream.
 *
 * <p>The migration adds no column and no table: it adds the query indexes and the two partial unique
 * indexes that make a ghost pause and a duplicate open work session impossible from a second writer.
 * The test runs on a real SQLite file, so the index definitions and the constraint behaviour are the
 * ones the release ships (decision 0058; requirements RF-030, RF-039, RF-155).
 */
class TimeAndPauseMigrationTest {

	private static final Path DIRECTORY = Path.of("target", "flexclock-flyway");

	private static final Path DATABASE = DIRECTORY.resolve("time-and-pause.db");

	private static final long EPOCH_MILLI = 1_760_000_000_000L;

	@BeforeAll
	static void migrate() {
		FlywayTestDatabase.reset(DATABASE);
		Flyway flyway = FlywayTestDatabase.flyway(DATABASE);
		flyway.migrate();
	}

	@Test
	void theMigrationIsAppliedOnTopOfTheBaseline() {
		Flyway flyway = FlywayTestDatabase.flyway(DATABASE);

		assertThat(flyway.info().pending()).isEmpty();
		// V4 is proven applied by name, so a later workstream that adds V5 and beyond does not break this
		// assertion (decision 0058).
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("4"));
	}

	@Test
	void theMigrationAddsTheTimeAndPauseIndexes() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(indexNames(statement)).contains("ix_work_session_started_at",
					"ux_work_session_open_procedure", "ix_agent_pause_procedure", "ix_agent_pause_session",
					"ix_agent_pause_expiry", "ux_agent_pause_open_procedure");
		}
	}

	@Test
	void theSchemaKeepsOneIndexOnTheProcedureAndStartOfAWorkSession() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			List<String> names = indexNames(statement);

			// V3 owns the index on (procedure_id, started_at). V4 repeated it under another name, and V6
			// drops only that redundant copy (decision 0058 already froze V4, so the correction is a new
			// migration).
			assertThat(names).contains("idx_work_session_procedure_started");
			assertThat(names).doesNotContain("ix_work_session_procedure");
			assertThat(indexColumns(statement, "idx_work_session_procedure_started"))
					.containsExactly("procedure_id", "started_at");
		}
	}

	@Test
	void aProcedureCanHoldOnlyOneUnresolvedPause() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertPause(statement, 1L, "paused");
			assertThatThrownBy(() -> insertPause(statement, 1L, "expired"))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("UNIQUE");
			insertPause(statement, 1L, "aborted");
			insertPause(statement, 2L, "paused");
		}
	}

	@Test
	void aProcedureCanHoldOnlyOneOpenWorkSession() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			// V3 adds the foreign key from work_session to procedure, so the procedure has to exist.
			insertProcedure(statement, 1L);
			insertWorkSession(statement, 1L, null);
			assertThatThrownBy(() -> insertWorkSession(statement, 1L, null))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("UNIQUE");

			statement.executeUpdate("update work_session set ended_at = " + EPOCH_MILLI + " where procedure_id = 1");
			insertWorkSession(statement, 1L, null);
		}
	}

	@Test
	void aWorkSessionWithoutAProcedureIsNeverConstrained() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertWorkSession(statement, null, null);
			insertWorkSession(statement, null, null);
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(FlywayTestDatabase.url(DATABASE));
	}

	private java.util.List<String> indexColumns(Statement statement, String index) throws SQLException {
		java.util.List<String> columns = new java.util.ArrayList<>();
		try (var resultSet = statement.executeQuery("pragma index_info('%s')".formatted(index))) {
			while (resultSet.next()) {
				columns.add(resultSet.getString("name"));
			}
		}
		return columns;
	}

	private java.util.List<String> indexNames(Statement statement) throws SQLException {
		java.util.List<String> names = new java.util.ArrayList<>();
		try (var resultSet = statement.executeQuery("select name from sqlite_master where type = 'index'")) {
			while (resultSet.next()) {
				names.add(resultSet.getString(1));
			}
		}
		return names;
	}

	private void insertPause(Statement statement, long procedureId, String status) throws SQLException {
		statement.executeUpdate("""
				insert into agent_pause (procedure_id, session_id, status, pause_start)
				values (%d, 'ses_migration', '%s', %d)
				""".formatted(procedureId, status, EPOCH_MILLI));
	}

	private void insertProcedure(Statement statement, long procedureId) throws SQLException {
		statement.executeUpdate("""
				insert into procedure (id, session_id, title, directory, status, started_at, created_at, updated_at)
				values (%d, 'ses_migration_%d', 'Migration session', '/tmp', 'active', %d, %d, %d)
				""".formatted(procedureId, procedureId, EPOCH_MILLI, EPOCH_MILLI, EPOCH_MILLI));
	}

	private void insertWorkSession(Statement statement, Long procedureId, Long endedAt) throws SQLException {
		statement.executeUpdate("""
				insert into work_session (procedure_id, mode, started_at, ended_at, total_ms, paused_ms, active_ms, timezone)
				values (%s, 'stopwatch', %d, %s, 0, 0, 0, 'UTC')
				""".formatted(procedureId == null ? "null" : procedureId, EPOCH_MILLI,
				endedAt == null ? "null" : endedAt));
	}
}
