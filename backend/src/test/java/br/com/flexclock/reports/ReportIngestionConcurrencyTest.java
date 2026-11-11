package br.com.flexclock.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import br.com.flexclock.common.IdempotencyRecord;
import br.com.flexclock.common.IdempotencyService;
import br.com.flexclock.common.IdempotencyStore;
import br.com.flexclock.common.IdempotentResponse;
import br.com.flexclock.dto.ReportRecordRequest;
import br.com.flexclock.dto.ReportRecordResponse;
import br.com.flexclock.enums.FailureType;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.Severity;
import br.com.flexclock.reports.ReportRecordRepository.ErrorDetailRow;
import br.com.flexclock.support.ReportRequestBuilder;
import br.com.flexclock.support.SqliteIntegrationTest;

/**
 * Proves that a duplicate write with the same Idempotency-Key waits for the commit of the original one
 * instead of meeting the unique index of the table.
 *
 * <p>The original write is held inside its own transaction, its row already inserted and not committed,
 * while the duplicate with the same key and the same payload is started. The duplicate must not run the
 * write at all: the per key lock of the idempotency engine is held until the transaction completes, so
 * the duplicate waits, then reads the record stored by {@code afterCommit} and answers the original
 * response. Both callers end with the same body, neither of them sees a database error, and the key
 * leaves exactly one row behind (requirement RF-174, requirement RNF-013).
 *
 * <p>The decisive moment is the commit window itself: the store of the replay record is held open after
 * the transaction committed and before the lock is released, and the duplicate is still waiting right
 * there. A lock that were released before the commit would let the duplicate through at that instant,
 * with no record to find.
 *
 * <p>The test keeps the single writer connection of the product, so the duplicate holds no second
 * database transaction: it enters the same engine, with the same key, the same operation and the same
 * payload, and its write would raise instead of inserting, which is what proves that the original
 * response was replayed and not written again.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportIngestionConcurrencyTest extends SqliteIntegrationTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-04-06T09:00:00Z");

	private static final String KEY = "concurrent-errors-1";

	private static final long TIMEOUT_SECONDS = 20;

	@Autowired
	private ReportIngestionService ingestionService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private ReportRecordRepository repository;

	@MockitoSpyBean
	private IdempotencyService idempotencyService;

	@MockitoSpyBean
	private IdempotencyStore idempotencyStore;

	@Test
	void aDuplicateThatArrivesDuringTheCommitWindowAnswersTheOriginalResponse() throws Exception {
		ReportRecordRequest request = errorRecord();
		CountDownLatch originalRowInserted = new CountDownLatch(1);
		CountDownLatch originalMayCommit = new CountDownLatch(1);
		CountDownLatch duplicateInTheEngine = new CountDownLatch(1);
		CountDownLatch originalRecordStoring = new CountDownLatch(1);
		CountDownLatch recordMayBeStored = new CountDownLatch(1);
		AtomicBoolean originalHeld = new AtomicBoolean();
		AtomicBoolean originalStoringHeld = new AtomicBoolean();
		AtomicInteger engineCalls = new AtomicInteger();

		doAnswer(invocation -> {
			if (engineCalls.incrementAndGet() == 2) {
				duplicateInTheEngine.countDown();
			}
			return invocation.callRealMethod();
		}).when(idempotencyService)
				.execute(anyString(), anyString(), any(), anyInt(), any(), any());

		doAnswer(invocation -> {
			if (originalHeld.compareAndSet(false, true)) {
				originalRowInserted.countDown();
				assertThat(originalMayCommit.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			}
			return invocation.callRealMethod();
		}).when(repository).insertErrorDetail(any(ErrorDetailRow.class));

		doAnswer(invocation -> {
			if (originalStoringHeld.compareAndSet(false, true)) {
				originalRecordStoring.countDown();
				assertThat(recordMayBeStored.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			}
			return invocation.callRealMethod();
		}).when(idempotencyStore).save(any(IdempotencyRecord.class));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<ReportRecordResponse> original = executor.submit(
					() -> ingestionService.append(ReportFamily.FAILURES, request, KEY));
			assertThat(originalRowInserted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.as("the original write must reach its row inside the open transaction").isTrue();

			Future<IdempotentResponse<ReportRecordResponse>> duplicate = executor.submit(
					() -> idempotencyService.execute(KEY, "reports." + ReportFamily.FAILURES.wireName(), request,
							201, ReportRecordResponse.class, () -> {
								throw new IllegalStateException("the duplicate must replay the original response");
							}));
			assertThat(duplicateInTheEngine.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.as("the duplicate must reach the idempotency engine while the transaction is open").isTrue();

			originalMayCommit.countDown();
			assertThat(originalRecordStoring.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.as("the original write must reach the store of the replay record").isTrue();
			assertThatThrownBy(() -> duplicate.get(500, TimeUnit.MILLISECONDS))
					.as("the duplicate must wait for the completion of the original transaction")
					.isInstanceOf(TimeoutException.class);
			recordMayBeStored.countDown();

			ReportRecordResponse originalResponse = original.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			IdempotentResponse<ReportRecordResponse> duplicateResponse = duplicate.get(TIMEOUT_SECONDS,
					TimeUnit.SECONDS);

			assertThat(duplicateResponse.status()).isEqualTo(201);
			assertThat(duplicateResponse.body()).isEqualTo(originalResponse);
			assertThat(originalResponse.recordId()).isPositive();
			assertThat(countRows("failure_event")).isEqualTo(1);
			assertThat(countErrorDetails()).isEqualTo(1);
		}
		finally {
			originalMayCommit.countDown();
			recordMayBeStored.countDown();
			executor.shutdownNow();
		}
	}

	private int countRows(String table) {
		Integer count = jdbcTemplate.queryForObject(
				"select count(*) from " + table + " where idempotency_key = ?", Integer.class, KEY);
		return count == null ? 0 : count;
	}

	private int countErrorDetails() {
		Integer count = jdbcTemplate.queryForObject("""
				select count(*) from error_detail d
				join failure_event f on f.id = d.failure_event_id
				where f.idempotency_key = ?
				""", Integer.class, KEY);
		return count == null ? 0 : count;
	}

	private ReportRecordRequest errorRecord() {
		return ReportRequestBuilder.of(ReportRecordType.ERRORS)
				.occurredAt(OCCURRED_AT)
				.agentId("agent-concurrent")
				.failureType(FailureType.DB_WRITE_FAILURE)
				.severity(Severity.HIGH)
				.code("DB_WRITE_FAILURE")
				.message("The database write failed.")
				.build();
	}
}
