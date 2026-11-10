package br.com.flexclock.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import br.com.flexclock.support.SqliteIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves that the generated OpenAPI document publishes the time and pause surface.
 *
 * <p>The document is generated from the code and is the single source of truth for paths, payloads and
 * error codes, so a missing path or a missing additive code is a contract drift the build must catch
 * (architecture contract, sections 5 and 6; requirements RF-100, RF-101, RF-103).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TimeAndPauseContractSurfaceTest extends SqliteIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void theDocumentPublishesTheTimeAndPauseOperations() throws Exception {
		JsonNode document = openApiDocument();
		JsonNode paths = document.get("paths");

		assertThat(paths.has("/api/v1/work-sessions")).isTrue();
		assertThat(paths.has("/api/v1/work-sessions/{workSessionId}/stop")).isTrue();
		assertThat(paths.has("/api/v1/work-sessions/current")).isTrue();
		assertThat(paths.has("/api/v1/work-sessions/{workSessionId}")).isTrue();
		assertThat(paths.has("/api/v1/anchors/{anchorId}/pause")).isTrue();
		assertThat(paths.has("/api/v1/pauses/{pauseId}/resume")).isTrue();
		assertThat(paths.has("/api/v1/pauses/{pauseId}/abort")).isTrue();
		assertThat(paths.has("/api/v1/pauses/current")).isTrue();
		assertThat(paths.has("/api/v1/pauses/notifications")).isTrue();
		assertThat(paths.has("/api/v1/procedures/{procedureId}/pauses")).isTrue();
	}

	@Test
	void theDocumentPublishesTheAdditiveErrorCodes() throws Exception {
		JsonNode document = openApiDocument();
		JsonNode code = document.get("components").get("schemas").get("ErrorResponse").get("properties").get("code");
		assertThat(code.get("enum").toString()).contains("WORK_SESSION_ALREADY_RUNNING")
				.contains("WORK_SESSION_NOT_RUNNING");
	}

	private JsonNode openApiDocument() throws Exception {
		MvcResult result = mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.HOST, ALLOWED_HOST))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
