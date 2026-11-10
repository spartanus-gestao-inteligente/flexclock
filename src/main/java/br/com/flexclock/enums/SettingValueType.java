package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Type of the value stored by a setting. Values frozen by the architecture contract, section 4.3.
 */
public enum SettingValueType implements WireValue {

	STRING("string"),
	INTEGER("integer"),
	ENUM("enum"),
	URL("url");

	private final String wireValue;

	SettingValueType(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static SettingValueType fromWireValue(String value) {
		return WireValue.fromWireValue(SettingValueType.class, value);
	}
}
