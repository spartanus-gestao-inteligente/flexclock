package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.WriteSource;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every WriteSource attribute.
 */
@Converter(autoApply = true)
public class WriteSourceConverter extends WireValueConverter<WriteSource> {

	public WriteSourceConverter() {
		super(WriteSource.class);
	}
}
