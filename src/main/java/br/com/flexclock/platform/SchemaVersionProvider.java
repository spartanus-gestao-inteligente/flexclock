package br.com.flexclock.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Database schema version reported by the product metadata.
 *
 * <p>Since Flyway owns the schema from V1 on, the truthful value is the highest migration that was
 * applied, not a number written in a properties file (decision 0058; requirement RF-007). The value is
 * resolved once, on the first call, because migrations run before the web server accepts a request and
 * cannot change during the run.
 *
 * <p>When the query cannot be answered, the configured value is reported instead of failing the
 * metadata call, so a client always receives a document and the readiness endpoint stays the single
 * place that reports the database as unavailable (requirement RF-003).
 */
@Component
public class SchemaVersionProvider {

	private static final Logger log = LoggerFactory.getLogger(SchemaVersionProvider.class);

	private static final String HIGHEST_APPLIED_MIGRATION = """
			select max(cast(version as integer)) from flyway_schema_history where success = 1
			""";

	private final JdbcTemplate jdbcTemplate;
	private final String configuredVersion;

	private volatile String resolvedVersion;

	public SchemaVersionProvider(JdbcTemplate jdbcTemplate,
			@Value("${flexclock.schema.version:0}") String configuredVersion) {
		this.jdbcTemplate = jdbcTemplate;
		this.configuredVersion = configuredVersion;
	}

	/**
	 * Returns the schema version of the live database.
	 *
	 * @return the highest applied migration, or the configured version when it cannot be read
	 */
	public String current() {
		String resolved = resolvedVersion;
		if (resolved == null) {
			resolved = resolve();
			resolvedVersion = resolved;
		}
		return resolved;
	}

	private String resolve() {
		try {
			Integer highest = jdbcTemplate.queryForObject(HIGHEST_APPLIED_MIGRATION, Integer.class);
			return highest == null ? configuredVersion : Integer.toString(highest);
		}
		catch (DataAccessException exception) {
			log.warn("The schema version could not be read from the migration history", exception);
			return configuredVersion;
		}
	}
}
