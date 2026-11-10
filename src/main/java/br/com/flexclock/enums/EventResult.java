package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Result of a consulted source, a download or an upload. Values frozen by the architecture contract, section 4.3.
 */
public enum EventResult implements WireValue {

	SUCCESS("success"),
	FAILURE("failure");

	private final String wireValue;

	EventResult(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static EventResult fromWireValue(String value) {
		return WireValue.fromWireValue(EventResult.class, value);
	}
}
