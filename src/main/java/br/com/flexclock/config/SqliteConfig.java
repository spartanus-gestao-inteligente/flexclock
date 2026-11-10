package br.com.flexclock.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.zaxxer.hikari.HikariDataSource;

/**
 * SQLite configuration for a single writer process.
 *
 * <p>The datasource properties already ask SQLite for write ahead logging, foreign keys on every
 * connection and a busy timeout. This class adds the two things a property cannot do:
 *
 * <ol>
 * <li>it creates the data directory before the driver opens the database file, because SQLite does not
 * create a missing parent directory, and it restricts the directory to the owner;</li>
 * <li>it verifies on startup that the connection really carries the three pragmas and that the pool
 * holds a single connection, and fails the process when one of them is missing. A silent fallback to
 * the default journal mode would break the append only guarantees later.</li>
 * </ol>
 *
 * <p>Architecture contract, sections 2 and 4.5; decisions 0017, 0049; requirements RF-005, RNF-010,
 * RNF-016 and RNF-029.
 */
@Configuration(proxyBeanMethods = false)
public class SqliteConfig {

	private static final Logger log = LoggerFactory.getLogger(SqliteConfig.class);

	private static final String SQLITE_URL_PREFIX = "jdbc:sqlite:";
	private static final String PRAGMA_JOURNAL_MODE = "journal_mode";
	private static final String PRAGMA_FOREIGN_KEYS = "foreign_keys";
	private static final String PRAGMA_BUSY_TIMEOUT = "busy_timeout";
	private static final String EXPECTED_JOURNAL_MODE = "wal";
	private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");

	/**
	 * Creates the directory that holds the database file before any bean opens a connection.
	 *
	 * <p>It is a bean factory post processor on purpose: it runs before singleton instantiation, which
	 * is the only point where the directory is guaranteed to exist before the datasource is used.
	 *
	 * @param environment the environment that holds the datasource url
	 * @return the post processor that prepares the data directory
	 */
	@Bean
	static BeanFactoryPostProcessor sqliteDataDirectoryInitializer(Environment environment) {
		return beanFactory -> prepareDataDirectory(environment.getProperty("spring.datasource.url"));
	}

	static void prepareDataDirectory(String datasourceUrl) {
		if (datasourceUrl == null || !datasourceUrl.startsWith(SQLITE_URL_PREFIX)) {
			return;
		}
		Path databaseFile = databaseFile(datasourceUrl);
		Path directory = databaseFile.getParent();
		if (directory == null) {
			return;
		}
		try {
			Files.createDirectories(directory);
			applyPermissions(directory, OWNER_ONLY_DIRECTORY);
			log.debug("SQLite data directory ready at {}", directory);
		}
		catch (IOException exception) {
			throw new IllegalStateException("The SQLite data directory could not be created at " + directory,
					exception);
		}
	}

	private static Path databaseFile(String datasourceUrl) {
		String location = datasourceUrl.substring(SQLITE_URL_PREFIX.length());
		int queryIndex = location.indexOf('?');
		if (queryIndex >= 0) {
			location = location.substring(0, queryIndex);
		}
		return Path.of(location).toAbsolutePath().normalize();
	}

	static String readPragma(Connection connection, String pragma) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("PRAGMA " + pragma)) {
			return resultSet.next() ? resultSet.getString(1) : null;
		}
	}

	private static void applyPermissions(Path path, Set<PosixFilePermission> permissions) {
		try {
			Files.setPosixFilePermissions(path, permissions);
		}
		catch (UnsupportedOperationException exception) {
			log.debug("File permissions are not POSIX on this platform, skipping {}", path);
		}
		catch (IOException exception) {
			log.warn("Could not restrict the permissions of {}", path, exception);
		}
	}

	/**
	 * Verifies the SQLite contract on the first connection and fails the process when it is broken.
	 *
	 * @param dataSource the configured datasource
	 * @return the verifier that runs at startup
	 */
	@Bean
	SqlitePragmaVerifier sqlitePragmaVerifier(DataSource dataSource, Environment environment) {
		return new SqlitePragmaVerifier(dataSource, databaseFile(environment.getProperty("spring.datasource.url",
				SQLITE_URL_PREFIX + "data/flexclock.db")));
	}

	/**
	 * Startup self check of the SQLite pragmas and of the single writer pool.
	 */
	static final class SqlitePragmaVerifier implements InitializingBean {

		private final DataSource dataSource;
		private final Path databaseFile;

		SqlitePragmaVerifier(DataSource dataSource, Path databaseFile) {
			this.dataSource = dataSource;
			this.databaseFile = databaseFile;
		}

		@Override
		public void afterPropertiesSet() throws SQLException {
			try (Connection connection = dataSource.getConnection()) {
				String journalMode = SqliteConfig.readPragma(connection, PRAGMA_JOURNAL_MODE);
				if (!EXPECTED_JOURNAL_MODE.equalsIgnoreCase(journalMode)) {
					throw new IllegalStateException(
							"SQLite journal_mode is " + journalMode + " and must be " + EXPECTED_JOURNAL_MODE + ".");
				}
				String foreignKeys = SqliteConfig.readPragma(connection, PRAGMA_FOREIGN_KEYS);
				if (!"1".equals(foreignKeys)) {
					throw new IllegalStateException("SQLite foreign_keys is " + foreignKeys + " and must be 1.");
				}
				String busyTimeout = SqliteConfig.readPragma(connection, PRAGMA_BUSY_TIMEOUT);
				if (busyTimeout == null || Integer.parseInt(busyTimeout) <= 0) {
					throw new IllegalStateException("SQLite busy_timeout must be a positive number of milliseconds.");
				}
			}
			assertSingleWriterPool();
			applyPermissions(databaseFile, OWNER_ONLY_FILE);
			log.info("SQLite verified: journal_mode=wal, foreign_keys=on, busy_timeout configured, single writer pool");
		}

		private void assertSingleWriterPool() {
			if (dataSource instanceof HikariDataSource hikari && hikari.getMaximumPoolSize() != 1) {
				throw new IllegalStateException(
						"SQLite requires a single writer: Hikari maximumPoolSize is " + hikari.getMaximumPoolSize());
			}
		}
	}
}
