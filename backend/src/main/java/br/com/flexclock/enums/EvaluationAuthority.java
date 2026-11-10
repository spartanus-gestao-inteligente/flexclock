package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Authority of a quality evaluation: the agent self assessment or the owner correction. Values frozen by the architecture contract, section 4.3.
 */
public enum EvaluationAuthority implements WireValue {

	SELF("SELF"),
	OWNER("OWNER");

	private final String wireValue;

	EvaluationAuthority(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static EvaluationAuthority fromWireValue(String value) {
		return WireValue.fromWireValue(EvaluationAuthority.class, value);
	}
}
