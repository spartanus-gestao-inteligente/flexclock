package br.com.flexclock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of a settings change.
 *
 * <p>Shape frozen by the architecture contract, section 6.2. The value is validated again against the
 * type and the domain of the key, because the bound of the column is not a domain rule (requirement
 * RF-008).
 *
 * @param value the new value of the setting
 */
public record SettingUpdateRequest(@NotBlank @Size(max = 2000) String value) {
}
