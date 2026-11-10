package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.EvaluationAuthority;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every EvaluationAuthority attribute.
 */
@Converter(autoApply = true)
public class EvaluationAuthorityConverter extends WireValueConverter<EvaluationAuthority> {

	public EvaluationAuthorityConverter() {
		super(EvaluationAuthority.class);
	}
}
