package br.com.flexclock.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import br.com.flexclock.platform.SettingKey;
import br.com.flexclock.support.FlywayTestDatabase;

/**
 * Proves migration V2 on its own database: the seeded rows of the closed settings catalogue, their
 * types, their owners and the representation of the audit instant.
 *
 * <p>It runs against a dedicated file so the assertion never depends on a value another test changed,
 * and it reads the raw row instead of the entity, because what has to be proven here is the migration
 * and not the mapping (decision 0058; requirement RF-008).
 *
 * <p>The instant is the delicate part: the migration writes it as the UTC instant in epoch
 * milliseconds, which is the representation the SQLite driver and the Hibernate timestamp mapping
 * agree on, so the column must hold an integer and not a text date.
 */
class SettingsCatalogueSeedTest {

	private static final Path DATABASE = FlywayTestDatabase.reset(
			Path.of("target", "flexclock-flyway").resolve("catalogue.db"));

	@BeforeAll
	static void migrate() {
		// The database is migrated up to the catalogue migration, so the assertion proves what V2 produced
		// without depending on how many forward migrations the later workstreams added (decision 0058).
		Flyway flyway = FlywayTestDatabase.flyway(DATABASE, "2");
		assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
	}

	@Test
	void everyKeyOfTheCatalogueIsSeededOnceWithItsDeclaredTypeOwnerAndRange() throws SQLException {
		for (SettingKey key : SettingKey.values()) {
			Map<String, Object> row = readRow(key.wireKey());

			assertThat(row).as("seed of %s", key.wireKey()).isNotNull();
			assertThat(row.get("value_type")).isEqualTo(key.valueType().wireValue());
			assertThat(row.get("value")).isEqualTo(key.defaultValue());
			assertThat(row.get("min_value")).isEqualTo(key.minValue());
			assertThat(row.get("max_value")).isEqualTo(key.maxValue());
			assertThat(row.get("owner")).isEqualTo(key.owner().wireValue());
			assertThat(row.get("updated_by")).isEqualTo("installer");
		}
		assertThat(countRows()).isEqualTo(SettingKey.values().length);
	}

	@Test
	void theAuditInstantIsStoredAsEpochMillisecondsThatTheMappingCanRead() throws SQLException {
		try (Connection connection = DriverManager.getConnection(FlywayTestDatabase.url(DATABASE));
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						select typeof(updated_at) as kind, updated_at as value from settings where key = 'language'
						""")) {
			assertThat(resultSet.next()).isTrue();
			assertThat(resultSet.getString("kind")).isEqualTo("integer");
			long epochMillis = resultSet.getLong("value");
			assertThat(Instant.ofEpochMilli(epochMillis)).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
		}
	}

	@Test
	void theSeededRowsSatisfyEveryCheckConstraintOfTheBaseline() throws SQLException {
		try (Connection connection = DriverManager.getConnection(FlywayTestDatabase.url(DATABASE));
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"select count(*) as total from settings where value_type not in ('string','integer','enum','url')"
								+ " or owner not in ('user','agent')")) {
			assertThat(resultSet.next()).isTrue();
			assertThat(resultSet.getInt("total")).isZero();
		}
	}

	private Map<String, Object> readRow(String key) throws SQLException {
		try (Connection connection = DriverManager.getConnection(FlywayTestDatabase.url(DATABASE));
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						select value_type, value, min_value, max_value, owner, updated_by
						from settings where key = '%s'
						""".formatted(key))) {
			if (!resultSet.next()) {
				return null;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("value_type", resultSet.getString("value_type"));
			row.put("value", resultSet.getString("value"));
			row.put("min_value", resultSet.getString("min_value"));
			row.put("max_value", resultSet.getString("max_value"));
			row.put("owner", resultSet.getString("owner"));
			row.put("updated_by", resultSet.getString("updated_by"));
			return row;
		}
	}

	private int countRows() throws SQLException {
		try (Connection connection = DriverManager.getConnection(FlywayTestDatabase.url(DATABASE));
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("select count(*) as total from settings")) {
			assertThat(resultSet.next()).isTrue();
			return resultSet.getInt("total");
		}
	}
}
