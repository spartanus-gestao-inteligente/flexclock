package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.Severity;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every Severity attribute.
 */
@Converter(autoApply = true)
public class SeverityConverter extends WireValueConverter<Severity> {

	public SeverityConverter() {
		super(Severity.class);
	}
}
