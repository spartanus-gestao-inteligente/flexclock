package br.com.flexclock.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import br.com.flexclock.opencode.BoundedRetry;
import br.com.flexclock.opencode.OpenCodeEndpoint;

/**
 * Configuration of the client to the local OpenCode server.
 *
 * <p>The base url defaults to {@value br.com.flexclock.opencode.OpenCodeEndpoint#DEFAULT_ENDPOINT} and
 * may be changed at installation. Only a loopback http target without user information is accepted: an
 * external host, a wildcard address, a hostname, another scheme or a credential in the URL is refused
 * at startup, and the same rule is applied again when the endpoint is written into the settings table
 * and again on every use (decision 0057 AB-023; requirements RF-150 and RNF-019).
 *
 * <p>The client bean carries no base url, because the effective endpoint is read from the settings
 * table on every call. The two timeouts of the decision are fixed here: two seconds to connect and ten
 * seconds to read, so a call can never hang the caller (requirement RF-164). The retry policy is fixed
 * by decision 0046: three attempts with an exponential backoff at one, two and four seconds.
 */
@Configuration(proxyBeanMethods = false)
public class OpenCodeClientConfig {

	private final String baseUrl;
	private final Duration connectTimeout;
	private final Duration readTimeout;

	public OpenCodeClientConfig(@Value("${flexclock.opencode.base-url:http://127.0.0.1:4096}") String baseUrl,
			@Value("${flexclock.opencode.connect-timeout:2s}") Duration connectTimeout,
			@Value("${flexclock.opencode.read-timeout:10s}") Duration readTimeout) {
		this.baseUrl = requireLoopback(baseUrl);
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
	}

	/**
	 * Validates that the configured OpenCode endpoint is a loopback http target.
	 *
	 * @param candidate the configured base url
	 * @return the accepted base url
	 * @throws IllegalStateException when the target is not loopback
	 */
	static String requireLoopback(String candidate) {
		try {
			return OpenCodeEndpoint.require(candidate);
		}
		catch (OpenCodeEndpoint.InvalidEndpointException exception) {
			throw new IllegalStateException(exception.getMessage(), exception);
		}
	}

	/**
	 * Builds the client used to reach the local OpenCode server.
	 *
	 * <p>It has no base url on purpose: the endpoint is read from the settings table on every call, so a
	 * change of the setting takes effect without a restart (requirement RF-009 applied to the client).
	 *
	 * @return the configured REST client
	 */
	@Bean
	RestClient openCodeRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeout);
		requestFactory.setReadTimeout(readTimeout);
		return RestClient.builder().requestFactory(requestFactory).build();
	}

	/**
	 * Retry policy of the OpenCode calls.
	 *
	 * <p>The defaults are the ones of decision 0046. A test profile may shorten the backoff, which is why
	 * the values are configuration properties and not constants of the client.
	 *
	 * @param maxAttempts number of attempts, including the first one
	 * @param initialBackoff the wait before the second attempt
	 * @param multiplier the factor applied after each attempt
	 * @return the retry policy
	 */
	@Bean
	BoundedRetry openCodeRetry(@Value("${flexclock.opencode.retry.max-attempts:3}") int maxAttempts,
			@Value("${flexclock.opencode.retry.initial-backoff:1s}") Duration initialBackoff,
			@Value("${flexclock.opencode.retry.multiplier:2}") double multiplier) {
		return new BoundedRetry(maxAttempts, initialBackoff, multiplier, new ThreadSleeper());
	}

	/**
	 * Returns the effective OpenCode base url of the configuration, published by the metadata endpoint
	 * when the settings table cannot answer.
	 *
	 * @return the base url
	 */
	public String baseUrl() {
		return baseUrl;
	}

	/**
	 * Real waiter used between two attempts in production.
	 */
	static final class ThreadSleeper implements BoundedRetry.Sleeper {

		@Override
		public void sleep(Duration duration) {
			try {
				Thread.sleep(duration);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new BoundedRetry.TransientOpenCodeException(
						"The wait before the next OpenCode attempt was interrupted.", exception);
			}
		}
	}
}
