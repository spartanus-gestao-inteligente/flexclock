package br.com.flexclock.support;

import java.time.Instant;

import br.com.flexclock.dto.ReportRecordRequest;
import br.com.flexclock.enums.EvaluationAuthority;
import br.com.flexclock.enums.EventResult;
import br.com.flexclock.enums.FailureType;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.Severity;
import br.com.flexclock.enums.SourceOrigin;

/**
 * Small fluent builder for the report record of a test.
 *
 * <p>The request record has one field per column of the nine families, so building one positionally in
 * a test would be unreadable and fragile. The builder keeps a readable named field per value and
 * produces the record the contract defines.
 */
public final class ReportRequestBuilder {

	private ReportRecordType recordType;
	private FailureType failureType;
	private Severity severity;
	private Instant occurredAt = Instant.parse("2026-01-01T10:00:00Z");
	private String agentId = "agent-test";
	private Long procedureId;
	private String sessionId;
	private String source;
	private SourceOrigin origin;
	private String taskContext;
	private String purpose;
	private EventResult result;
	private Boolean cited;
	private Boolean sent;
	private Boolean redacted;
	private Long sizeBytes;
	private String destination;
	private String item;
	private String itemType;
	private Integer score;
	private Integer adherenceLevel;
	private Integer evidenceLevel;
	private Integer processLevel;
	private Integer artifactLevel;
	private Integer honestyLevel;
	private String rationale;
	private EvaluationAuthority authority;
	private Integer queryCount;
	private Integer distinctNodes;
	private Boolean consultedBeforeWork;
	private Long inputTokens;
	private Long outputTokens;
	private Long baselineInputTokens;
	private Long baselineOutputTokens;
	private Long savingsTokens;
	private Boolean measurable;
	private String notMeasurableReason;
	private String modelId;
	private String responseRef;
	private String code;
	private String message;
	private String fieldOrResource;

	private ReportRequestBuilder(ReportRecordType recordType) {
		this.recordType = recordType;
	}

	/**
	 * Starts a record of a type.
	 *
	 * @param recordType the closed record type
	 * @return the builder
	 */
	public static ReportRequestBuilder of(ReportRecordType recordType) {
		return new ReportRequestBuilder(recordType);
	}

	public ReportRequestBuilder failureType(FailureType value) {
		this.failureType = value;
		return this;
	}

	public ReportRequestBuilder severity(Severity value) {
		this.severity = value;
		return this;
	}

	public ReportRequestBuilder occurredAt(Instant value) {
		this.occurredAt = value;
		return this;
	}

	public ReportRequestBuilder agentId(String value) {
		this.agentId = value;
		return this;
	}

	public ReportRequestBuilder procedureId(Long value) {
		this.procedureId = value;
		return this;
	}

	public ReportRequestBuilder sessionId(String value) {
		this.sessionId = value;
		return this;
	}

	public ReportRequestBuilder source(String value) {
		this.source = value;
		return this;
	}

	public ReportRequestBuilder origin(SourceOrigin value) {
		this.origin = value;
		return this;
	}

	public ReportRequestBuilder taskContext(String value) {
		this.taskContext = value;
		return this;
	}

	public ReportRequestBuilder purpose(String value) {
		this.purpose = value;
		return this;
	}

	public ReportRequestBuilder result(EventResult value) {
		this.result = value;
		return this;
	}

	public ReportRequestBuilder cited(Boolean value) {
		this.cited = value;
		return this;
	}

	public ReportRequestBuilder sent(Boolean value) {
		this.sent = value;
		return this;
	}

	public ReportRequestBuilder redacted(Boolean value) {
		this.redacted = value;
		return this;
	}

	public ReportRequestBuilder sizeBytes(Long value) {
		this.sizeBytes = value;
		return this;
	}

	public ReportRequestBuilder destination(String value) {
		this.destination = value;
		return this;
	}

	public ReportRequestBuilder item(String value) {
		this.item = value;
		return this;
	}

	public ReportRequestBuilder itemType(String value) {
		this.itemType = value;
		return this;
	}

	public ReportRequestBuilder score(Integer value) {
		this.score = value;
		return this;
	}

	public ReportRequestBuilder levels(int level) {
		this.adherenceLevel = level;
		this.evidenceLevel = level;
		this.processLevel = level;
		this.artifactLevel = level;
		this.honestyLevel = level;
		return this;
	}

	public ReportRequestBuilder rationale(String value) {
		this.rationale = value;
		return this;
	}

	public ReportRequestBuilder authority(EvaluationAuthority value) {
		this.authority = value;
		return this;
	}

	public ReportRequestBuilder queryCount(Integer value) {
		this.queryCount = value;
		return this;
	}

	public ReportRequestBuilder distinctNodes(Integer value) {
		this.distinctNodes = value;
		return this;
	}

	public ReportRequestBuilder consultedBeforeWork(Boolean value) {
		this.consultedBeforeWork = value;
		return this;
	}

	public ReportRequestBuilder inputTokens(Long value) {
		this.inputTokens = value;
		return this;
	}

	public ReportRequestBuilder outputTokens(Long value) {
		this.outputTokens = value;
		return this;
	}

	public ReportRequestBuilder baseline(Long input, Long output) {
		this.baselineInputTokens = input;
		this.baselineOutputTokens = output;
		return this;
	}

	public ReportRequestBuilder savingsTokens(Long value) {
		this.savingsTokens = value;
		return this;
	}

	public ReportRequestBuilder measurable(Boolean value) {
		this.measurable = value;
		return this;
	}

	public ReportRequestBuilder notMeasurableReason(String value) {
		this.notMeasurableReason = value;
		return this;
	}

	public ReportRequestBuilder modelId(String value) {
		this.modelId = value;
		return this;
	}

	public ReportRequestBuilder responseRef(String value) {
		this.responseRef = value;
		return this;
	}

	public ReportRequestBuilder code(String value) {
		this.code = value;
		return this;
	}

	public ReportRequestBuilder message(String value) {
		this.message = value;
		return this;
	}

	public ReportRequestBuilder fieldOrResource(String value) {
		this.fieldOrResource = value;
		return this;
	}

	/**
	 * Builds the request record.
	 *
	 * @return the record the contract defines
	 */
	public ReportRecordRequest build() {
		return new ReportRecordRequest(recordType, failureType, severity, occurredAt, agentId, procedureId,
				sessionId, source, origin, taskContext, purpose, result, cited, sent, redacted, sizeBytes,
				destination, item, itemType, score, adherenceLevel, evidenceLevel, processLevel, artifactLevel,
				honestyLevel, rationale, authority, queryCount, distinctNodes, consultedBeforeWork, inputTokens,
				outputTokens, baselineInputTokens, baselineOutputTokens, savingsTokens, measurable,
				notMeasurableReason, modelId, responseRef, code, message, fieldOrResource);
	}
}
