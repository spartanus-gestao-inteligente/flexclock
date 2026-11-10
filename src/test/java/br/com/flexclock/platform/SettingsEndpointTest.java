package br.com.flexclock.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.common.InstanceDescriptor;
import br.com.flexclock.dto.HealthResponse;
import br.com.flexclock.dto.LanguageResponse;
import br.com.flexclock.dto.MetadataResponse;
import br.com.flexclock.dto.SettingResponse;
import br.com.flexclock.dto.SettingsResponse;
import br.com.flexclock.enums.SettingOwner;
import br.com.flexclock.enums.SettingValueType;
import br.com.flexclock.support.SqliteIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the settings surface over real HTTP against a real SQLite file.
 *
 * <p>The requests use the JDK client on purpose: the loopback filters, the instance identifier, the
 * contract version and the idempotency guard must be exercised over a real socket, not through a mock
 * request. The test restores every key it writes, so the shared context stays in the seeded state for
 * the other classes (requirement RF-008).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SettingsEndpointTest extends SqliteIntegrationTest {

	private static final String SETTINGS = "/api/v1/settings";
	private static final String LANGUAGE = "/api/v1/language";

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@Autowired
	private InstanceDescriptor instanceDescriptor;

	@Autowired
	private SettingsService settingsService;

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	@AfterEach
	void restoreTheSeededValues() {
		settingsService.update("language", "en", SettingActor.USER, UUID.randomUUID().toString());
		settingsService.update("orchestration.model", "central_coordinator", SettingActor.USER,
				UUID.randomUUID().toString());
		settingsService.update("time.default.target-seconds", "3600", SettingActor.USER,
				UUID.randomUUID().toString());
		settingsService.update("timezone", "Etc/UTC", SettingActor.USER, UUID.randomUUID().toString());
	}

	@Test
	void theWholeCatalogueIsServedWithItsTypeAndOwner() throws Exception {
		HttpResponse<String> response = read(SETTINGS);

		assertThat(response.statusCode()).isEqualTo(200);
		SettingsResponse settings = objectMapper.readValue(response.body(), SettingsResponse.class);
		assertThat(settings.settings()).extracting(SettingResponse::key).containsExactly("language", "timezone",
				"orchestration.model", "report.default.period", "time.default-mode", "time.default.target-seconds",
				"opencode.endpoint", "region.clock-source");

		SettingResponse language = settings.settings().getFirst();
		assertThat(language.value()).isEqualTo("en");
		assertThat(language.valueType()).isEqualTo(SettingValueType.ENUM);
		assertThat(language.owner()).isEqualTo(SettingOwner.AGENT);
		assertThat(language.updatedBy()).isNotBlank();
		assertThat(language.updatedAt()).isNotNull();

		SettingResponse untouched = settings.settings().stream()
				.filter(setting -> "region.clock-source".equals(setting.key())).findFirst().orElseThrow();
		assertThat(untouched.value()).isEqualTo("system");
		assertThat(untouched.updatedBy()).isEqualTo("installer");

		SettingResponse endpoint = settings.settings().stream()
				.filter(setting -> "opencode.endpoint".equals(setting.key())).findFirst().orElseThrow();
		assertThat(endpoint.owner()).isEqualTo(SettingOwner.USER);
		assertThat(endpoint.valueType()).isEqualTo(SettingValueType.URL);
		assertThat(endpoint.value()).isEqualTo("http://127.0.0.1:4096");
	}

	@Test
	void theAgentChangesAKeyItOwnsAndTheMetadataFollows() throws Exception {
		HttpResponse<String> response = write("PUT", SETTINGS + "/timezone", "{\"value\":\"America/Sao_Paulo\"}",
				writeHeaders(newIdempotencyKey(), null, ContractVersion.VERSION));

		assertThat(response.statusCode()).isEqualTo(200);
		SettingResponse setting = objectMapper.readValue(response.body(), SettingResponse.class);
		assertThat(setting.value()).isEqualTo("America/Sao_Paulo");
		assertThat(setting.updatedBy()).isEqualTo("agent");

		MetadataResponse metadata = objectMapper.readValue(read("/api/v1/metadata").body(), MetadataResponse.class);
		assertThat(metadata.timezone()).isEqualTo("America/Sao_Paulo");
		assertThat(metadata.locale()).isEqualTo("en");
	}

	@Test
	void aUserOwnedKeyIsRefusedForTheAgent() throws Exception {
		HttpResponse<String> response = write("PUT", SETTINGS + "/opencode.endpoint",
				"{\"value\":\"http://127.0.0.1:5000\"}", writeHeaders(newIdempotencyKey(), null, ContractVersion.VERSION));

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(errorCode(response)).isEqualTo("SETTING_NOT_WRITABLE");
	}

	@Test
	void theUserChangesTheOpenCodeEndpointButOnlyInsideTheLoopback() throws Exception {
		HttpResponse<String> external = write("PUT", SETTINGS + "/opencode.endpoint",
				"{\"value\":\"http://192.168.0.10:4096\"}",
				writeHeaders(newIdempotencyKey(), "user", ContractVersion.VERSION));

		assertThat(external.statusCode()).isEqualTo(400);
		assertThat(errorCode(external)).isEqualTo("VALUE_OUT_OF_RANGE");

		HttpResponse<String> loopback = write("PUT", SETTINGS + "/opencode.endpoint",
				"{\"value\":\"http://127.0.0.1:5000\"}",
				writeHeaders(newIdempotencyKey(), "user", ContractVersion.VERSION));

		assertThat(loopback.statusCode()).isEqualTo(200);
		assertThat(settingsService.effective(SettingKey.OPENCODE_ENDPOINT)).isEqualTo("http://127.0.0.1:5000");

		write("PUT", SETTINGS + "/opencode.endpoint", "{\"value\":\"http://127.0.0.1:4096\"}",
				writeHeaders(newIdempotencyKey(), "user", ContractVersion.VERSION));
	}

	@Test
	void anUnknownKeyIsRejectedWithNotFound() throws Exception {
		HttpResponse<String> response = write("PUT", SETTINGS + "/software.language", "{\"value\":\"en\"}",
				writeHeaders(newIdempotencyKey(), null, ContractVersion.VERSION));

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(errorCode(response)).isEqualTo("NOT_FOUND");
	}

	@Test
	void aValueOutsideTheClosedSetIsRejected() throws Exception {
		HttpResponse<String> response = write("PUT", SETTINGS + "/orchestration.model",
				"{\"value\":\"federation\"}", writeHeaders(newIdempotencyKey(), null, ContractVersion.VERSION));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo("INVALID_ENUM_VALUE");
	}

	@Test
	void aRepeatedKeyWithTheSamePayloadReplaysTheFirstResult() throws Exception {
		String idempotencyKey = newIdempotencyKey();
		String body = "{\"value\":\"1500\"}";

		HttpResponse<String> first = write("PUT", SETTINGS + "/time.default.target-seconds", body,
				writeHeaders(idempotencyKey, null, ContractVersion.VERSION));
		HttpResponse<String> second = write("PUT", SETTINGS + "/time.default.target-seconds", body,
				writeHeaders(idempotencyKey, null, ContractVersion.VERSION));

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(second.body()).isEqualTo(first.body());
	}

	@Test
	void aRepeatedKeyWithADifferentPayloadIsRejected() throws Exception {
		String idempotencyKey = newIdempotencyKey();

		HttpResponse<String> first = write("PUT", SETTINGS + "/time.default.target-seconds", "{\"value\":\"1200\"}",
				writeHeaders(idempotencyKey, null, ContractVersion.VERSION));
		HttpResponse<String> second = write("PUT", SETTINGS + "/time.default.target-seconds", "{\"value\":\"1800\"}",
				writeHeaders(idempotencyKey, null, ContractVersion.VERSION));

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(second.statusCode()).isEqualTo(409);
		assertThat(errorCode(second)).isEqualTo("IDEMPOTENCY_CONFLICT");
	}

	@Test
	void aWriteWithoutTheIdempotencyKeyIsRejected() throws Exception {
		HttpResponse<String> response = write("PUT", SETTINGS + "/time.default.target-seconds", "{\"value\":\"900\"}",
				writeHeaders(null, null, ContractVersion.VERSION));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo("IDEMPOTENCY_KEY_MISSING");
	}

	@Test
	void aWriteWithoutTheContractVersionIsRejected() throws Exception {
		HttpResponse<String> response = write("PUT", SETTINGS + "/time.default.target-seconds", "{\"value\":\"900\"}",
				writeHeaders(newIdempotencyKey(), null, null));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo("MISSING_VERSION");
	}

	@Test
	void aWriteWithADivergentMajorVersionIsRejected() throws Exception {
		HttpResponse<String> response = write("PUT", SETTINGS + "/time.default.target-seconds", "{\"value\":\"900\"}",
				writeHeaders(newIdempotencyKey(), null, "2.0.0"));

		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(errorCode(response)).isEqualTo("VERSION_MISMATCH");
	}

	@Test
	void aReadOfTheHandshakeWorksWithoutTheVersionHeader() throws Exception {
		assertThat(read("/api/v1/version").statusCode()).isEqualTo(200);
		assertThat(read("/api/v1/health").statusCode()).isEqualTo(200);
		assertThat(read(SETTINGS).statusCode()).isEqualTo(200);
	}

	@Test
	void theLanguageEndpointServesTheThirtyCommercialLanguages() throws Exception {
		HttpResponse<String> response = read(LANGUAGE);

		assertThat(response.statusCode()).isEqualTo(200);
		LanguageResponse language = objectMapper.readValue(response.body(), LanguageResponse.class);
		assertThat(language.language()).isEqualTo("en");
		assertThat(language.rightToLeft()).isFalse();
		assertThat(language.supported()).hasSize(30);
		assertThat(language.supported()).extracting(supported -> supported.tag()).contains("en", "pt-BR", "ar", "he");
		assertThat(language.supported().stream().filter(supported -> "ar".equals(supported.tag())).findFirst()
				.orElseThrow().rightToLeft()).isTrue();
	}

	@Test
	void theAgentChangesTheDefaultLanguageThroughTheDedicatedEndpoint() throws Exception {
		HttpResponse<String> response = write("PUT", LANGUAGE, "{\"language\":\"pt-br\"}",
				writeHeaders(newIdempotencyKey(), null, ContractVersion.VERSION));

		assertThat(response.statusCode()).isEqualTo(200);
		LanguageResponse language = objectMapper.readValue(response.body(), LanguageResponse.class);
		assertThat(language.language()).isEqualTo("pt-BR");
		assertThat(language.rightToLeft()).isFalse();

		HealthResponse health = objectMapper.readValue(read("/api/v1/health").body(), HealthResponse.class);
		assertThat(health.locale()).isEqualTo("pt-BR");
	}

	@Test
	void anUnsupportedLanguageIsRejected() throws Exception {
		HttpResponse<String> response = write("PUT", LANGUAGE, "{\"language\":\"tlh\"}",
				writeHeaders(newIdempotencyKey(), null, ContractVersion.VERSION));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo("INVALID_ENUM_VALUE");
	}

	@Test
	void anActorOutsideTheClosedSetIsRejected() throws Exception {
		HttpResponse<String> response = write("PUT", SETTINGS + "/time.default.target-seconds", "{\"value\":\"900\"}",
				writeHeaders(newIdempotencyKey(), "administrator", ContractVersion.VERSION));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo("INVALID_ENUM_VALUE");
	}

	private HttpResponse<String> read(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.timeout(Duration.ofSeconds(10))
				.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId())
				.header(ContractVersion.VERSION_HEADER, ContractVersion.VERSION)
				.GET()
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> write(String method, String path, String body, Map<String, String> headers)
			throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(builder::header);
		return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private Map<String, String> writeHeaders(String idempotencyKey, String actor, String contractVersion) {
		Map<String, String> headers = new HashMap<>();
		headers.put(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId());
		if (idempotencyKey != null) {
			headers.put(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
		}
		if (actor != null) {
			headers.put(SettingActor.ACTOR_HEADER, actor);
		}
		if (contractVersion != null) {
			headers.put(ContractVersion.VERSION_HEADER, contractVersion);
		}
		return headers;
	}

	private String errorCode(HttpResponse<String> response) {
		JsonNode node = objectMapper.readTree(response.body());
		return node.get("code").asString();
	}

	private String newIdempotencyKey() {
		return UUID.randomUUID().toString();
	}

	private String baseUrl() {
		return "http://127.0.0.1:" + port;
	}

}
