package br.com.flexclock.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.enums.SettingOwner;
import br.com.flexclock.enums.SettingValueType;

/**
 * Guards the closed settings catalogue: the seeded keys, their types, their owners and their domains.
 *
 * <p>A key that is not declared here cannot be written, and a declared default that its own domain
 * rejects would make a fresh database unusable, so both are proven by test (requirement RF-008).
 */
class SettingKeyTest {

	@Test
	void theCatalogueHoldsTheKeysOfTheArchitectureContract() {
		List<String> keys = Arrays.stream(SettingKey.values()).map(SettingKey::wireKey).toList();

		assertThat(keys).containsExactly("language", "timezone", "orchestration.model", "report.default.period",
				"time.default-mode", "time.default.target-seconds", "opencode.endpoint", "region.clock-source");
	}

	@Test
	void everyDeclaredDefaultSatisfiesItsOwnDomain() {
		for (SettingKey key : SettingKey.values()) {
			assertThat(key.normalize(key.defaultValue())).as("default of %s", key.wireKey()).isNotBlank();
		}
	}

	@Test
	void anUnknownKeyIsRejectedWithNotFound() {
		assertThatThrownBy(() -> SettingKey.fromWireKey("software.language"))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	@Test
	void aKeyIsResolvedIgnoringCaseAndSurroundingSpaces() {
		assertThat(SettingKey.fromWireKey(" Language ")).isEqualTo(SettingKey.LANGUAGE);
		assertThat(SettingKey.fromWireKey("OPENCODE.ENDPOINT")).isEqualTo(SettingKey.OPENCODE_ENDPOINT);
	}

	@Test
	void theLanguageTagIsNormalizedToItsCanonicalSpelling() {
		assertThat(SettingKey.LANGUAGE.normalize("pt-br")).isEqualTo("pt-BR");
		assertThat(SettingKey.LANGUAGE.normalize("ZH-hans")).isEqualTo("zh-Hans");
	}

	@Test
	void aLanguageOutsideTheCommercialListIsRejected() {
		assertThatThrownBy(() -> SettingKey.LANGUAGE.normalize("tlh"))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.INVALID_ENUM_VALUE);
	}

	@Test
	void aTimezoneMustBeAnIanaZone() {
		assertThat(SettingKey.TIMEZONE.normalize("America/Sao_Paulo")).isEqualTo("America/Sao_Paulo");
		assertThatThrownBy(() -> SettingKey.TIMEZONE.normalize("Mars/Olympus"))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.VALUE_OUT_OF_RANGE);
	}

	@Test
	void theOrchestrationModelIsAClosedSet() {
		assertThat(SettingKey.ORCHESTRATION_MODEL.normalize("Pipeline")).isEqualTo("pipeline");
		assertThatThrownBy(() -> SettingKey.ORCHESTRATION_MODEL.normalize("federation"))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.INVALID_ENUM_VALUE);
	}

	@Test
	void theDefaultTargetIsAnIntegerInsideItsRange() {
		assertThat(SettingKey.TIME_DEFAULT_TARGET_SECONDS.normalize("1500")).isEqualTo("1500");
		assertThatThrownBy(() -> SettingKey.TIME_DEFAULT_TARGET_SECONDS.normalize("0"))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.VALUE_OUT_OF_RANGE);
		assertThatThrownBy(() -> SettingKey.TIME_DEFAULT_TARGET_SECONDS.normalize("86401"))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.VALUE_OUT_OF_RANGE);
		assertThatThrownBy(() -> SettingKey.TIME_DEFAULT_TARGET_SECONDS.normalize("one hour"))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.VALUE_OUT_OF_RANGE);
	}

	@Test
	void theOpenCodeEndpointMustStayInsideTheLoopback() {
		assertThat(SettingKey.OPENCODE_ENDPOINT.normalize("http://localhost:4096/")).isEqualTo("http://localhost:4096");
		assertThatThrownBy(() -> SettingKey.OPENCODE_ENDPOINT.normalize("http://192.168.0.10:4096"))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.VALUE_OUT_OF_RANGE);
	}

	@Test
	void theUserOwnsTheOpenCodeEndpointAndNothingElse() {
		List<String> userOwned = Arrays.stream(SettingKey.values())
				.filter(key -> key.owner() == SettingOwner.USER)
				.map(SettingKey::wireKey)
				.toList();

		assertThat(userOwned).containsExactly("opencode.endpoint");
	}

	@Test
	void theAgentCannotWriteAUserOwnedKey() {
		assertThat(SettingKey.OPENCODE_ENDPOINT.isWritableBy(SettingActor.AGENT)).isFalse();
		assertThat(SettingKey.OPENCODE_ENDPOINT.isWritableBy(SettingActor.USER)).isTrue();
		assertThat(SettingKey.LANGUAGE.isWritableBy(SettingActor.AGENT)).isTrue();
	}

	@Test
	void everyKeyDeclaresATypeAndItsRangeMatchesTheColumn() {
		for (SettingKey key : SettingKey.values()) {
			assertThat(key.valueType()).isNotNull();
			if (key.valueType() == SettingValueType.INTEGER) {
				assertThat(key.minValue()).isNotNull();
				assertThat(key.maxValue()).isNotNull();
			}
		}
	}

	@Test
	void aBlankValueIsRejected() {
		assertThatThrownBy(() -> SettingKey.LANGUAGE.normalize("  "))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	@Test
	void theTwoClosedPrincipalsShareTheSameWireValues() {
		assertThat(Arrays.stream(SettingOwner.values()).map(SettingOwner::wireValue).toList())
				.containsExactlyElementsOf(Arrays.stream(SettingActor.values()).map(SettingActor::wireValue).toList());
	}
}
