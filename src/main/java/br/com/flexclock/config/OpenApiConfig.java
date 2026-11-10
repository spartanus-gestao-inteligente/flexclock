package br.com.flexclock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import br.com.flexclock.common.ContractVersion;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

/**
 * OpenAPI document generated from the code.
 *
 * <p>The document is the single source of truth for paths, payloads and error codes, and it is
 * versioned with the MCP and event contracts. It is published in development only: the release
 * profile disables both the document and the Swagger UI (requirement RF-156; architecture contract,
 * section 5).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	/**
	 * Describes the versioned contract of the backend.
	 *
	 * @return the OpenAPI document
	 */
	@Bean
	OpenAPI flexClockOpenApi() {
		return new OpenAPI().info(new Info()
				.title("Flex Clock API")
				.version(ContractVersion.VERSION)
				.description("""
						Local, single user, offline first API of Flex Clock. Loopback only: every request \
						carries the per run instance identifier and every write carries the Idempotency-Key \
						header. The effective address and port are published in the instance descriptor.""")
				.license(new License().name("Spartanus Attribution License 1.0")));
	}
}
