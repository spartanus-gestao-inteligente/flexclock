package br.com.flexclock.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import br.com.flexclock.common.IdempotencyStore;
import br.com.flexclock.dto.ReportRecordRequest;
import br.com.flexclock.dto.ReportRecordResponse;
import br.com.flexclock.enums.FailureType;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.Severity;
import br.com.flexclock.reports.ReportRecordRepository.ErrorDetailRow;
import br.com.flexclock.support.ReportRequestBuilder;
import br.com.flexclock.support.SqliteIntegrationTest;

/**
 * Proves that a report write that fails leaves no replayable record behind.
 *
 * <p>The ingestion writes the failure row and then the error detail of an {@code errors} record. When
 * the second insert fails, the transaction rolls the first one back and the idempotency engine must not
 * keep the result of a write that was never stored: a repeated key then re-executes the write instead
 * of replaying a record that does not exist (requirement RF-155, requirement RF-174). The durable guard
 * of the unique index on the Idempotency-Key covers the opposite case: a key committed by an earlier
 * process, whose in memory record is gone, answers with the original row.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportIngestionIdempotencyTest extends SqliteIntegrationTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-04-05T10:00:00Z");

	@Autowired
	private ReportIngestionService ingestionService;

	@Autowired
	private IdempotencyStore idempotencyStore;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private ReportRecordRepository repository;

	@Test
	void aFailedWriteLeavesNoReplayableRecordAndTheKeyCanRunAgain() {
		String key = "rollback-errors-1";
		ReportRecordRequest request = errorRecord();
		doThrow(new DataIntegrityViolationException("forced failure of the error detail"))
				.when(repository).insertErrorDetail(any(ErrorDetailRow.class));

		assertThatThrownBy(() -> ingestionService.append(ReportFamily.FAILURES, request, key))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(idempotencyStore.find(key)).isEmpty();
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from failure_event where idempotency_key = ?", Integer.class, key)).isZero();

		doCallRealMethod().when(repository).insertErrorDetail(any(ErrorDetailRow.class));
		ReportRecordResponse replayed = ingestionService.append(ReportFamily.FAILURES, request, key);

		assertThat(replayed.recordId()).isPositive();
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from failure_event where idempotency_key = ?", Integer.class, key)).isEqualTo(1);
	}

	@Test
	void aKeyRecordedByAnEarlierProcessAnswersWithTheOriginalRow() {
		String key = "restart-errors-1";
		jdbcTemplate.update("""
				insert into failure_event (record_type, failure_type, severity, occurred_at, agent_id, write_source,
					idempotency_key)
				values ('errors', 'db_write_failure', 'high', '2026-04-05 10:00:00', 'agent-earlier', 'rest', ?)
				""", key);
		Long recordedId = jdbcTemplate.queryForObject(
				"select id from failure_event where idempotency_key = ?", Long.class, key);

		ReportRecordResponse response = ingestionService.append(ReportFamily.FAILURES, errorRecord(), key);

		assertThat(response.recordId()).isEqualTo(recordedId);
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from failure_event where idempotency_key = ?", Integer.class, key)).isEqualTo(1);
	}

	private ReportRecordRequest errorRecord() {
		return ReportRequestBuilder.of(ReportRecordType.ERRORS)
				.occurredAt(OCCURRED_AT)
				.agentId("agent-rollback")
				.failureType(FailureType.DB_WRITE_FAILURE)
				.severity(Severity.HIGH)
				.code("DB_WRITE_FAILURE")
				.message("The database write failed.")
				.build();
	}
}
