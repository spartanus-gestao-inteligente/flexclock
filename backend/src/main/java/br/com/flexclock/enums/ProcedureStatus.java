package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle of a procedure, one to one with an OpenCode session. Values frozen by the architecture contract, section 4.3.
 */
public enum ProcedureStatus implements WireValue {

	ACTIVE("active"),
	COMPLETED("completed"),
	ABORTED("aborted");

	private final String wireValue;

	ProcedureStatus(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static ProcedureStatus fromWireValue(String value) {
		return WireValue.fromWireValue(ProcedureStatus.class, value);
	}
}
