package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle of an agent pause record. Values frozen by the architecture contract, section 4.3.
 */
public enum PauseStatus implements WireValue {

	PAUSED("paused"),
	RESUMED("resumed"),
	EXPIRED("expired"),
	ABORTED("aborted");

	private final String wireValue;

	PauseStatus(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static PauseStatus fromWireValue(String value) {
		return WireValue.fromWireValue(PauseStatus.class, value);
	}
}
