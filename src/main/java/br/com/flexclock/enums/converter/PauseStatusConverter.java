package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.PauseStatus;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every PauseStatus attribute.
 */
@Converter(autoApply = true)
public class PauseStatusConverter extends WireValueConverter<PauseStatus> {

	public PauseStatusConverter() {
		super(PauseStatus.class);
	}
}
