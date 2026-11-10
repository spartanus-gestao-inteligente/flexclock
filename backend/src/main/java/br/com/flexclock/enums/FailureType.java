package br.com.flexclock.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Closed type of an agent failure. Values frozen by the architecture contract, section 4.3.
 */
public enum FailureType implements WireValue {

	HALLUCINATION("hallucination"),
	WRONG_DATA("wrong_data"),
	TOOL_ERROR("tool_error"),
	TIMEOUT("timeout"),
	DB_WRITE_FAILURE("db_write_failure"),
	BUILD_FAILURE("build_failure"),
	TEST_FAILURE("test_failure"),
	DEPLOY_FAILURE("deploy_failure"),
	LOGIC_ERROR("logic_error"),
	SCOPE_ERROR("scope_error"),
	PERMISSION_ERROR("permission_error"),
	NETWORK_ERROR("network_error"),
	UNKNOWN("unknown");

	private final String wireValue;

	FailureType(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	@JsonValue
	public String wireValue() {
		return wireValue;
	}

	@JsonCreator
	public static FailureType fromWireValue(String value) {
		return WireValue.fromWireValue(FailureType.class, value);
	}
}
