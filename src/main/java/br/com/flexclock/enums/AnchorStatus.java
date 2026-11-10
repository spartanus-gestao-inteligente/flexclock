package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Liveness of an anchored session. Values frozen by the architecture contract, section 4.3.
 */
public enum AnchorStatus implements WireValue {

	ACTIVE("active"),
	STALE("stale");

	private final String wireValue;

	AnchorStatus(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static AnchorStatus fromWireValue(String value) {
		return WireValue.fromWireValue(AnchorStatus.class, value);
	}
}
