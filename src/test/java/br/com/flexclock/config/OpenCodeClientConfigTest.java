package br.com.flexclock.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Proves that the OpenCode endpoint stays inside the loopback.
 */
class OpenCodeClientConfigTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	@Test
	void aLoopbackTargetIsAccepted() {
		assertThat(OpenCodeClientConfig.requireLoopback("http://127.0.0.1:4096"))
				.isEqualTo("http://127.0.0.1:4096");
		assertThat(OpenCodeClientConfig.requireLoopback("http://localhost:4096"))
				.isEqualTo("http://localhost:4096");
		assertThat(OpenCodeClientConfig.requireLoopback("http://[::1]:4096"))
				.isEqualTo("http://[::1]:4096");
		assertThat(OpenCodeClientConfig.requireLoopback("http://[::1]")).isEqualTo("http://[::1]");
		assertThat(OpenCodeClientConfig.requireLoopback("http://127.0.0.1")).isEqualTo("http://127.0.0.1");
		assertThat(OpenCodeClientConfig.requireLoopback("http://127.0.0.1:4096/api")).isNotNull();
	}

	@Test
	void anExternalTargetIsRefused() {
		assertThatThrownBy(() -> OpenCodeClientConfig.requireLoopback("http://192.168.0.10:4096"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("loopback");
		assertThatThrownBy(() -> OpenCodeClientConfig.requireLoopback("https://opencode.example.com"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> OpenCodeClientConfig.requireLoopback(""))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theConfigurationBuildsAClientForALoopbackTarget() {
		OpenCodeClientConfig configuration = new OpenCodeClientConfig("http://127.0.0.1:4096", TIMEOUT, TIMEOUT);

		assertThat(configuration.baseUrl()).isEqualTo("http://127.0.0.1:4096");
		assertThat(configuration.openCodeRestClient()).isNotNull();
	}

	@Test
	void theConfigurationRefusesToStartWithAnExternalTarget() {
		assertThatThrownBy(() -> new OpenCodeClientConfig("http://10.0.0.5:4096", TIMEOUT, TIMEOUT))
				.isInstanceOf(IllegalStateException.class);
	}
}
