package br.com.flexclock.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.InstanceDescriptor;
import br.com.flexclock.dto.HealthResponse;
import br.com.flexclock.dto.MetadataResponse;
import br.com.flexclock.dto.VersionResponse;
import br.com.flexclock.security.InstanceDescriptorWriter;
import br.com.flexclock.support.SqliteIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the lifecycle surface of the backend over real HTTP: health, metadata, the contract version
 * handshake, the instance descriptor file and the generated OpenAPI document.
 *
 * <p>The requests use the JDK HTTP client on purpose: the loopback filters, the instance identifier and
 * the Host allowlist must be exercised over a real socket, not through a mock request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PlatformEndpointTest extends SqliteIntegrationTest {

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@Autowired
	private InstanceDescriptor instanceDescriptor;

	@Autowired
	private InstanceDescriptorWriter instanceDescriptorWriter;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@LocalServerPort
	private int port;

	@Test
	void healthIsUpAndCarriesTheRuntimeMetadata() throws Exception {
		HttpResponse<String> response = get("/api/v1/health", true);

		assertThat(response.statusCode()).isEqualTo(200);
		HealthResponse health = objectMapper.readValue(response.body(), HealthResponse.class);
		assertThat(health.status()).isEqualTo(HealthResponse.STATUS_UP);
		assertThat(health.schemaVersion()).isEqualTo(highestAppliedMigration());
		assertThat(health.locale()).isEqualTo("en");
		assertThat(health.opencodeUrl()).isEqualTo("http://127.0.0.1:4096");
	}

	@Test
	void metadataPublishesTheContractAndTheLiveProcess() throws Exception {
		HttpResponse<String> response = get("/api/v1/metadata", true);

		assertThat(response.statusCode()).isEqualTo(200);
		MetadataResponse metadata = objectMapper.readValue(response.body(), MetadataResponse.class);
		assertThat(metadata.contractVersion()).isEqualTo(ContractVersion.VERSION);
		assertThat(metadata.locale()).isEqualTo("en");
		assertThat(metadata.timezone()).isEqualTo("Etc/UTC");
		assertThat(metadata.regionClockSource()).isEqualTo("system");
		assertThat(metadata.opencodeUrl()).isEqualTo("http://127.0.0.1:4096");
		assertThat(metadata.apiVersion()).isEqualTo(ContractVersion.VERSION);
		assertThat(metadata.instanceId()).isEqualTo(instanceDescriptor.instanceId());
		assertThat(metadata.pid()).isEqualTo(ProcessHandle.current().pid());
		assertThat(metadata.port()).isEqualTo(port);
		assertThat(metadata.address()).isEqualTo("127.0.0.1");
	}

	@Test
	void theVersionEndpointAnswersTheHandshake() throws Exception {
		HttpResponse<String> response = get("/api/v1/version", true);

		assertThat(response.statusCode()).isEqualTo(200);
		VersionResponse version = objectMapper.readValue(response.body(), VersionResponse.class);
		assertThat(version.contractVersion()).isEqualTo("1.0.0");
		assertThat(version.contractMajor()).isEqualTo(ContractVersion.MAJOR);
	}

	@Test
	void aRequestWithoutTheInstanceIdentifierIsRejectedOverHttp() throws Exception {
		HttpResponse<String> response = get("/api/v1/version", false);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(objectMapper.readTree(response.body()).get("code").asString()).isEqualTo("INSTANCE_ID_MISSING");
	}

	@Test
	void theInstanceDescriptorIsWrittenWithOwnerOnlyPermissions() throws IOException {
		Path descriptorPath = instanceDescriptorWriter.descriptorPath();
		assertThat(descriptorPath).exists();

		JsonNode descriptor = objectMapper.readTree(Files.readString(descriptorPath));
		assertThat(descriptor.get("instanceId").asString()).isEqualTo(instanceDescriptor.instanceId());
		assertThat(descriptor.get("pid").asLong()).isEqualTo(ProcessHandle.current().pid());
		assertThat(descriptor.get("address").asString()).isEqualTo("127.0.0.1");
		assertThat(descriptor.get("port").asInt()).isEqualTo(port);
		assertThat(descriptor.get("startedAt").asString()).isNotBlank();
		assertThat(descriptor.get("contractVersion").asString()).isEqualTo(ContractVersion.VERSION);
		assertThat(descriptor.get("schemaVersion").asString()).isEqualTo(highestAppliedMigration());

		if (supportsPosix(descriptorPath)) {
			assertThat(Files.getPosixFilePermissions(descriptorPath))
					.isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		}
	}

	@Test
	void theOpenApiDocumentIsGeneratedFromTheCode() throws Exception {
		HttpResponse<String> response = get("/v3/api-docs", false);

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode document = objectMapper.readTree(response.body());
		assertThat(document.get("info").get("title").asString()).isEqualTo("Flex Clock API");
		assertThat(document.get("info").get("version").asString()).isEqualTo(ContractVersion.VERSION);
		assertThat(document.get("paths").has("/api/v1/health")).isTrue();
		assertThat(document.get("paths").has("/api/v1/version")).isTrue();
		assertThat(document.get("paths").has("/api/v1/settings")).isTrue();
		assertThat(document.get("paths").has("/api/v1/settings/{key}")).isTrue();
		assertThat(document.get("paths").has("/api/v1/language")).isTrue();
		assertThat(document.get("components").get("schemas").has("ErrorResponse")).isTrue();
	}

	private String highestAppliedMigration() {
		Integer highest = jdbcTemplate.queryForObject(
				"select max(cast(version as integer)) from flyway_schema_history where success = 1", Integer.class);
		return highest == null ? null : highest.toString();
	}

	private HttpResponse<String> get(String path, boolean withInstanceId) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(10))
				.GET();
		if (withInstanceId) {
			builder.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId());
		}
		return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private boolean supportsPosix(Path path) {
		return path.getFileSystem().supportedFileAttributeViews().contains("posix");
	}
}
