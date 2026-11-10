package br.com.flexclock.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Proves the SQLite contract: the data directory is created before the driver opens the file, the
 * pragmas are really applied, and the single writer pool is enforced.
 */
class SqliteConfigTest {

	@TempDir
	private Path temporaryDirectory;

	@Test
	void theDataDirectoryIsCreatedBeforeTheDriverOpensTheFile() {
		Path databaseFile = temporaryDirectory.resolve("nested").resolve("deeper").resolve("flexclock.db");

		SqliteConfig.prepareDataDirectory("jdbc:sqlite:" + databaseFile + "?journal_mode=WAL&foreign_keys=ON");

		assertThat(databaseFile.getParent()).isDirectory();
	}

	@Test
	void thePragmaVerifierAcceptsAWellConfiguredDatabase() throws Exception {
		Path databaseFile = temporaryDirectory.resolve("verified.db");
		try (HikariDataSource dataSource = dataSource(databaseFile, 1)) {
			SqliteConfig.SqlitePragmaVerifier verifier = new SqliteConfig.SqlitePragmaVerifier(dataSource,
					databaseFile);

			verifier.afterPropertiesSet();

			try (Connection connection = dataSource.getConnection()) {
				assertThat(SqliteConfig.readPragma(connection, "journal_mode")).isEqualTo("wal");
				assertThat(SqliteConfig.readPragma(connection, "foreign_keys")).isEqualTo("1");
			}
			assertThat(databaseFile).exists();
		}
	}

	@Test
	void thePragmaVerifierRejectsAPoolWithMoreThanOneConnection() {
		Path databaseFile = temporaryDirectory.resolve("multi-writer.db");
		try (HikariDataSource dataSource = dataSource(databaseFile, 4)) {
			SqliteConfig.SqlitePragmaVerifier verifier = new SqliteConfig.SqlitePragmaVerifier(dataSource,
					databaseFile);

			assertThatThrownBy(verifier::afterPropertiesSet)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("single writer");
		}
	}

	@Test
	void thePragmaVerifierRejectsADatabaseWithoutWriteAheadLogging() {
		Path databaseFile = temporaryDirectory.resolve("rollback-journal.db");
		try (HikariDataSource dataSource = dataSource(databaseFile, 1, "jdbc:sqlite:" + databaseFile)) {
			SqliteConfig.SqlitePragmaVerifier verifier = new SqliteConfig.SqlitePragmaVerifier(dataSource,
					databaseFile);

			assertThatThrownBy(verifier::afterPropertiesSet)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("journal_mode");
		}
	}

	@Test
	void theDatabaseFileIsRestrictedToTheOwner() throws Exception {
		Path databaseFile = temporaryDirectory.resolve("owner-only.db");
		try (HikariDataSource dataSource = dataSource(databaseFile, 1)) {
			new SqliteConfig.SqlitePragmaVerifier(dataSource, databaseFile).afterPropertiesSet();
		}
		if (Files.getFileStore(databaseFile).supportsFileAttributeView("posix")) {
			assertThat(Files.getPosixFilePermissions(databaseFile))
					.isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		}
	}

	private HikariDataSource dataSource(Path databaseFile, int maximumPoolSize) {
		return dataSource(databaseFile, maximumPoolSize,
				"jdbc:sqlite:" + databaseFile + "?journal_mode=WAL&foreign_keys=ON&busy_timeout=5000");
	}

	private HikariDataSource dataSource(Path databaseFile, int maximumPoolSize, String jdbcUrl) {
		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(jdbcUrl);
		dataSource.setMaximumPoolSize(maximumPoolSize);
		dataSource.setMinimumIdle(1);
		return dataSource;
	}
}
