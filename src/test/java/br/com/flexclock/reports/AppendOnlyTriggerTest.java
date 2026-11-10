package br.com.flexclock.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import br.com.flexclock.support.SqliteIntegrationTest;

/**
 * Proves the second half of the append only defence: the SQLite triggers of migration V5 abort any
 * UPDATE and any DELETE on every report table and on the error detail table, with no exception.
 *
 * <p>Decision 0049; requirements RF-048, RF-050.
 */
@SpringBootTest
@ActiveProfiles("test")
class AppendOnlyTriggerTest extends SqliteIntegrationTest {

	private static final List<String> TABLES = ReportIngestionService.appendOnlyTables();

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void theMigrationInstalledBothTriggersOnEveryReportTable() {
		for (String table : TABLES) {
			for (String suffix : List.of("no_update", "no_delete")) {
				Integer count = jdbcTemplate.queryForObject(
						"select count(*) from sqlite_master where type = 'trigger' and name = ?", Integer.class,
						"trg_" + table + "_" + suffix);
				assertThat(count).as("trigger of %s (%s)", table, suffix).isEqualTo(1);
			}
		}
		// Only the append only pairs are counted: the procedure table carries its own guards from the
		// sessions migration (V3), and they must not be confused with the report triggers.
		assertThat(jdbcTemplate.queryForObject("""
				select count(*) from sqlite_master where type = 'trigger'
				and (name like 'trg_%_no_update' or name like 'trg_%_no_delete')
				""", Integer.class)).isEqualTo(TABLES.size() * 2);
	}

	@Test
	void anUpdateOnAFailureRecordIsAborted() {
		jdbcTemplate.update("""
				insert into failure_event (record_type, failure_type, severity, occurred_at, agent_id, write_source,
					idempotency_key)
				values ('failure', 'tool_error', 'low', '2026-02-01 10:00:00', 'agent-append', 'rest', 'append-update-1')
				""");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"update failure_event set severity = 'critical' where idempotency_key = 'append-update-1'"))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("append only");
	}

	@Test
	void aDeleteOnAnErrorDetailIsAborted() {
		jdbcTemplate.update("""
				insert into failure_event (record_type, failure_type, severity, occurred_at, agent_id, write_source,
					idempotency_key)
				values ('errors', 'db_write_failure', 'high', '2026-02-01 11:00:00', 'agent-append', 'rest',
					'append-detail-parent')
				""");
		Long parentId = jdbcTemplate.queryForObject(
				"select id from failure_event where idempotency_key = 'append-detail-parent'", Long.class);
		jdbcTemplate.update("""
				insert into error_detail (failure_event_id, code, message, failure_type, severity, agent_id, occurred_at)
				values (?, 'DB_WRITE_FAILURE', 'The database write failed.', 'db_write_failure', 'high', 'agent-append',
					'2026-02-01 11:00:00')
				""", parentId);

		assertThatThrownBy(() -> jdbcTemplate.update("delete from error_detail where failure_event_id = ?", parentId))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("append only");
	}

	@Test
	void everyReportTableRefusesAnUpdateAndADelete() {
		List<String> seeds = List.of(
				"""
				insert into hallucination_event (occurred_at, agent_id, response_ref, record_type, write_source,
					idempotency_key) values ('2026-02-02 10:00:00', 'agent-append', 'ref', 'hallucination', 'rest',
					'append-hallucination')
				""",
				"""
				insert into quality_evaluation (occurred_at, agent_id, score, adherence_level, evidence_level,
					process_level, artifact_level, honesty_level, rationale, authority, write_source, idempotency_key)
				values ('2026-02-02 10:00:00', 'agent-append', 8, 4, 3, 4, 3, 4, 'reason', 'SELF', 'rest',
					'append-quality')
				""",
				"""
				insert into graph_usage_event (occurred_at, agent_id, query_count, distinct_nodes,
					consulted_before_work, record_type, write_source, idempotency_key)
				values ('2026-02-02 10:00:00', 'agent-append', 2, 5, true, 'graph_usage', 'rest', 'append-graph')
				""",
				"""
				insert into token_economy_event (occurred_at, agent_id, input_tokens, output_tokens, measurable,
					not_measurable_reason, model_id, record_type, write_source, idempotency_key)
				values ('2026-02-02 10:00:00', 'agent-append', 10, 20, false, 'no baseline', 'model', 'token_economy',
					'rest', 'append-token')
				""",
				"""
				insert into source_event (occurred_at, agent_id, source, origin, task_context, purpose, result,
					redacted, record_type, write_source, idempotency_key)
				values ('2026-02-02 10:00:00', 'agent-append', 'https://x.example', 'domain', 'context', 'purpose',
					'success', false, 'source', 'rest', 'append-source')
				""",
				"""
				insert into download_event (occurred_at, agent_id, source, item, item_type, size_bytes, result,
					destination, redacted, record_type, write_source, idempotency_key)
				values ('2026-02-02 10:00:00', 'agent-append', 'https://x.example', 'file.zip', 'zip', 10, 'success',
					'/tmp', false, 'download', 'rest', 'append-download')
				""",
				"""
				insert into upload_event (occurred_at, agent_id, destination, item, item_type, size_bytes, result,
					sent, redacted, record_type, write_source, idempotency_key)
				values ('2026-02-02 10:00:00', 'agent-append', 'https://y.example', 'file.zip', 'zip', 10, 'success',
					true, false, 'upload', 'rest', 'append-upload')
				""");

		seeds.forEach(jdbcTemplate::update);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"update hallucination_event set response_ref = 'other' where idempotency_key = 'append-hallucination'"))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append only");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"update quality_evaluation set score = 0 where idempotency_key = 'append-quality'"))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append only");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"update graph_usage_event set query_count = 99 where idempotency_key = 'append-graph'"))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append only");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"update token_economy_event set input_tokens = 99 where idempotency_key = 'append-token'"))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append only");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"delete from source_event where idempotency_key = 'append-source'"))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append only");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"delete from download_event where idempotency_key = 'append-download'"))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append only");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"delete from upload_event where idempotency_key = 'append-upload'"))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append only");
	}

	@Test
	void theSelfCheckPassesOnTheMigratedDatabase() {
		new AppendOnlyGuard(jdbcTemplate).verify();
	}

	@Test
	void theSelfCheckFailsWhenATriggerIsMissing() {
		JdbcTemplate isolated = jdbcTemplate;
		isolated.execute("drop trigger trg_upload_event_no_delete");
		try {
			assertThatThrownBy(() -> new AppendOnlyGuard(isolated).verify())
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("trg_upload_event_no_delete");
		}
		finally {
			isolated.execute("""
					create trigger trg_upload_event_no_delete before delete on upload_event
					begin
						select raise(abort, 'upload_event is append only: delete is not allowed');
					end
					""");
		}
		new AppendOnlyGuard(isolated).verify();
	}
}
