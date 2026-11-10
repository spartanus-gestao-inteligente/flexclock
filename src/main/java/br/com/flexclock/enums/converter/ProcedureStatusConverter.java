package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.ProcedureStatus;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every ProcedureStatus attribute.
 */
@Converter(autoApply = true)
public class ProcedureStatusConverter extends WireValueConverter<ProcedureStatus> {

	public ProcedureStatusConverter() {
		super(ProcedureStatus.class);
	}
}
