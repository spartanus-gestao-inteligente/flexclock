package br.com.flexclock.reports;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup self check of the append only guarantee.
 *
 * <p>The SQLite triggers are the second half of the append only defence: they abort any UPDATE or
 * DELETE on a report table and on the error detail table, with no exception. A product that silently
 * lost that guarantee must not run, so the check confirms that both triggers exist on every one of the
 * nine tables and fails the startup otherwise.
 *
 * <p>The check runs as an {@link ApplicationRunner}, which is after the context refresh and therefore
 * after Flyway migrated the database: a check that ran earlier could not tell a missing trigger from a
 * migration that has not run yet. Throwing here aborts the startup and the process exits with a
 * failure.
 *
 * <p>Decision 0049; requirements RF-048, RF-050.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AppendOnlyGuard implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AppendOnlyGuard.class);

	private final JdbcTemplate jdbcTemplate;

	public AppendOnlyGuard(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		verify();
	}

	/**
	 * Verifies that the append only triggers exist on every report table.
	 *
	 * @throws IllegalStateException when at least one trigger is missing
	 */
	public void verify() {
		List<String> missing = new ArrayList<>();
		for (String table : ReportIngestionService.appendOnlyTables()) {
			for (String suffix : List.of("no_update", "no_delete")) {
				String trigger = "trg_" + table + "_" + suffix;
				Integer count = jdbcTemplate.queryForObject(
						"select count(*) from sqlite_master where type = 'trigger' and name = ?",
						Integer.class, trigger);
				if (count == null || count == 0) {
					missing.add(trigger);
				}
			}
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException("The append only guarantee is missing, triggers not found: "
					+ String.join(", ", missing));
		}
		log.info("Append only self check passed for {} report tables",
				ReportIngestionService.appendOnlyTables().size());
	}
}
