package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.FailureType;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every FailureType attribute.
 */
@Converter(autoApply = true)
public class FailureTypeConverter extends WireValueConverter<FailureType> {

	public FailureTypeConverter() {
		super(FailureType.class);
	}
}
