package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Reason recorded when a pause ends without a resume. Values frozen by the architecture contract, section 4.3.
 */
public enum PauseReason implements WireValue {

	USER_CANCEL("user_cancel"),
	SESSION_GONE("session_gone"),
	PAUSE_FAILED("pause_failed");

	private final String wireValue;

	PauseReason(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static PauseReason fromWireValue(String value) {
		return WireValue.fromWireValue(PauseReason.class, value);
	}
}
