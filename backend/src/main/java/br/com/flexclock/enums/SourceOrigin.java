package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Origin of a consulted source. Values frozen by the architecture contract, section 4.3.
 */
public enum SourceOrigin implements WireValue {

	DOMAIN("domain"),
	CORPUS("corpus"),
	GRAPH("graph");

	private final String wireValue;

	SourceOrigin(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static SourceOrigin fromWireValue(String value) {
		return WireValue.fromWireValue(SourceOrigin.class, value);
	}
}
