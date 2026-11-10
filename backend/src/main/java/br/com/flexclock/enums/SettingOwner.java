package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Who owns a setting and therefore who may change it. Values frozen by the architecture contract, section 4.3. Replaces the scaffold enumerator named Owner.
 */
public enum SettingOwner implements WireValue {

	USER("user"),
	AGENT("agent");

	private final String wireValue;

	SettingOwner(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static SettingOwner fromWireValue(String value) {
		return WireValue.fromWireValue(SettingOwner.class, value);
	}
}
