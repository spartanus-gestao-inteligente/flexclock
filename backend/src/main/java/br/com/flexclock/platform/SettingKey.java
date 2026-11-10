package br.com.flexclock.platform;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.enums.SettingOwner;
import br.com.flexclock.enums.SettingValueType;
import br.com.flexclock.opencode.OpenCodeEndpoint;

/**
 * Closed catalogue of the settings keys.
 *
 * <p>The product accepts no other key: an unknown key is rejected with
 * {@link ErrorCode#NOT_FOUND} instead of being stored, so the configuration surface can never grow
 * silently (requirement RF-008). Each key declares its value type, the range that applies, the writer
 * that owns it and the domain rule that every new value must satisfy. The same declaration is seeded
 * in migration V2, and a test fails the build when the two disagree.
 *
 * <p>{@link SettingOwner} is the owner of the key, which means the principal allowed to change it. A
 * key owned by {@link SettingOwner#USER} is exclusive to the user and is never writable by the agent,
 * even when the user asks the agent to change a setting (decision 0026 limited by decision 0050;
 * requirements RF-162 and RNF-051).
 *
 * <p>Language, timezone, orchestration model, report default period, default time mode and target,
 * OpenCode endpoint and region clock source are the configuration named by the architecture contract,
 * section 4.1, and by decisions 0019, 0020, 0026 and 0043.
 */
public enum SettingKey {

	/**
	 * Software language that drives the interface, the reports and the date and number formats.
	 *
	 * <p>English is the canonical source and the fallback (decision 0019; requirement RF-130).
	 */
	LANGUAGE("language", SettingValueType.ENUM, SettingOwner.AGENT, "en", null, null,
			LanguageCatalogue::requireSupported),

	/**
	 * Timezone used to resolve report periods and to split a session that crosses midnight.
	 *
	 * <p>Instants are always stored in UTC; the timezone only shapes the reporting boundaries and is
	 * never written into a stored instant (decision 0019; requirement RF-009).
	 */
	TIMEZONE("timezone", SettingValueType.STRING, SettingOwner.AGENT, "Etc/UTC", null, null,
			SettingKey::requireZoneId),

	/**
	 * Orchestration model the product applies to the agents it manages.
	 *
	 * <p>The central coordinator is the recommended default; pipeline and swarm are offered with an
	 * explanation and are never forced (decision 0026).
	 */
	ORCHESTRATION_MODEL("orchestration.model", SettingValueType.ENUM, SettingOwner.AGENT, "central_coordinator",
			null, null, value -> requireMember("orchestration.model", value, Set.of("central_coordinator", "pipeline",
					"swarm"))),

	/**
	 * Period a report uses when the caller does not state one.
	 *
	 * <p>The default is one day (decision 0019). The report language follows {@link #LANGUAGE} and the
	 * date format follows the language locale, so no second language key exists.
	 */
	REPORT_DEFAULT_PERIOD("report.default.period", SettingValueType.ENUM, SettingOwner.AGENT, "day", null, null,
			value -> requireMember("report.default.period", value, Set.of("session", "day", "week", "month",
					"custom"))),

	/**
	 * Time mode applied to a work session that does not state one.
	 *
	 * <p>The backend applies the configured default; the interface never switches the mode locally
	 * (decisions 0020 and 0057; rule RN-055).
	 */
	TIME_DEFAULT_MODE("time.default-mode", SettingValueType.ENUM, SettingOwner.AGENT, "stopwatch", null, null,
			value -> requireMember("time.default-mode", value, Set.of("stopwatch", "project_stopwatch"))),

	/**
	 * Target of the countdown in project stopwatch mode, in seconds.
	 *
	 * <p>Bounded between one second and one day so a malformed target can never produce a negative or
	 * absurd countdown (decision 0005; rule RN-055).
	 */
	TIME_DEFAULT_TARGET_SECONDS("time.default.target-seconds", SettingValueType.INTEGER, SettingOwner.AGENT, "3600",
			"1", "86400", SettingKey::normalizeSeconds),

	/**
	 * Endpoint of the local OpenCode server.
	 *
	 * <p>Loopback only, http only and without user information, validated when the value is written and
	 * again on every use (decision 0043 and 0046; requirement RF-150). Owned by the user because the
	 * listen and connection addresses are exclusive user settings (requirement RF-162).
	 */
	OPENCODE_ENDPOINT("opencode.endpoint", SettingValueType.URL, SettingOwner.USER, "http://127.0.0.1:4096", null,
			null, SettingKey::requireOpenCodeEndpoint),

	/**
	 * Source of the region clock.
	 *
	 * <p>{@code system} follows the machine clock; {@code region} follows the user region clock and
	 * falls back to the machine clock when there is no internet (decision 0019).
	 */
	REGION_CLOCK_SOURCE("region.clock-source", SettingValueType.ENUM, SettingOwner.AGENT, "system", null, null,
			value -> requireMember("region.clock-source", value, Set.of("system", "region")));

	/** Maximum length accepted for a stored setting value, matching the {@code value} column. */
	public static final int MAX_VALUE_LENGTH = 2000;

	private final String wireKey;
	private final SettingValueType valueType;
	private final SettingOwner owner;
	private final String defaultValue;
	private final String minValue;
	private final String maxValue;
	private final SettingValueDomain domain;

	SettingKey(String wireKey, SettingValueType valueType, SettingOwner owner, String defaultValue, String minValue,
			String maxValue, SettingValueDomain domain) {
		this.wireKey = wireKey;
		this.valueType = valueType;
		this.owner = owner;
		this.defaultValue = defaultValue;
		this.minValue = minValue;
		this.maxValue = maxValue;
		this.domain = domain;
	}

	/**
	 * Resolves a key presented by a client.
	 *
	 * @param presented the key of the request
	 * @return the catalogue entry
	 * @throws ApiException with {@link ErrorCode#NOT_FOUND} when the key is not part of the catalogue
	 */
	public static SettingKey fromWireKey(String presented) {
		if (presented != null) {
			for (SettingKey key : values()) {
				if (key.wireKey.equalsIgnoreCase(presented.trim())) {
					return key;
				}
			}
		}
		throw new ApiException(ErrorCode.NOT_FOUND, "key",
				"The setting key is not part of the closed catalogue: " + presented + ".");
	}

	/**
	 * Validates and normalizes a value for this key.
	 *
	 * @param presented the value of the request
	 * @return the canonical value to store
	 * @throws ApiException when the value is outside the type, the range or the domain of the key
	 */
	public String normalize(String presented) {
		if (presented == null || presented.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, wireKey, "The setting value must not be blank.");
		}
		String candidate = presented.trim();
		if (candidate.length() > MAX_VALUE_LENGTH) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, wireKey,
					"The setting value is longer than the " + MAX_VALUE_LENGTH + " characters the column allows.");
		}
		String normalized = domain.normalize(candidate);
		requireInsideRange(normalized);
		return normalized;
	}

	/**
	 * Reports whether a principal may change this key.
	 *
	 * @param actor the principal performing the write
	 * @return true when the actor owns the key or is the user
	 */
	public boolean isWritableBy(SettingActor actor) {
		return owner != SettingOwner.USER || actor == SettingActor.USER;
	}

	/**
	 * Returns the key as published by the API and stored in the database.
	 *
	 * @return the wire key
	 */
	public String wireKey() {
		return wireKey;
	}

	/**
	 * Returns the declared value type.
	 *
	 * @return the value type
	 */
	public SettingValueType valueType() {
		return valueType;
	}

	/**
	 * Returns the owner, which is the principal allowed to change the key.
	 *
	 * @return the owner
	 */
	public SettingOwner owner() {
		return owner;
	}

	/**
	 * Returns the seeded default, used when the row is absent.
	 *
	 * @return the default value
	 */
	public String defaultValue() {
		return defaultValue;
	}

	/**
	 * Returns the declared lower bound, or null when the type has none.
	 *
	 * @return the lower bound
	 */
	public String minValue() {
		return minValue;
	}

	/**
	 * Returns the declared upper bound, or null when the type has none.
	 *
	 * @return the upper bound
	 */
	public String maxValue() {
		return maxValue;
	}

	private void requireInsideRange(String value) {
		if (valueType != SettingValueType.INTEGER) {
			return;
		}
		int parsed = requireSeconds(wireKey, value);
		if (minValue != null && parsed < Integer.parseInt(minValue)) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, wireKey,
					"The value must be greater than or equal to " + minValue + ".");
		}
		if (maxValue != null && parsed > Integer.parseInt(maxValue)) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, wireKey,
					"The value must be less than or equal to " + maxValue + ".");
		}
	}

	private static String requireMember(String key, String value, Set<String> allowed) {
		String normalized = value.toLowerCase(Locale.ROOT);
		if (!allowed.contains(normalized)) {
			throw new ApiException(ErrorCode.INVALID_ENUM_VALUE, key,
					"The value is not part of the closed set " + allowed + ".");
		}
		return normalized;
	}

	private static String requireZoneId(String value) {
		try {
			return ZoneId.of(value).getId();
		}
		catch (DateTimeException exception) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, "timezone",
					"The value is not a valid IANA timezone identifier.");
		}
	}

	private static int requireSeconds(String key, String value) {
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException exception) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, key, "The value must be an integer.");
		}
	}

	private static String normalizeSeconds(String value) {
		return Integer.toString(requireSeconds("time.default.target-seconds", value));
	}

	private static String requireOpenCodeEndpoint(String value) {
		try {
			return OpenCodeEndpoint.require(value);
		}
		catch (OpenCodeEndpoint.InvalidEndpointException exception) {
			throw new ApiException(ErrorCode.VALUE_OUT_OF_RANGE, "opencode.endpoint", exception.getMessage());
		}
	}

	/**
	 * Rule of the value domain of a key. It returns the canonical value to store or throws an
	 * {@link ApiException} with the stable code of the violation.
	 */
	@FunctionalInterface
	private interface SettingValueDomain {

		/**
		 * Validates and normalizes a value.
		 *
		 * @param value the trimmed value
		 * @return the canonical value
		 */
		String normalize(String value);
	}
}
