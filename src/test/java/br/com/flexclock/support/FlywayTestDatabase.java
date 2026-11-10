package br.com.flexclock.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Prepares the SQLite files used by the migration tests.
 *
 * <p>The tests need two kinds of database: one that already holds the Phase 0 schema, exactly as
 * Hibernate created it, and one that is empty so Flyway has to build it. Both run against real files
 * with the same connection pragmas as the application, because write ahead logging, foreign keys and
 * the single writer behaviour are part of what must be proven.
 */
public final class FlywayTestDatabase {

	private static final String SQLITE_URL_PREFIX = "jdbc:sqlite:";

	private static final String CONNECTION_PRAGMAS = "?journal_mode=WAL&foreign_keys=ON&busy_timeout=5000";

	private FlywayTestDatabase() {
		// Utility.
	}

	/**
	 * Removes the database file and its write ahead log companions, so a stale file can never hide a
	 * problem, and creates the parent directory.
	 *
	 * @param databaseFile the database file to clear
	 * @return the same file, ready to be created by the driver
	 */
	public static Path reset(Path databaseFile) {
		Path directory = databaseFile.toAbsolutePath().getParent();
		try {
			if (directory != null) {
				Files.createDirectories(directory);
			}
			for (String artifact : List.of("", "-wal", "-shm")) {
				Files.deleteIfExists(Path.of(databaseFile + artifact));
			}
		}
		catch (IOException exception) {
			throw new UncheckedIOException("Could not reset the test database " + databaseFile, exception);
		}
		return databaseFile;
	}

	/**
	 * Builds the JDBC url of a test database with the pragmas the application requires.
	 *
	 * @param databaseFile the database file
	 * @return the url used by the datasource and by Flyway
	 */
	public static String url(Path databaseFile) {
		return SQLITE_URL_PREFIX + databaseFile.toAbsolutePath() + CONNECTION_PRAGMAS;
	}

	/**
	 * Executes a semicolon separated SQL script from the test resources.
	 *
	 * @param databaseFile the database file to create and populate
	 * @param classpathResource the resource path, relative to the classpath root
	 */
	public static void executeScript(Path databaseFile, String classpathResource) {
		String script = readResource(classpathResource);
		try (Connection connection = DriverManager.getConnection(url(databaseFile));
				Statement statement = connection.createStatement()) {
			for (String command : script.split(";")) {
				if (!command.isBlank()) {
					statement.execute(command);
				}
			}
		}
		catch (SQLException exception) {
			throw new IllegalStateException("Could not execute " + classpathResource + " on " + databaseFile,
					exception);
		}
	}

	/**
	 * Creates a Flyway instance configured like the application: baseline on an existing schema, the
	 * baseline version that marks the Phase 0 schema as already applied, no clean, and validation on.
	 *
	 * <p>It mirrors {@code src/main/resources/application.properties} for the cases where a test has to
	 * migrate a second database that no Spring context owns.
	 *
	 * @param databaseFile the database file to migrate
	 * @return the configured Flyway instance
	 */
	public static Flyway flyway(Path databaseFile) {
		return flyway(databaseFile, null);
	}

	/**
	 * Creates a Flyway instance configured like the application, optionally stopped at a target version.
	 *
	 * <p>The target version exists so a test can prove what one specific migration produced, without
	 * depending on how many forward migrations later workstreams added, and so the baseline migration
	 * can be compared with the Phase 0 schema without the forward migrations.
	 *
	 * @param databaseFile the database file to migrate
	 * @param targetVersion the last version to apply, null to apply every migration
	 * @return the configured Flyway instance
	 */
	public static Flyway flyway(Path databaseFile, String targetVersion) {
		FluentConfiguration configuration = Flyway.configure()
				.dataSource(url(databaseFile), null, null)
				.locations("classpath:db/migration")
				.baselineOnMigrate(true)
				.baselineVersion("1")
				.cleanDisabled(true)
				.validateOnMigrate(true);
		if (targetVersion != null) {
			configuration.target(MigrationVersion.fromVersion(targetVersion));
		}
		return configuration.load();
	}

	private static String readResource(String classpathResource) {
		try (InputStream stream = FlywayTestDatabase.class.getResourceAsStream("/" + classpathResource)) {
			if (stream == null) {
				throw new IllegalStateException("Missing test resource " + classpathResource);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new UncheckedIOException("Could not read " + classpathResource, exception);
		}
	}
}
