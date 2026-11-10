package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.PauseReason;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every PauseReason attribute.
 */
@Converter(autoApply = true)
public class PauseReasonConverter extends WireValueConverter<PauseReason> {

	public PauseReasonConverter() {
		super(PauseReason.class);
	}
}
