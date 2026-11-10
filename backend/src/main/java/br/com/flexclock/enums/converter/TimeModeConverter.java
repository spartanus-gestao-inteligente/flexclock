package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.TimeMode;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every TimeMode attribute.
 */
@Converter(autoApply = true)
public class TimeModeConverter extends WireValueConverter<TimeMode> {

	public TimeModeConverter() {
		super(TimeMode.class);
	}
}
