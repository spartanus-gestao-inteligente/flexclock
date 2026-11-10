package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Severity of a failure or error record. Values frozen by the architecture contract, section 4.3.
 */
public enum Severity implements WireValue {

	LOW("low"),
	MEDIUM("medium"),
	HIGH("high"),
	CRITICAL("critical");

	private final String wireValue;

	Severity(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static Severity fromWireValue(String value) {
		return WireValue.fromWireValue(Severity.class, value);
	}
}
