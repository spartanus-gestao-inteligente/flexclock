package br.com.flexclock.enums;

/**
 * Contract for the closed enumerators of Flex Clock.
 *
 * <p>Every enumerator declares a canonical wire token. The token is the single representation used
 * in the SQLite column, in the REST JSON payloads and in the OpenAPI document, so the database, the
 * backend and the clients never disagree about a value. Java constant names stay idiomatic
 * (upper snake case) while the wire token keeps the exact spelling frozen by the architecture
 * contract, section 4.3.
 */
public interface WireValue {

	/**
	 * Returns the canonical token of the constant, exactly as frozen by the contract.
	 *
	 * @return the canonical token, never null
	 */
	String wireValue();

	/**
	 * Resolves a canonical token to its constant.
	 *
	 * @param enumType the enumerator type to resolve
	 * @param wireValue the canonical token
	 * @param <E> the enumerator type
	 * @return the matching constant
	 * @throws IllegalArgumentException when the token is not part of the closed set
	 */
	static <E extends Enum<E> & WireValue> E fromWireValue(Class<E> enumType, String wireValue) {
		for (E candidate : enumType.getEnumConstants()) {
			if (candidate.wireValue().equals(wireValue)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException(
				"'%s' is not a valid %s value".formatted(wireValue, enumType.getSimpleName()));
	}
}
