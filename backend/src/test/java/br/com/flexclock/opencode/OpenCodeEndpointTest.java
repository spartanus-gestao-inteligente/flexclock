package br.com.flexclock.opencode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Proves that the OpenCode endpoint never leaves the loopback.
 */
class OpenCodeEndpointTest {

	@Test
	void theLoopbackAddressesOfTheContractAreAccepted() {
		assertThat(OpenCodeEndpoint.require("http://127.0.0.1:4096")).isEqualTo("http://127.0.0.1:4096");
		assertThat(OpenCodeEndpoint.require("http://localhost:4096")).isEqualTo("http://localhost:4096");
		assertThat(OpenCodeEndpoint.require("http://[::1]:4096")).isEqualTo("http://[::1]:4096");
		assertThat(OpenCodeEndpoint.require("http://127.0.0.1")).isEqualTo("http://127.0.0.1");
		assertThat(OpenCodeEndpoint.require("http://127.0.0.1:4096/")).isEqualTo("http://127.0.0.1:4096");
	}

	@Test
	void aNetworkAddressIsRefused() {
		assertThatThrownBy(() -> OpenCodeEndpoint.require("http://192.168.0.10:4096"))
				.isInstanceOf(OpenCodeEndpoint.InvalidEndpointException.class)
				.hasMessageContaining("loopback");
		assertThatThrownBy(() -> OpenCodeEndpoint.require("http://0.0.0.0:4096"))
				.isInstanceOf(OpenCodeEndpoint.InvalidEndpointException.class);
		assertThatThrownBy(() -> OpenCodeEndpoint.require("http://opencode.example.com:4096"))
				.isInstanceOf(OpenCodeEndpoint.InvalidEndpointException.class);
	}

	@Test
	void anotherSchemeIsRefused() {
		assertThatThrownBy(() -> OpenCodeEndpoint.require("https://127.0.0.1:4096"))
				.isInstanceOf(OpenCodeEndpoint.InvalidEndpointException.class)
				.hasMessageContaining("http scheme");
		assertThatThrownBy(() -> OpenCodeEndpoint.require("127.0.0.1:4096"))
				.isInstanceOf(OpenCodeEndpoint.InvalidEndpointException.class);
	}

	@Test
	void userInformationInTheUrlIsRefused() {
		assertThatThrownBy(() -> OpenCodeEndpoint.require("http://opencode:secret@127.0.0.1:4096"))
				.isInstanceOf(OpenCodeEndpoint.InvalidEndpointException.class)
				.hasMessageContaining("user information");
	}

	@Test
	void aBlankOrInvalidPortIsRefused() {
		assertThatThrownBy(() -> OpenCodeEndpoint.require("")).isInstanceOf(OpenCodeEndpoint.InvalidEndpointException.class);
		assertThatThrownBy(() -> OpenCodeEndpoint.require("http://127.0.0.1:0"))
				.isInstanceOf(OpenCodeEndpoint.InvalidEndpointException.class);
	}

	@Test
	void thePredicateVersionNeverThrows() {
		assertThat(OpenCodeEndpoint.isAllowed("http://127.0.0.1:4096")).isTrue();
		assertThat(OpenCodeEndpoint.isAllowed("http://10.0.0.5:4096")).isFalse();
		assertThat(OpenCodeEndpoint.isAllowed(null)).isFalse();
	}
}
