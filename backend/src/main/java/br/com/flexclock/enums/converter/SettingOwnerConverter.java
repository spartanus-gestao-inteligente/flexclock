package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.SettingOwner;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every SettingOwner attribute.
 */
@Converter(autoApply = true)
public class SettingOwnerConverter extends WireValueConverter<SettingOwner> {

	public SettingOwnerConverter() {
		super(SettingOwner.class);
	}
}
