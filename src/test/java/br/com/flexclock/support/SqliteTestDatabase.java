package br.com.flexclock.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prepares the SQLite file used by the integration tests.
 *
 * <p>The persistence tests run against a real file, because write ahead logging, foreign keys and the
 * community dialect are part of what must be proven. The file is removed once per test run, before the
 * first context starts, so a stale schema from a previous run can never hide a problem.
 */
public final class SqliteTestDatabase {

	private static final Path DIRECTORY = Path.of("target", "flexclock-test");

	private static final List<String> ARTIFACTS = List.of("flexclock-test.db", "flexclock-test.db-wal",
			"flexclock-test.db-shm", "flexclock.instance.json");

	private SqliteTestDatabase() {
		// Utility.
	}

	/**
	 * Removes the database, its write ahead log files and the instance descriptor.
	 */
	public static void reset() {
		ARTIFACTS.forEach(artifact -> delete(DIRECTORY.resolve(artifact)));
	}

	private static void delete(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException exception) {
			throw new UncheckedIOException("Could not remove the test artifact " + path, exception);
		}
	}
}
