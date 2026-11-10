package br.com.flexclock.reports;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.common.IdempotencyRecord;
import br.com.flexclock.common.IdempotencyStore;
import br.com.flexclock.dto.ReportRecordRequest;
import br.com.flexclock.dto.ReportRecordResponse;
import br.com.flexclock.enums.EvaluationAuthority;
import br.com.flexclock.enums.EventResult;
import br.com.flexclock.enums.FailureType;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.Severity;
import br.com.flexclock.enums.SourceOrigin;
import br.com.flexclock.enums.WriteSource;
import br.com.flexclock.reports.RedactionComponent.RedactedFields;
import br.com.flexclock.reports.ReportRecordRepository.DownloadRow;
import br.com.flexclock.reports.ReportRecordRepository.ErrorDetailRow;
import br.com.flexclock.reports.ReportRecordRepository.FailureEventRow;
import br.com.flexclock.reports.ReportRecordRepository.GraphUsageRow;
import br.com.flexclock.reports.ReportRecordRepository.HallucinationRow;
import br.com.flexclock.reports.ReportRecordRepository.QualityRow;
import br.com.flexclock.reports.ReportRecordRepository.SourceRow;
import br.com.flexclock.reports.ReportRecordRepository.TokenEconomyRow;
import br.com.flexclock.reports.ReportRecordRepository.UploadRow;

/**
 * Appends one record to a report family.
 *
 * <p>The ingestion path offers creation only: there is no update, no delete and no batch operation that
 * rewrites an existing record. Validation refuses a value outside the closed domain before anything is
 * written, secrets are removed from every free text and URL field before the record is persisted, and a
 * repeated Idempotency-Key returns the original result instead of inserting a second record.
 *
 * <p>An error record is a failure record whose type is {@code errors}; its details are written as an
 * immutable row of the error detail table, linked by foreign key, so the failures report can read them.
 *
 * <p>Decisions 0014, 0021, 0033, 0049, 0054, 0056; requirements RF-045 to RF-053, RF-066.
 */
@Service
public class ReportIngestionService {

	private static final Logger log = LoggerFactory.getLogger(ReportIngestionService.class);

	private static final WriteSource REST_WRITE_SOURCE = WriteSource.REST;

	private final ReportRecordRepository repository;
	private final RedactionComponent redactionComponent;
	private final IdempotencyStore idempotencyStore;

	public ReportIngestionService(ReportRecordRepository repository, RedactionComponent redactionComponent,
			IdempotencyStore idempotencyStore) {
		this.repository = repository;
		this.redactionComponent = redactionComponent;
		this.idempotencyStore = idempotencyStore;
	}

	/**
	 * Appends one record of a family.
	 *
	 * @param family the family of the path
	 * @param request the record to append
	 * @param idempotencyKey the key presented by the client
	 * @return the result of the append, the original result for a repeated key
	 */
	@Transactional
	public ReportRecordResponse append(ReportFamily family, ReportRecordRequest request, String idempotencyKey) {
		if (!family.acceptsRecords()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "family",
					"The time family is derived from the work sessions and cannot accept an appended record.");
		}
		requireKey(idempotencyKey);

		String fingerprint = fingerprint(request);
		Optional<IdempotencyRecord> previous = idempotencyStore.find(idempotencyKey);
		if (previous.isPresent()) {
			if (!previous.get().requestFingerprint().equals(fingerprint)) {
				throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key",
						"The Idempotency-Key was already used with a different payload.");
			}
			// The key is already recorded in this process: answer with the identifier of the original
			// record, read from the table, so a retried write returns the same result and inserts nothing.
			Long recordedId = repository.findIdByIdempotencyKey(tableOf(family), idempotencyKey).orElse(null);
			return new ReportRecordResponse(family, recordedId, request.recordType(), request.occurredAt(), false);
		}

		ReportRecordResponse response;
		try {
			response = switch (family) {
				case FAILURES -> appendFailure(request, idempotencyKey);
				case HALLUCINATION -> appendHallucination(request, idempotencyKey);
				case QUALITY -> appendQuality(request, idempotencyKey);
				case GRAPH_USAGE -> appendGraphUsage(request, idempotencyKey);
				case TOKEN_ECONOMY -> appendTokenEconomy(request, idempotencyKey);
				case SOURCES -> appendSource(request, idempotencyKey);
				case DOWNLOADS -> appendDownload(request, idempotencyKey);
				case UPLOADS -> appendUpload(request, idempotencyKey);
				case TIME -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "family",
						"The time family is derived from the work sessions and cannot accept an appended record.");
			};
		}
		catch (DataIntegrityViolationException exception) {
			// The unique index on the Idempotency-Key is the durable guard: a key already recorded by an
			// earlier process answers with the original record instead of duplicating the effect.
			Optional<Long> existing = repository.findIdByIdempotencyKey(tableOf(family), idempotencyKey);
			if (existing.isEmpty()) {
				throw exception;
			}
			response = new ReportRecordResponse(family, existing.get(), request.recordType(), request.occurredAt(),
					false);
		}

		idempotencyStore.save(new IdempotencyRecord(idempotencyKey, fingerprint, 201, "", Instant.now()));
		return response;
	}

	private ReportRecordResponse appendFailure(ReportRecordRequest request, String idempotencyKey) {
		requireCommon(request);
		requireRecordType(request, ReportRecordType.FAILURE, ReportRecordType.ERRORS);
		FailureType failureType = require(request.failureType(), "failureType");
		Severity severity = require(request.severity(), "severity");
		boolean error = request.recordType() == ReportRecordType.ERRORS;
		if (error) {
			requireText(request.code(), "code");
			requireText(request.message(), "message");
		}

		Long id = repository.insertFailure(new FailureEventRow(request.recordType().wireValue(),
				failureType.wireValue(), severity.wireValue(), request.occurredAt(), request.agentId().trim(),
				request.procedureId(), trimToNull(request.sessionId()), REST_WRITE_SOURCE.wireValue(),
				idempotencyKey));
		if (error) {
			repository.insertErrorDetail(new ErrorDetailRow(id, request.code().trim(), request.message().trim(),
					trimToNull(request.fieldOrResource()), failureType.wireValue(), severity.wireValue(),
					request.agentId().trim(), request.occurredAt()));
		}
		return new ReportRecordResponse(ReportFamily.FAILURES, id, request.recordType(), request.occurredAt(), false);
	}

	private ReportRecordResponse appendHallucination(ReportRecordRequest request, String idempotencyKey) {
		requireCommon(request);
		requireRecordType(request, ReportRecordType.HALLUCINATION);
		requireText(request.responseRef(), "responseRef");

		Long id = repository.insertHallucination(new HallucinationRow(request.occurredAt(), request.agentId().trim(),
				request.procedureId(), trimToNull(request.sessionId()), request.responseRef().trim(),
				ReportRecordType.HALLUCINATION.wireValue(), REST_WRITE_SOURCE.wireValue(), idempotencyKey));
		return new ReportRecordResponse(ReportFamily.HALLUCINATION, id, request.recordType(), request.occurredAt(),
				false);
	}

	private ReportRecordResponse appendQuality(ReportRecordRequest request, String idempotencyKey) {
		requireCommon(request);
		requireRecordType(request, ReportRecordType.QUALITY);
		int score = requireRange(request.score(), "score", 0, 10);
		int adherence = requireRange(request.adherenceLevel(), "adherenceLevel", 0, 4);
		int evidence = requireRange(request.evidenceLevel(), "evidenceLevel", 0, 4);
		int process = requireRange(request.processLevel(), "processLevel", 0, 4);
		int artifact = requireRange(request.artifactLevel(), "artifactLevel", 0, 4);
		int honesty = requireRange(request.honestyLevel(), "honestyLevel", 0, 4);
		requireText(request.rationale(), "rationale");
		EvaluationAuthority authority = require(request.authority(), "authority");

		Long id = repository.insertQuality(new QualityRow(request.occurredAt(), request.agentId().trim(),
				request.procedureId(), trimToNull(request.sessionId()), score, adherence, evidence, process, artifact,
				honesty, request.rationale().trim(), authority.wireValue(), REST_WRITE_SOURCE.wireValue(),
				idempotencyKey));
		return new ReportRecordResponse(ReportFamily.QUALITY, id, request.recordType(), request.occurredAt(), false);
	}

	private ReportRecordResponse appendGraphUsage(ReportRecordRequest request, String idempotencyKey) {
		requireCommon(request);
		requireRecordType(request, ReportRecordType.GRAPH_USAGE);
		int queries = requireRange(request.queryCount(), "queryCount", 0, Integer.MAX_VALUE);
		int nodes = requireRange(request.distinctNodes(), "distinctNodes", 0, Integer.MAX_VALUE);
		boolean beforeWork = require(request.consultedBeforeWork(), "consultedBeforeWork");

		Long id = repository.insertGraphUsage(new GraphUsageRow(request.occurredAt(), request.agentId().trim(),
				request.procedureId(), trimToNull(request.sessionId()), queries, nodes, beforeWork,
				ReportRecordType.GRAPH_USAGE.wireValue(), REST_WRITE_SOURCE.wireValue(), idempotencyKey));
		return new ReportRecordResponse(ReportFamily.GRAPH_USAGE, id, request.recordType(), request.occurredAt(),
				false);
	}

	private ReportRecordResponse appendTokenEconomy(ReportRecordRequest request, String idempotencyKey) {
		requireCommon(request);
		requireRecordType(request, ReportRecordType.TOKEN_ECONOMY);
		requireText(request.modelId(), "modelId");
		long input = requireNonNegative(request.inputTokens(), "inputTokens");
		long output = requireNonNegative(request.outputTokens(), "outputTokens");
		boolean measurable = require(request.measurable(), "measurable");

		Long saving = request.savingsTokens();
		if (measurable) {
			if (saving == null) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR, "savingsTokens",
						"A measurable saving requires the saving tokens.");
			}
			if (request.baselineInputTokens() == null || request.baselineOutputTokens() == null) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR, "baselineInputTokens",
						"A measurable saving requires the baseline of the same unit and the same model.");
			}
		}
		else {
			if (request.notMeasurableReason() == null || request.notMeasurableReason().isBlank()) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR, "notMeasurableReason",
						"A not measurable saving requires the reason.");
			}
			saving = null;
		}

		Long id = repository.insertTokenEconomy(new TokenEconomyRow(request.occurredAt(), request.agentId().trim(),
				request.procedureId(), trimToNull(request.sessionId()), input, output,
				request.baselineInputTokens(), request.baselineOutputTokens(), saving, measurable,
				measurable ? null : request.notMeasurableReason().trim(), request.modelId().trim(),
				ReportRecordType.TOKEN_ECONOMY.wireValue(), REST_WRITE_SOURCE.wireValue(), idempotencyKey));
		return new ReportRecordResponse(ReportFamily.TOKEN_ECONOMY, id, request.recordType(), request.occurredAt(),
				false);
	}

	private ReportRecordResponse appendSource(ReportRecordRequest request, String idempotencyKey) {
		requireCommon(request);
		requireRecordType(request, ReportRecordType.SOURCE);
		SourceOrigin origin = require(request.origin(), "origin");
		EventResult result = require(request.result(), "result");
		requireText(request.source(), "source");
		requireText(request.taskContext(), "taskContext");
		requireText(request.purpose(), "purpose");

		RedactedFields redacted = redactionComponent.redactAll(Map.of("source", request.source(),
				"taskContext", request.taskContext(), "purpose", request.purpose()));
		boolean wasRedacted = redacted.redacted() || Boolean.TRUE.equals(request.redacted());
		log.debug("Source record accepted, redaction rules fired: {}", redacted.rules());

		Long id = repository.insertSource(new SourceRow(request.occurredAt(), request.agentId().trim(),
				request.procedureId(), trimToNull(request.sessionId()), redacted.values().get("source"),
				origin.wireValue(), redacted.values().get("taskContext"), redacted.values().get("purpose"),
				result.wireValue(), request.cited(), wasRedacted, ReportRecordType.SOURCE.wireValue(),
				REST_WRITE_SOURCE.wireValue(), idempotencyKey));
		return new ReportRecordResponse(ReportFamily.SOURCES, id, request.recordType(), request.occurredAt(),
				wasRedacted);
	}

	private ReportRecordResponse appendDownload(ReportRecordRequest request, String idempotencyKey) {
		requireCommon(request);
		requireRecordType(request, ReportRecordType.DOWNLOAD);
		EventResult result = require(request.result(), "result");
		requireText(request.source(), "source");
		requireText(request.item(), "item");
		requireText(request.itemType(), "itemType");
		requireText(request.destination(), "destination");
		long size = requireNonNegative(request.sizeBytes(), "sizeBytes");

		RedactedFields redacted = redactionComponent.redactAll(Map.of("source", request.source(),
				"item", request.item(), "destination", request.destination()));
		boolean wasRedacted = redacted.redacted() || Boolean.TRUE.equals(request.redacted());

		Long id = repository.insertDownload(new DownloadRow(request.occurredAt(), request.agentId().trim(),
				request.procedureId(), trimToNull(request.sessionId()), redacted.values().get("source"),
				redacted.values().get("item"), request.itemType().trim(), size, result.wireValue(),
				redacted.values().get("destination"), wasRedacted, ReportRecordType.DOWNLOAD.wireValue(),
				REST_WRITE_SOURCE.wireValue(), idempotencyKey));
		return new ReportRecordResponse(ReportFamily.DOWNLOADS, id, request.recordType(), request.occurredAt(),
				wasRedacted);
	}

	private ReportRecordResponse appendUpload(ReportRecordRequest request, String idempotencyKey) {
		requireCommon(request);
		requireRecordType(request, ReportRecordType.UPLOAD);
		EventResult result = require(request.result(), "result");
		requireText(request.destination(), "destination");
		requireText(request.item(), "item");
		requireText(request.itemType(), "itemType");
		long size = requireNonNegative(request.sizeBytes(), "sizeBytes");
		boolean sent = require(request.sent(), "sent");

		RedactedFields redacted = redactionComponent.redactAll(Map.of("destination", request.destination(),
				"item", request.item()));
		boolean wasRedacted = redacted.redacted() || Boolean.TRUE.equals(request.redacted());

		Long id = repository.insertUpload(new UploadRow(request.occurredAt(), request.agentId().trim(),
				request.procedureId(), trimToNull(request.sessionId()), redacted.values().get("destination"),
				redacted.values().get("item"), request.itemType().trim(), size, result.wireValue(), sent,
				wasRedacted, ReportRecordType.UPLOAD.wireValue(), REST_WRITE_SOURCE.wireValue(), idempotencyKey));
		return new ReportRecordResponse(ReportFamily.UPLOADS, id, request.recordType(), request.occurredAt(),
				wasRedacted);
	}

	private String tableOf(ReportFamily family) {
		return switch (family) {
			case FAILURES -> "failure_event";
			case HALLUCINATION -> "hallucination_event";
			case QUALITY -> "quality_evaluation";
			case GRAPH_USAGE -> "graph_usage_event";
			case TOKEN_ECONOMY -> "token_economy_event";
			case SOURCES -> "source_event";
			case DOWNLOADS -> "download_event";
			case UPLOADS -> "upload_event";
			case TIME -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "family",
					"The time family is derived from the work sessions and cannot accept an appended record.");
		};
	}

	private void requireCommon(ReportRecordRequest request) {
		if (request == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, null, "The record is missing.");
		}
		require(request.occurredAt(), "occurredAt");
		requireText(request.agentId(), "agentId");
		require(request.recordType(), "recordType");
	}

	private void requireRecordType(ReportRecordRequest request, ReportRecordType... allowed) {
		ReportRecordType recordType = request.recordType();
		for (ReportRecordType candidate : allowed) {
			if (candidate == recordType) {
				return;
			}
		}
		throw new ApiException(ErrorCode.VALIDATION_ERROR, "recordType",
				"The record type does not belong to the family of the path.");
	}

	private void requireKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISSING, "Idempotency-Key",
					"Every write must carry the Idempotency-Key header.");
		}
	}

	private void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, field, "The field is required.");
		}
	}

	private <T> T require(T value, String field) {
		if (value == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, field, "The field is required.");
		}
		return value;
	}

	private int requireRange(Integer value, String field, int minimum, int maximum) {
		int resolved = require(value, field);
		if (resolved < minimum || resolved > maximum) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, field,
					"The value must be between " + minimum + " and " + maximum + ".");
		}
		return resolved;
	}

	private long requireNonNegative(Long value, String field) {
		long resolved = require(value, field);
		if (resolved < 0) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, field, "The value must not be negative.");
		}
		return resolved;
	}

	private String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private String fingerprint(ReportRecordRequest request) {
		StringBuilder canonical = new StringBuilder();
		append(canonical, request.recordType());
		append(canonical, request.failureType());
		append(canonical, request.severity());
		append(canonical, request.occurredAt());
		append(canonical, request.agentId());
		append(canonical, request.procedureId());
		append(canonical, request.sessionId());
		append(canonical, request.source());
		append(canonical, request.origin());
		append(canonical, request.taskContext());
		append(canonical, request.purpose());
		append(canonical, request.result());
		append(canonical, request.cited());
		append(canonical, request.sent());
		append(canonical, request.redacted());
		append(canonical, request.sizeBytes());
		append(canonical, request.destination());
		append(canonical, request.item());
		append(canonical, request.itemType());
		append(canonical, request.score());
		append(canonical, request.adherenceLevel());
		append(canonical, request.evidenceLevel());
		append(canonical, request.processLevel());
		append(canonical, request.artifactLevel());
		append(canonical, request.honestyLevel());
		append(canonical, request.rationale());
		append(canonical, request.authority());
		append(canonical, request.queryCount());
		append(canonical, request.distinctNodes());
		append(canonical, request.consultedBeforeWork());
		append(canonical, request.inputTokens());
		append(canonical, request.outputTokens());
		append(canonical, request.baselineInputTokens());
		append(canonical, request.baselineOutputTokens());
		append(canonical, request.savingsTokens());
		append(canonical, request.measurable());
		append(canonical, request.notMeasurableReason());
		append(canonical, request.modelId());
		append(canonical, request.responseRef());
		append(canonical, request.code());
		append(canonical, request.message());
		append(canonical, request.fieldOrResource());
		return sha256(canonical.toString());
	}

	private void append(StringBuilder builder, Object value) {
		builder.append(value == null ? "" : value.toString()).append('\u001f');
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this runtime.", exception);
		}
	}

	/**
	 * Lists the append only tables of the eight ingestible families plus the error detail table.
	 *
	 * @return the table names
	 */
	public static List<String> appendOnlyTables() {
		return List.of("failure_event", "error_detail", "hallucination_event", "quality_evaluation",
				"graph_usage_event", "token_economy_event", "source_event", "download_event", "upload_event");
	}
}
