package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Surface that wrote a report record. Values frozen by the architecture contract, section 4.3.
 */
public enum WriteSource implements WireValue {

	REST("rest"),
	MCP("mcp"),
	EVENT_SERVICE("event_service");

	private final String wireValue;

	WriteSource(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static WriteSource fromWireValue(String value) {
		return WireValue.fromWireValue(WriteSource.class, value);
	}
}
