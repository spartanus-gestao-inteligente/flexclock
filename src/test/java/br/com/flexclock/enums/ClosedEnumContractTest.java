package br.com.flexclock.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Freezes the fourteen closed enumerators of the architecture contract, section 4.3.
 *
 * <p>The wire tokens are the contract: they are the value stored in SQLite, sent in the REST payloads
 * and published in the OpenAPI document. A change here is a contract change and must be versioned.
 */
class ClosedEnumContractTest {

	private static final int CLOSED_ENUM_COUNT = 14;

	@Test
	void everyClosedEnumHasExactlyTheFrozenWireValues() {
		assertThat(wireValues(SettingValueType.class)).containsExactly("string", "integer", "enum", "url");
		assertThat(wireValues(SettingOwner.class)).containsExactly("user", "agent");
		assertThat(wireValues(AnchorStatus.class)).containsExactly("active", "stale");
		assertThat(wireValues(ProcedureStatus.class)).containsExactly("active", "completed", "aborted");
		assertThat(wireValues(PauseStatus.class)).containsExactly("paused", "resumed", "expired", "aborted");
		assertThat(wireValues(PauseReason.class)).containsExactly("user_cancel", "session_gone", "pause_failed");
		assertThat(wireValues(TimeMode.class)).containsExactly("stopwatch", "project_stopwatch");
		assertThat(wireValues(ReportRecordType.class)).containsExactly("failure", "hallucination", "quality",
				"graph_usage", "token_economy", "source", "download", "upload", "errors");
		assertThat(wireValues(FailureType.class)).containsExactly("hallucination", "wrong_data", "tool_error",
				"timeout", "db_write_failure", "build_failure", "test_failure", "deploy_failure", "logic_error",
				"scope_error", "permission_error", "network_error", "unknown");
		assertThat(wireValues(Severity.class)).containsExactly("low", "medium", "high", "critical");
		assertThat(wireValues(WriteSource.class)).containsExactly("rest", "mcp", "event_service");
		assertThat(wireValues(EvaluationAuthority.class)).containsExactly("SELF", "OWNER");
		assertThat(wireValues(SourceOrigin.class)).containsExactly("domain", "corpus", "graph");
		assertThat(wireValues(EventResult.class)).containsExactly("success", "failure");
	}

	@Test
	void everyWireValueResolvesBackToItsConstant() {
		assertRoundTrip(SettingValueType.class);
		assertRoundTrip(SettingOwner.class);
		assertRoundTrip(AnchorStatus.class);
		assertRoundTrip(ProcedureStatus.class);
		assertRoundTrip(PauseStatus.class);
		assertRoundTrip(PauseReason.class);
		assertRoundTrip(TimeMode.class);
		assertRoundTrip(ReportRecordType.class);
		assertRoundTrip(FailureType.class);
		assertRoundTrip(Severity.class);
		assertRoundTrip(WriteSource.class);
		assertRoundTrip(EvaluationAuthority.class);
		assertRoundTrip(SourceOrigin.class);
		assertRoundTrip(EventResult.class);
	}

	@Test
	void aTokenOutsideTheClosedSetIsRejected() {
		assertThatThrownBy(() -> SettingOwner.fromWireValue("administrator"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("administrator");
		assertThatThrownBy(() -> Severity.fromWireValue("LOW"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void thePackageHoldsExactlyFourteenClosedEnums() throws Exception {
		PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		Resource[] resources = resolver.getResources("classpath*:br/com/flexclock/enums/*.class");
		List<Class<?>> closedEnums = new ArrayList<>();
		for (Resource resource : resources) {
			String filename = resource.getFilename();
			if (filename == null || filename.contains("$") || "WireValue.class".equals(filename)) {
				continue;
			}
			Class<?> candidate = Class.forName("br.com.flexclock.enums." + filename.replace(".class", ""));
			if (candidate.isEnum() && WireValue.class.isAssignableFrom(candidate)) {
				closedEnums.add(candidate);
			}
		}
		assertThat(closedEnums).hasSize(CLOSED_ENUM_COUNT);
	}

	private static <E extends Enum<E> & WireValue> List<String> wireValues(Class<E> enumType) {
		return Arrays.stream(enumType.getEnumConstants()).map(WireValue::wireValue).toList();
	}

	private static <E extends Enum<E> & WireValue> void assertRoundTrip(Class<E> enumType) {
		for (E constant : enumType.getEnumConstants()) {
			assertThat(WireValue.fromWireValue(enumType, constant.wireValue())).isEqualTo(constant);
		}
		List<String> tokens = wireValues(enumType);
		assertThat(tokens).doesNotHaveDuplicates();
	}
}
