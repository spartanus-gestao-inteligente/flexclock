package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.SourceOrigin;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every SourceOrigin attribute.
 */
@Converter(autoApply = true)
public class SourceOriginConverter extends WireValueConverter<SourceOrigin> {

	public SourceOriginConverter() {
		super(SourceOrigin.class);
	}
}
