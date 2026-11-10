package br.com.flexclock.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.common.InstanceDescriptor;

/**
 * Base of every test that talks to a real SQLite file.
 *
 * <p>The static initializer resets the test database once per test run, before any context starts.
 * The helpers build requests that satisfy the loopback filters, so a test only states what it wants to
 * prove.
 */
public abstract class SqliteIntegrationTest {

	/** Port configured by the test profile for the mock web environment. */
	public static final int TEST_PORT = 18080;

	/** Host header accepted by the loopback allowlist in the test profile. */
	public static final String ALLOWED_HOST = "localhost:" + TEST_PORT;

	static {
		SqliteTestDatabase.reset();
	}

	/**
	 * Builds a request that carries the Host header of the test profile and the instance identifier.
	 *
	 * @param method the HTTP method
	 * @param path the path inside the API
	 * @param instanceId the instance identifier of the live test context
	 * @return the prepared request
	 */
	protected MockHttpServletRequestBuilder apiRequest(HttpMethod method, String path, String instanceId) {
		return request(method, ContractVersion.API_BASE_PATH + path)
				.header(HttpHeaders.HOST, ALLOWED_HOST)
				.header(InstanceDescriptor.INSTANCE_ID_HEADER, instanceId);
	}

	/**
	 * Builds a write request that also carries the Idempotency-Key and the contract version headers.
	 *
	 * @param method the HTTP method
	 * @param path the path inside the API
	 * @param instanceId the instance identifier of the live test context
	 * @param idempotencyKey the idempotency key of the write
	 * @return the prepared request
	 */
	protected MockHttpServletRequestBuilder writeRequest(HttpMethod method, String path, String instanceId,
			String idempotencyKey) {
		return apiRequest(method, path, instanceId)
				.header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
				.header(ContractVersion.VERSION_HEADER, ContractVersion.VERSION);
	}
}
