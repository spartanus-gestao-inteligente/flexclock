package br.com.flexclock.dto;

import java.time.Instant;

import br.com.flexclock.reports.ReportFamily;
import br.com.flexclock.enums.ReportRecordType;

/**
 * Result of appending one report record.
 *
 * <p>A repeated Idempotency-Key with the same payload returns this same response and inserts nothing,
 * so the caller can retry a write after a timeout without duplicating the record.
 *
 * @param family the family the record belongs to
 * @param recordId the identifier of the appended record
 * @param recordType the closed record type of the record
 * @param occurredAt the instant the event happened
 * @param redacted true when a secret was removed before the record was persisted
 */
public record ReportRecordResponse(ReportFamily family, Long recordId, ReportRecordType recordType,
		Instant occurredAt, boolean redacted) {
}
