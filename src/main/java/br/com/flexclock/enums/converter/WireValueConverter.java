package br.com.flexclock.enums.converter;

import br.com.flexclock.enums.WireValue;
import jakarta.persistence.AttributeConverter;

/**
 * Persists a closed enumerator as its canonical wire token instead of the Java constant name, so a
 * SQLite column, a REST payload and the OpenAPI document always carry the same spelling.
 *
 * @param <E> the enumerator type
 */
public abstract class WireValueConverter<E extends Enum<E> & WireValue> implements AttributeConverter<E, String> {

	private final Class<E> enumType;

	protected WireValueConverter(Class<E> enumType) {
		this.enumType = enumType;
	}

	@Override
	public String convertToDatabaseColumn(E attribute) {
		return attribute == null ? null : attribute.wireValue();
	}

	@Override
	public E convertToEntityAttribute(String dbData) {
		return dbData == null ? null : WireValue.fromWireValue(enumType, dbData);
	}
}
