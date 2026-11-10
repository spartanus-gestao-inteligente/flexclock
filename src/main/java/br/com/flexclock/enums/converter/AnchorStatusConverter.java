package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.AnchorStatus;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every AnchorStatus attribute.
 */
@Converter(autoApply = true)
public class AnchorStatusConverter extends WireValueConverter<AnchorStatus> {

	public AnchorStatusConverter() {
		super(AnchorStatus.class);
	}
}
