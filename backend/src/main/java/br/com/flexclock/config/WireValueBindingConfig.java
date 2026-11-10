package br.com.flexclock.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import br.com.flexclock.enums.WireValue;

/**
 * Binds the closed enumerators of the contract by their canonical wire token, everywhere.
 *
 * <p>The token is the single representation used in SQLite, in the JSON payloads and in the OpenAPI
 * document, so a query parameter or a path variable must accept the same spelling as the body. Without
 * this factory Spring would accept only the Java constant name ({@code ABORTED}) and reject the
 * contract token ({@code aborted}), which would split one value into two spellings.
 *
 * <p>Architecture contract, sections 4.3 and 6.1.
 */
@Configuration(proxyBeanMethods = false)
public class WireValueBindingConfig implements WebMvcConfigurer {

	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverterFactory(new StringToWireValueConverterFactory());
	}

	/**
	 * Resolves a canonical token to the constant of any closed enumerator.
	 */
	static final class StringToWireValueConverterFactory implements ConverterFactory<String, WireValue> {

		@Override
		public <T extends WireValue> Converter<String, T> getConverter(Class<T> targetType) {
			return new StringToWireValue<>(targetType);
		}
	}

	private record StringToWireValue<T extends WireValue>(Class<T> targetType) implements Converter<String, T> {

		@Override
		public T convert(String source) {
			if (source == null || source.isBlank()) {
				return null;
			}
			T[] constants = targetType.getEnumConstants();
			if (constants == null) {
				throw new IllegalArgumentException(
						"%s is not a closed enumerator".formatted(targetType.getSimpleName()));
			}
			for (T candidate : constants) {
				if (candidate.wireValue().equals(source.trim())) {
					return candidate;
				}
			}
			throw new IllegalArgumentException(
					"'%s' is not a valid %s value".formatted(source, targetType.getSimpleName()));
		}
	}
}
