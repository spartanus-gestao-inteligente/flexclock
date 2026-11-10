package br.com.flexclock.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import br.com.flexclock.support.FlywayTestDatabase;

/**
 * Proves the schema rules that migration V3 adds to the sessions, anchors and procedure lifecycle.
 *
 * <p>The migration is applied to a real SQLite file, with foreign keys on, exactly like the release
 * connection. The test then attacks the rules with raw SQL: a work session cannot reference a
 * procedure that does not exist, a procedure cannot have two open work sessions, the end instant of a
 * procedure is written once and a final status is final (requirements RF-020, RF-029, RF-030 and
 * RN-016).
 *
 * <p>Architecture contract, sections 4.5 and 11.1; decisions 0042, 0049 and 0058.
 */
class FlywayV3SessionsTest {

	private static final Path DATABASE = Path.of("target", "flexclock-flyway", "v3-sessions.db");

	private static final String START = "2026-11-10 09:00:00";

	@BeforeAll
	static void migrate() {
		FlywayTestDatabase.reset(DATABASE);
		Flyway flyway = FlywayTestDatabase.flyway(DATABASE);
		assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(2);
	}

	@Test
	void theForwardMigrationRunsOnTopOfTheFrozenBaseline() throws SQLException {
		List<String> history = queryColumn("select version from flyway_schema_history order by installed_rank");

		assertThat(history).contains("1", "3");
		assertThat(queryColumn("select success from flyway_schema_history where version = '3'")).containsExactly("1");
	}

	@Test
	void aWorkSessionCannotReferenceAProcedureThatDoesNotExist() {
		assertThatThrownBy(() -> insertWorkSession(999_999L, null))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("FOREIGN KEY");
	}

	@Test
	void aWorkSessionWithoutAProcedureStaysValid() throws SQLException {
		assertThatCode(() -> insertWorkSession(null, null)).doesNotThrowAnyException();
	}

	@Test
	void onlyOneOpenWorkSessionIsAllowedPerProcedure() throws SQLException {
		long procedureId = insertProcedure("ses_open", "active", null);
		insertWorkSession(procedureId, null);

		assertThatThrownBy(() -> insertWorkSession(procedureId, null))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("UNIQUE");
	}

	@Test
	void aClosedWorkSessionDoesNotBlockTheNextOne() throws SQLException {
		long procedureId = insertProcedure("ses_closed", "active", null);
		insertWorkSession(procedureId, "2026-11-10 10:00:00");

		assertThatCode(() -> insertWorkSession(procedureId, null)).doesNotThrowAnyException();
	}

	@Test
	void theProcedureEndInstantIsWrittenOnce() throws SQLException {
		long procedureId = insertProcedure("ses_once", "active", null);
		execute("update procedure set ended_at = ? where id = ?", START, procedureId);
		execute("update procedure set title = ? where id = ?", "Renamed", procedureId);

		assertThatThrownBy(() -> execute("update procedure set ended_at = ? where id = ?",
				"2026-11-10 11:00:00", procedureId))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("written once");
	}

	@Test
	void aFinalProcedureStatusNeverMovesAgain() throws SQLException {
		long procedureId = insertProcedure("ses_final", "completed", START);

		assertThatThrownBy(() -> execute("update procedure set status = ? where id = ?", "aborted", procedureId))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("final");
		assertThatThrownBy(() -> execute("update procedure set status = ? where id = ?", "active", procedureId))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("final");
	}

	@Test
	void theIndexesAndTriggersOfTheMigrationExist() throws SQLException {
		List<String> names = queryColumn(
				"select name from sqlite_master where type in ('index', 'trigger') order by name");

		assertThat(names).contains("idx_agent_anchor_updated_at", "idx_procedure_status",
				"idx_work_session_procedure_started", "ux_work_session_open_procedure",
				"trg_procedure_ended_at_write_once", "trg_procedure_status_final");
	}

	private static long insertProcedure(String sessionId, String status, String endedAt) throws SQLException {
		String sql = """
				insert into procedure (session_id, title, directory, status, started_at, ended_at, created_at, updated_at)
				values (?, 'Implement the clock', '/home/user/project', ?, ?, ?, ?, ?)
				""";
		try (Connection connection = connection();
				PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, sessionId);
			statement.setString(2, status);
			statement.setString(3, START);
			statement.setString(4, endedAt);
			statement.setString(5, START);
			statement.setString(6, START);
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				assertThat(keys.next()).isTrue();
				return keys.getLong(1);
			}
		}
	}

	private static void insertWorkSession(Long procedureId, String endedAt) throws SQLException {
		String sql = """
				insert into work_session (procedure_id, mode, started_at, ended_at, total_ms, paused_ms, active_ms, timezone)
				values (?, 'stopwatch', ?, ?, 0, 0, 0, 'America/Sao_Paulo')
				""";
		execute(sql, procedureId, START, endedAt);
	}

	private static void execute(String sql, Object... parameters) throws SQLException {
		try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < parameters.length; index++) {
				statement.setObject(index + 1, parameters[index]);
			}
			statement.executeUpdate();
		}
	}

	private static List<String> queryColumn(String sql) throws SQLException {
		try (Connection connection = connection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(sql)) {
			List<String> values = new ArrayList<>();
			while (resultSet.next()) {
				values.add(resultSet.getString(1));
			}
			return values;
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(FlywayTestDatabase.url(DATABASE));
	}
}
