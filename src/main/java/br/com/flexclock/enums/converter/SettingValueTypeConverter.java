package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.SettingValueType;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every SettingValueType attribute.
 */
@Converter(autoApply = true)
public class SettingValueTypeConverter extends WireValueConverter<SettingValueType> {

	public SettingValueTypeConverter() {
		super(SettingValueType.class);
	}
}
