package br.com.flexclock.opencode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Validation of the local OpenCode endpoint.
 *
 * <p>The product never opens a connection outside the loopback, so the endpoint must use the
 * {@code http} scheme, must name the IPv4 loopback address, {@code localhost} or the IPv6 loopback
 * address, and must not carry user information. A host name, a network address, a wildcard address,
 * another scheme or a credential in the URL is refused (decision 0034 and 0043; requirement RF-150).
 *
 * <p>The rule is applied twice: when the endpoint is written into the settings table, and again on
 * every use, so a database edited outside the product can never redirect the client (requirement
 * RF-150).
 */
public final class OpenCodeEndpoint {

	/** Default endpoint of the local OpenCode server. */
	public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:4096";

	private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1");

	private OpenCodeEndpoint() {
		// Constants only.
	}

	/**
	 * Validates an endpoint and returns it without a trailing slash.
	 *
	 * @param candidate the configured endpoint
	 * @return the accepted endpoint
	 * @throws InvalidEndpointException when the endpoint is not a loopback http target
	 */
	public static String require(String candidate) {
		if (candidate == null || candidate.isBlank()) {
			throw new InvalidEndpointException("The OpenCode endpoint must be configured.");
		}
		String trimmed = candidate.trim();
		URI uri;
		try {
			uri = new URI(trimmed);
		}
		catch (URISyntaxException exception) {
			throw new InvalidEndpointException("The OpenCode endpoint is not a valid URL: " + trimmed + ".");
		}
		String scheme = uri.getScheme();
		if (scheme == null || !"http".equalsIgnoreCase(scheme)) {
			throw new InvalidEndpointException("The OpenCode endpoint must use the http scheme, received: " + trimmed
					+ ".");
		}
		if (uri.getUserInfo() != null) {
			throw new InvalidEndpointException(
					"The OpenCode endpoint must not carry user information: " + trimmed + ".");
		}
		String host = uri.getHost();
		if (host == null) {
			throw new InvalidEndpointException("The OpenCode endpoint must name a loopback host: " + trimmed + ".");
		}
		String bareHost = host.startsWith("[") && host.endsWith("]")
				? host.substring(1, host.length() - 1)
				: host;
		bareHost = bareHost.toLowerCase(Locale.ROOT);
		if (!LOOPBACK_HOSTS.contains(bareHost)) {
			throw new InvalidEndpointException(
					"The OpenCode endpoint must target loopback only, received: " + trimmed + ".");
		}
		int port = uri.getPort();
		if (port == 0 || port < -1 || port > 65535) {
			throw new InvalidEndpointException("The OpenCode endpoint has an invalid port: " + trimmed + ".");
		}
		String path = uri.getPath() == null ? "" : uri.getPath();
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		String publishedHost = bareHost.contains(":") ? "[" + bareHost + "]" : bareHost;
		return scheme.toLowerCase(Locale.ROOT) + "://" + publishedHost + (port > 0 ? ":" + port : "") + path;
	}

	/**
	 * Reports whether an endpoint is usable, without throwing.
	 *
	 * @param candidate the configured endpoint
	 * @return true when {@link #require(String)} accepts it
	 */
	public static boolean isAllowed(String candidate) {
		try {
			require(candidate);
			return true;
		}
		catch (InvalidEndpointException exception) {
			return false;
		}
	}

	/**
	 * Rejection of an endpoint that leaves the loopback or breaks the scheme rule.
	 */
	public static class InvalidEndpointException extends IllegalArgumentException {

		private static final long serialVersionUID = 1L;

		/**
		 * Creates the rejection with the English reason.
		 *
		 * @param message the English reason
		 */
		public InvalidEndpointException(String message) {
			super(message);
		}
	}
}
