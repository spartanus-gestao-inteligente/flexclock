package br.com.flexclock.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.flexclock.enums.TimeMode;
import br.com.flexclock.support.SqliteIntegrationTest;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the single error envelope, the stable error codes and the three common filters.
 *
 * <p>Every rejection must be the same JSON shape: a stable English code, an English message, the field
 * or resource and an optional detail (architecture contract, sections 6.3 and 6.4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ErrorEnvelopeTest.TestSupportController.class)
class ErrorEnvelopeTest extends SqliteIntegrationTest {

	private static final String VALID_BODY = """
			{"name":"clock","mode":"stopwatch","occurredAt":"2026-11-10T12:00:00Z"}""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InstanceDescriptor instanceDescriptor;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void anUnknownPathAnswersTheNotFoundEnvelope() throws Exception {
		MvcResult result = mockMvc
				.perform(apiRequest(HttpMethod.GET, "/does-not-exist", instanceDescriptor.instanceId()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andReturn();

		ErrorResponse error = body(result);
		assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
		assertThat(error.message()).isEqualTo(ErrorCode.NOT_FOUND.message());
	}

	@Test
	void aRequestWithoutTheInstanceIdentifierIsRejected() throws Exception {
		MvcResult result = mockMvc
				.perform(get("/api/v1/version").header(HttpHeaders.HOST, ALLOWED_HOST))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INSTANCE_ID_MISSING"))
				.andExpect(jsonPath("$.field").value(InstanceDescriptor.INSTANCE_ID_HEADER))
				.andReturn();

		assertThat(body(result).code()).isEqualTo(ErrorCode.INSTANCE_ID_MISSING);
	}

	@Test
	void anInstanceIdentifierOfAnotherProcessIsRejected() throws Exception {
		MvcResult result = mockMvc
				.perform(get("/api/v1/version").header(HttpHeaders.HOST, ALLOWED_HOST)
						.header(InstanceDescriptor.INSTANCE_ID_HEADER, "not-the-live-process"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INSTANCE_ID_MISMATCH"))
				.andReturn();

		assertThat(body(result).code()).isEqualTo(ErrorCode.INSTANCE_ID_MISMATCH);
	}

	@Test
	void aHostOutsideTheLoopbackAllowlistIsRejected() throws Exception {
		MvcResult result = mockMvc
				.perform(get("/api/v1/version").header(HttpHeaders.HOST, "example.com:18080")
						.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.field").value("Host"))
				.andReturn();

		assertThat(body(result).field()).isEqualTo("Host");
	}

	@Test
	void aHostWithoutTheLivePortIsRejected() throws Exception {
		mockMvc.perform(get("/api/v1/version").header(HttpHeaders.HOST, "localhost:9999")
				.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceDescriptor.instanceId()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.field").value("Host"));
	}

	@Test
	void aRequestWithAnOriginHeaderIsRejected() throws Exception {
		mockMvc.perform(apiRequest(HttpMethod.GET, "/version", instanceDescriptor.instanceId())
				.header(HttpHeaders.ORIGIN, "http://localhost:3000"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.field").value("Origin"));
	}

	@Test
	void aWriteWithoutTheIdempotencyKeyIsRejected() throws Exception {
		mockMvc.perform(apiRequest(HttpMethod.POST, "/test-support/echo", instanceDescriptor.instanceId())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"))
				.andExpect(jsonPath("$.field").value(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER));
	}

	@Test
	void aWriteWithTheIdempotencyKeyReachesTheController() throws Exception {
		mockMvc.perform(writeRequest(HttpMethod.POST, "/test-support/echo", instanceDescriptor.instanceId(), "key-1")
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mode").value("stopwatch"));
	}

	@Test
	void anUnknownFieldIsReportedAsUnknownField() throws Exception {
		String body = """
				{"name":"clock","mode":"stopwatch","occurredAt":"2026-11-10T12:00:00Z","surprise":true}""";

		MvcResult result = mockMvc
				.perform(writeRequest(HttpMethod.POST, "/test-support/echo", instanceDescriptor.instanceId(), "key-2")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
				.andExpect(jsonPath("$.field").value("surprise"))
				.andReturn();

		assertThat(body(result).code()).isEqualTo(ErrorCode.UNKNOWN_FIELD);
	}

	@Test
	void anEnumOutsideTheClosedSetIsReportedAsInvalidEnumValue() throws Exception {
		String body = """
				{"name":"clock","mode":"countdown","occurredAt":"2026-11-10T12:00:00Z"}""";

		MvcResult result = mockMvc
				.perform(writeRequest(HttpMethod.POST, "/test-support/echo", instanceDescriptor.instanceId(), "key-3")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"))
				.andReturn();

		assertThat(body(result).code()).isEqualTo(ErrorCode.INVALID_ENUM_VALUE);
	}

	@Test
	void aMalformedDateIsReportedAsMalformedDate() throws Exception {
		String body = """
				{"name":"clock","mode":"stopwatch","occurredAt":"10/11/2026"}""";

		MvcResult result = mockMvc
				.perform(writeRequest(HttpMethod.POST, "/test-support/echo", instanceDescriptor.instanceId(), "key-4")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_DATE"))
				.andReturn();

		assertThat(body(result).code()).isEqualTo(ErrorCode.MALFORMED_DATE);
	}

	@Test
	void aProductRuleFailureKeepsItsOwnCodeAndField() throws Exception {
		MvcResult result = mockMvc
				.perform(apiRequest(HttpMethod.GET, "/test-support/api-error", instanceDescriptor.instanceId()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANCHOR_NOT_FOUND"))
				.andExpect(jsonPath("$.field").value("anchorId"))
				.andReturn();

		ErrorResponse error = body(result);
		assertThat(error.code()).isEqualTo(ErrorCode.ANCHOR_NOT_FOUND);
		assertThat(error.detail()).isEqualTo("There is no anchor with id 42.");
	}

	@Test
	void anUnexpectedFailureHidesTheCauseAndPublishesACorrelationId() throws Exception {
		MvcResult result = mockMvc
				.perform(apiRequest(HttpMethod.GET, "/test-support/failure", instanceDescriptor.instanceId()))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").value("An unexpected error occurred."))
				.andReturn();

		assertThat(result.getResponse().getHeader(GlobalExceptionHandler.CORRELATION_ID_HEADER)).isNotBlank();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("boom");
	}

	private ErrorResponse body(MvcResult result) throws Exception {
		return objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
	}

	/**
	 * Controller used only by this test to exercise binding failures and product rule failures.
	 */
	@RestController
	@RequestMapping(ContractVersion.API_BASE_PATH + "/test-support")
	static class TestSupportController {

		@PostMapping("/echo")
		EchoRequest echo(@RequestBody EchoRequest request) {
			return request;
		}

		@GetMapping("/api-error")
		void apiError() {
			throw new ApiException(ErrorCode.ANCHOR_NOT_FOUND, "anchorId", "There is no anchor with id 42.");
		}

		@GetMapping("/failure")
		void failure() {
			throw new IllegalStateException("boom");
		}
	}

	/**
	 * Body used by the test controller.
	 *
	 * @param name a free text value
	 * @param mode a closed enum value
	 * @param occurredAt an ISO 8601 instant
	 */
	record EchoRequest(String name, TimeMode mode, Instant occurredAt) {
	}
}
