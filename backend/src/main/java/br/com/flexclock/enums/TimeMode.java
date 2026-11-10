package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Time measurement mode of a work session. Values frozen by the architecture contract, section 4.3.
 */
public enum TimeMode implements WireValue {

	STOPWATCH("stopwatch"),
	PROJECT_STOPWATCH("project_stopwatch");

	private final String wireValue;

	TimeMode(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static TimeMode fromWireValue(String value) {
		return WireValue.fromWireValue(TimeMode.class, value);
	}
}
