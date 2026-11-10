package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.ReportRecordType;
import jakarta.persistence.Converter;

/**
 * Applies the canonical wire token mapping to every ReportRecordType attribute.
 */
@Converter(autoApply = true)
public class ReportRecordTypeConverter extends WireValueConverter<ReportRecordType> {

	public ReportRecordTypeConverter() {
		super(ReportRecordType.class);
	}
}
