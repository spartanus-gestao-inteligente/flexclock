package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.EventResult;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every EventResult attribute.
 */
@Converter(autoApply = true)
public class EventResultConverter extends WireValueConverter<EventResult> {

	public EventResultConverter() {
		super(EventResult.class);
	}
}
