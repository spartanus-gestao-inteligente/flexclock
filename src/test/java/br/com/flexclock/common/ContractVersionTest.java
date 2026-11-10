package br.com.flexclock.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Guards the versioning rules of the contract.
 */
class ContractVersionTest {

	@Test
	void theContractVersionIsSemanticAndMatchesItsComponents() {
		assertThat(ContractVersion.VERSION)
				.isEqualTo("%d.%d.%d".formatted(ContractVersion.MAJOR, ContractVersion.MINOR, ContractVersion.PATCH));
		assertThat(ContractVersion.MAJOR).isEqualTo(1);
	}

	@Test
	void theApiBasePathCarriesTheMajorVersion() {
		assertThat(ContractVersion.API_BASE_PATH).isEqualTo("/api/v1");
		assertThat(ContractVersion.API_BASE_PATH).endsWith("/v" + ContractVersion.MAJOR);
	}

	@Test
	void theHandshakeHeaderNameIsFrozen() {
		assertThat(ContractVersion.VERSION_HEADER).isEqualTo("X-FlexClock-Contract-Version");
	}

	@Test
	void theMajorComponentOfAPresentedVersionIsRead() {
		assertThat(ContractVersion.majorOf("1.0.0")).isEqualTo(1);
		assertThat(ContractVersion.majorOf("1")).isEqualTo(1);
		assertThat(ContractVersion.majorOf(" 2.4.1 ")).isEqualTo(2);
		assertThat(ContractVersion.majorOf("x.0.0")).isEqualTo(-1);
		assertThat(ContractVersion.majorOf("")).isEqualTo(-1);
		assertThat(ContractVersion.majorOf(null)).isEqualTo(-1);
		assertThat(ContractVersion.isCompatible(ContractVersion.VERSION)).isTrue();
		assertThat(ContractVersion.isCompatible("2.0.0")).isFalse();
	}

	@Test
	void everyErrorCodeHasAStatusAndAnEnglishMessage() {
		// The table of section 6.4 is the floor: a workstream may add a code as an additive contract
		// change, and the generated OpenAPI document publishes it, but no frozen code may disappear or
		// change meaning inside the major version. The whole list is never pinned, because the wave adds
		// codes as the surfaces grow (decision 0058).
		assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name)).contains("VALIDATION_ERROR",
				"UNKNOWN_FIELD", "VALUE_OUT_OF_RANGE", "INVALID_ENUM_VALUE", "MALFORMED_DATE",
				"IDEMPOTENCY_KEY_MISSING", "IDEMPOTENCY_CONFLICT", "INSTANCE_ID_MISSING", "INSTANCE_ID_MISMATCH",
				"MISSING_VERSION", "VERSION_MISMATCH", "ANCHOR_EXISTS", "ANCHOR_NOT_FOUND", "ANCHOR_STALE",
				"NO_SESSION_ANCHORED", "SESSION_GONE", "INVALID_PAUSE_TRANSITION", "INVALID_PROCEDURE_TRANSITION",
				"NOT_FOUND", "PAYLOAD_TOO_LARGE", "OPENCODE_UNREACHABLE", "PORT_BUSY", "PAUSE_FAILED",
				"DB_WRITE_FAILURE", "INTERNAL_ERROR", "SETTING_NOT_WRITABLE");
		Arrays.stream(ErrorCode.values()).forEach(code -> {
			assertThat(code.status()).isNotNull();
			assertThat(code.message()).isNotBlank();
			assertThat(code.message()).doesNotContain("  ");
		});
	}

	@Test
	void aSettingOwnedByTheUserIsRejectedWithAForbiddenCode() {
		assertThat(ErrorCode.SETTING_NOT_WRITABLE.status().value()).isEqualTo(403);
		assertThat(ErrorCode.SETTING_NOT_WRITABLE.message()).isNotBlank();
	}
}
