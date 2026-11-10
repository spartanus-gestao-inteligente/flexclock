package br.com.flexclock.common;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Enforces the loopback trust boundary on the Host and Origin headers.
 *
 * <p>The product has no user authentication in the MVP, so the compensating control is that every
 * process listens on loopback only and that a request must name the live process. This filter runs
 * before any controller and rejects:
 *
 * <ul>
 * <li>a Host header outside the loopback allowlist (127.0.0.1, localhost and the IPv6 loopback
 * address), always compared with the effective listen port;</li>
 * <li>any request that carries an Origin header. The desktop client and the MCP sidecar are not
 * browsers and never send Origin, so the release default is to reject every request that does.</li>
 * </ul>
 *
 * <p>The allowlist is built from the live instance descriptor, so a request cannot reach a different
 * port on the same machine. Architecture contract, section 2; decisions 0050; requirements RF-136,
 * RF-137 and RNF-020.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HostOriginFilter extends OncePerRequestFilter {

	private static final String LOOPBACK_HOST = "127.0.0.1";
	private static final String LOOPBACK_NAME = "localhost";
	private static final String LOOPBACK_IPV6 = "[::1]";

	private final InstanceDescriptor instanceDescriptor;
	private final ErrorResponseWriter errorResponseWriter;

	public HostOriginFilter(InstanceDescriptor instanceDescriptor, ErrorResponseWriter errorResponseWriter) {
		this.instanceDescriptor = instanceDescriptor;
		this.errorResponseWriter = errorResponseWriter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (origin != null) {
			errorResponseWriter.write(response, ErrorCode.VALIDATION_ERROR, "Origin",
					"The product does not accept a request that carries an Origin header.");
			return;
		}

		String host = request.getHeader(HttpHeaders.HOST);
		if (!isAllowedHost(host)) {
			errorResponseWriter.write(response, ErrorCode.VALIDATION_ERROR, "Host",
					"The Host header is not in the loopback allowlist for the live process port.");
			return;
		}

		filterChain.doFilter(request, response);
	}

	private boolean isAllowedHost(String host) {
		if (host == null || host.isBlank()) {
			return false;
		}
		return allowedHosts().contains(host.toLowerCase(Locale.ROOT));
	}

	private Set<String> allowedHosts() {
		int port = instanceDescriptor.port();
		return Set.of(LOOPBACK_HOST + ":" + port, LOOPBACK_NAME + ":" + port, LOOPBACK_IPV6 + ":" + port);
	}
}
