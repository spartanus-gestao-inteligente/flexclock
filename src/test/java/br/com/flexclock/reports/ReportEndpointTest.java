package br.com.flexclock.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.common.InstanceDescriptor;
import br.com.flexclock.support.ReportRequestBuilder;
import br.com.flexclock.support.SqliteIntegrationTest;
import br.com.flexclock.enums.EventResult;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.SourceOrigin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the report surface over real HTTP: the append, the aggregation read, the consolidated dashboard
 * and the PDF export, with the loopback filters, the instance identifier and the mandatory
 * Idempotency-Key in the path.
 *
 * <p>The requests use the JDK HTTP client on purpose: the filters and the instance identifier must be
 * exercised over a real socket, and the PDF must arrive as a real file with its content type and its
 * suggested name.
 *
 * <p>Architecture contract, section 6.1; decisions 0030, 0052; requirements RF-045, RF-074.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReportEndpointTest extends SqliteIntegrationTest {

	private static final String JSON = "application/json";

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@Autowired
	private InstanceDescriptor instanceDescriptor;

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	@Test
	void aSourceRecordIsAppendedAndThenReadBackThroughTheFamily() throws Exception {
		String body = objectMapper.writeValueAsString(ReportRequestBuilder.of(ReportRecordType.SOURCE)
				.occurredAt(Instant.parse("2026-04-01T10:00:00Z"))
				.agentId("agent-1")
				.sessionId("ses_http")
				.source("https://docs.spring.io/spring-boot/reference/index.html")
				.origin(SourceOrigin.DOMAIN)
				.taskContext("report contract")
				.purpose("confirm the endpoint shape")
				.result(EventResult.SUCCESS)
				.cited(true)
				.build());

		HttpResponse<String> created = post("/reports/sources", body, "http-source-1");
		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode createdBody = objectMapper.readTree(created.body());
		assertThat(createdBody.get("family").asString()).isEqualTo("sources");
		assertThat(createdBody.get("recordId").asLong()).isPositive();

		HttpResponse<String> read = get("/reports/sources?period=custom&start=2026-04-01&end=2026-04-02"
				+ "&timezone=UTC&locale=en");
		assertThat(read.statusCode()).isEqualTo(200);
		JsonNode report = objectMapper.readTree(read.body());
		assertThat(report.get("family").asString()).isEqualTo("sources");
		assertThat(report.get("empty").asBoolean()).isFalse();
		assertThat(report.get("aggregates").get("accesses").asInt()).isEqualTo(1);
		assertThat(report.get("aggregates").get("rows")).hasSize(1);
		assertThat(report.get("chartSeries")).hasSize(1);
	}

	@Test
	void aWriteWithoutTheIdempotencyKeyIsRefusedByTheFilter() throws Exception {
		String body = objectMapper.writeValueAsString(ReportRequestBuilder.of(ReportRecordType.SOURCE)
				.source("https://x.example")
				.origin(SourceOrigin.DOMAIN)
				.taskContext("context")
				.purpose("purpose")
				.result(EventResult.SUCCESS)
				.build());

		HttpRequest request = HttpRequest.newBuilder(uri("/reports/sources"))
				.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId())
				.header("Content-Type", JSON)
				.timeout(Duration.ofSeconds(10))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(objectMapper.readTree(response.body()).get("code").asString())
				.isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISSING.name());
	}

	@Test
	void theTimeFamilyRefusesAnAppendedRecord() throws Exception {
		String body = objectMapper.writeValueAsString(ReportRequestBuilder.of(ReportRecordType.FAILURE)
				.failureType(br.com.flexclock.enums.FailureType.TOOL_ERROR)
				.severity(br.com.flexclock.enums.Severity.LOW)
				.build());

		HttpResponse<String> response = post("/reports/time", body, "http-time-1");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(objectMapper.readTree(response.body()).get("code").asString())
				.isEqualTo(ErrorCode.VALIDATION_ERROR.name());
	}

	@Test
	void anUnknownFamilyIsRefusedWithTheEnumerationCode() throws Exception {
		HttpResponse<String> response = get("/reports/quarter?period=day&timezone=UTC");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(objectMapper.readTree(response.body()).get("code").asString())
				.isEqualTo(ErrorCode.INVALID_ENUM_VALUE.name());
	}

	@Test
	void anUnknownPeriodIsRefusedWithTheEnumerationCode() throws Exception {
		HttpResponse<String> response = get("/reports/time?period=fortnight&timezone=UTC");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(objectMapper.readTree(response.body()).get("code").asString())
				.isEqualTo(ErrorCode.INVALID_ENUM_VALUE.name());
	}

	@Test
	void theDashboardReturnsTheNineFamiliesOfTheSameWindow() throws Exception {
		HttpResponse<String> response = get("/reports/dashboard?period=custom&start=2026-04-02&end=2026-04-03"
				+ "&timezone=UTC&locale=en");

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode dashboard = objectMapper.readTree(response.body());
		assertThat(dashboard.get("period").asString()).isEqualTo("custom");
		assertThat(dashboard.get("reports")).hasSize(9);
		assertThat(dashboard.get("reports").get(0).get("family").asString()).isEqualTo("time");
		assertThat(dashboard.get("reports").get(8).get("family").asString()).isEqualTo("uploads");
		assertThat(dashboard.get("headline").get("uploads").asInt()).isZero();
		assertThat(dashboard.get("notMeasurable").isArray()).isTrue();
	}

	@Test
	void thePdfOfAFamilyArrivesAsAFileWithItsSuggestedName() throws Exception {
		HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder(uri("/reports/time/pdf"
				+ "?period=custom&start=2026-04-03&end=2026-04-04&timezone=UTC&locale=en"))
				.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId())
				.header(ContractVersion.VERSION_HEADER, ContractVersion.VERSION)
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build(), HttpResponse.BodyHandlers.ofByteArray());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
		assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
				.contains("flex-clock-time-2026-04-03-2026-04-04.pdf");
		assertThat(new String(response.body(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
	}

	@Test
	void theOpenApiDocumentPublishesTheWholeReportSurface() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode document = objectMapper.readTree(response.body());
		assertThat(document.get("paths").has("/api/v1/reports/{family}")).isTrue();
		assertThat(document.get("paths").has("/api/v1/reports/{family}/pdf")).isTrue();
		assertThat(document.get("paths").has("/api/v1/reports/dashboard")).isTrue();
		assertThat(document.get("components").get("schemas").has("ReportRecordRequest")).isTrue();
		assertThat(document.get("components").get("schemas").has("ReportResponse")).isTrue();
		assertThat(document.get("components").get("schemas").has("DashboardResponse")).isTrue();
	}

	@Test
	void theHealthSurfaceStillReportsTheAppendOnlyGuardPassed() throws Exception {
		HttpResponse<String> response = get("/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(response.body()).get("status").asString()).isEqualTo("UP");
	}

	@Test
	void theReportFamiliesAreListedInTheErrorDetailOfAnInvalidFamily() throws Exception {
		HttpResponse<String> response = get("/reports/unknown");

		assertThat(response.statusCode()).isEqualTo(400);
		Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
		assertThat(String.valueOf(body.get("detail"))).contains("time").contains("failures").contains("uploads");
	}

	private HttpResponse<String> post(String path, String body, String idempotencyKey) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri(path))
				.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId())
				.header(ContractVersion.VERSION_HEADER, ContractVersion.VERSION)
				.header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
				.header("Content-Type", JSON)
				.timeout(Duration.ofSeconds(10))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri(path))
				.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId())
				.header(ContractVersion.VERSION_HEADER, ContractVersion.VERSION)
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + ContractVersion.API_BASE_PATH + path);
	}
}
