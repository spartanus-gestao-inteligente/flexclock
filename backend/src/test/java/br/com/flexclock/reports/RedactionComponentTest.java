package br.com.flexclock.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.flexclock.reports.RedactionComponent.RedactedFields;
import br.com.flexclock.reports.RedactionComponent.RedactionResult;

/**
 * Proves that a secret is removed before a record is persisted and that the original value is never
 * kept.
 *
 * <p>Requirements RF-052, RF-093; decision 0033.
 */
class RedactionComponentTest {

	private final RedactionComponent component = new RedactionComponent();

	@Test
	void aBearerTokenIsRemovedAndTheOriginalIsNotReturned() {
		RedactionResult result = component.redact("request with Authorization: Bearer abc123def456ghi789");

		assertThat(result.redacted()).isTrue();
		assertThat(result.value()).doesNotContain("abc123def456ghi789");
		assertThat(result.value()).contains(RedactionComponent.REDACTED);
		assertThat(result.rules()).contains("authorization_header");
	}

	@Test
	void aUrlQuerySecretIsRemovedAndTheKeyIsKept() {
		RedactionResult result = component.redact("https://api.example.com/data?page=2&token=s3cr3t-value");

		assertThat(result.redacted()).isTrue();
		assertThat(result.value()).doesNotContain("s3cr3t-value");
		assertThat(result.value()).contains("token=[REDACTED]");
		assertThat(result.value()).contains("page=2");
	}

	@Test
	void credentialsInTheUrlAuthorityAreRemoved() {
		RedactionResult result = component.redact("jdbc:postgresql://admin:hunter2@db.local:5432/app");

		assertThat(result.redacted()).isTrue();
		assertThat(result.value()).doesNotContain("hunter2");
		assertThat(result.value()).doesNotContain("admin:hunter2");
	}

	@Test
	void aKeyValuePairInFreeTextIsRemoved() {
		RedactionResult result = component.redact("password=hunter2 and api_key=ABCDEF123456");

		assertThat(result.redacted()).isTrue();
		assertThat(result.value()).doesNotContain("hunter2");
		assertThat(result.value()).doesNotContain("ABCDEF123456");
	}

	@Test
	void aPrivateKeyBlockIsReplacedAsAWhole() {
		String pem = """
				-----BEGIN RSA PRIVATE KEY-----
				MIIEowIBAAKCAQEA1234567890
				-----END RSA PRIVATE KEY-----
				""";

		RedactionResult result = component.redact(pem);

		assertThat(result.redacted()).isTrue();
		assertThat(result.value().trim()).isEqualTo("[REDACTED PRIVATE KEY]");
		assertThat(result.value()).doesNotContain("MIIEowIBAAKCAQEA1234567890");
	}

	@Test
	void knownTokenShapesAreRemoved() {
		assertThat(component.redact("ghp_0123456789abcdefghijABCDEFGHIJ").value())
				.doesNotContain("ghp_0123456789abcdefghijABCDEFGHIJ");
		assertThat(component.redact("AKIAIOSFODNN7EXAMPLE").redacted()).isTrue();
		assertThat(component.redact("xoxb-123456789012-abcdefghijkl").redacted()).isTrue();
		assertThat(component.redact("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1g")
				.redacted()).isTrue();
	}

	@Test
	void anOrdinaryValueIsLeftAlone() {
		RedactionResult result = component.redact("https://docs.spring.io/spring-boot/reference/index.html");

		assertThat(result.redacted()).isFalse();
		assertThat(result.value()).isEqualTo("https://docs.spring.io/spring-boot/reference/index.html");
		assertThat(result.rules()).isEmpty();
	}

	@Test
	void aNullValueStaysNullAndIsNotFlagged() {
		RedactionResult result = component.redact(null);

		assertThat(result.value()).isNull();
		assertThat(result.redacted()).isFalse();
	}

	@Test
	void aFieldMapIsRedactedByFieldAndReportsTheAggregateFlag() {
		RedactedFields fields = component.redactAll(Map.of(
				"source", "https://x.example/?api_key=ABCDEF",
				"purpose", "consult the API reference"));

		assertThat(fields.redacted()).isTrue();
		assertThat(fields.values().get("source")).doesNotContain("ABCDEF");
		assertThat(fields.values().get("purpose")).isEqualTo("consult the API reference");
		assertThat(fields.rules()).contains("query_parameter");
	}
}
