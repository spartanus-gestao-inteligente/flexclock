package br.com.flexclock.dto;

import java.time.Instant;

import br.com.flexclock.enums.SettingOwner;
import br.com.flexclock.enums.SettingValueType;

/**
 * One setting of the closed catalogue.
 *
 * <p>Shape frozen by the architecture contract, section 6.2. The owner states which principal may
 * change the key, so the interface can render a user owned key as read only for the agent.
 *
 * @param key the closed key
 * @param value the current canonical value
 * @param valueType the declared type of the value
 * @param owner the principal allowed to change the key
 * @param minValue the declared lower bound, null when the type has none
 * @param maxValue the declared upper bound, null when the type has none
 * @param updatedBy the principal that wrote the current value
 * @param updatedAt the instant of the last change, null when the row is absent
 */
public record SettingResponse(String key, String value, SettingValueType valueType, SettingOwner owner,
		String minValue, String maxValue, String updatedBy, Instant updatedAt) {
}
