package br.com.flexclock.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Validates the per run instance identifier on every versioned API request.
 *
 * <p>The desktop client and the MCP sidecar read the identifier from the instance descriptor and
 * present it in the {@value InstanceDescriptor#INSTANCE_ID_HEADER} header. A request without the
 * header is rejected with {@link ErrorCode#INSTANCE_ID_MISSING}; a request whose value does not match
 * the live process is rejected with {@link ErrorCode#INSTANCE_ID_MISMATCH}.
 *
 * <p>The comparison is constant time. This is process continuity, not authentication (decision
 * 0050; requirement RF-139).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InstanceIdFilter extends OncePerRequestFilter {

	private final InstanceDescriptor instanceDescriptor;
	private final ErrorResponseWriter errorResponseWriter;

	public InstanceIdFilter(InstanceDescriptor instanceDescriptor, ErrorResponseWriter errorResponseWriter) {
		this.instanceDescriptor = instanceDescriptor;
		this.errorResponseWriter = errorResponseWriter;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(ContractVersion.API_BASE_PATH);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String presented = request.getHeader(InstanceDescriptor.INSTANCE_ID_HEADER);
		if (presented == null || presented.isBlank()) {
			errorResponseWriter.write(response, ErrorCode.INSTANCE_ID_MISSING, InstanceDescriptor.INSTANCE_ID_HEADER,
					"Every request must carry the instance identifier published by the instance descriptor.");
			return;
		}

		if (!matches(presented)) {
			errorResponseWriter.write(response, ErrorCode.INSTANCE_ID_MISMATCH, InstanceDescriptor.INSTANCE_ID_HEADER,
					"The instance identifier does not belong to the live process.");
			return;
		}

		filterChain.doFilter(request, response);
	}

	private boolean matches(String presented) {
		byte[] expected = instanceDescriptor.instanceId().getBytes(StandardCharsets.UTF_8);
		byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, actual);
	}
}
