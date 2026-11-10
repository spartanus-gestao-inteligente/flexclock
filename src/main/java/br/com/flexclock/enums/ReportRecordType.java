package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Closed type of a report record, including the errors family. Values frozen by the architecture contract, section 4.3.
 */
public enum ReportRecordType implements WireValue {

	FAILURE("failure"),
	HALLUCINATION("hallucination"),
	QUALITY("quality"),
	GRAPH_USAGE("graph_usage"),
	TOKEN_ECONOMY("token_economy"),
	SOURCE("source"),
	DOWNLOAD("download"),
	UPLOAD("upload"),
	ERRORS("errors");

	private final String wireValue;

	ReportRecordType(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static ReportRecordType fromWireValue(String value) {
		return WireValue.fromWireValue(ReportRecordType.class, value);
	}
}
