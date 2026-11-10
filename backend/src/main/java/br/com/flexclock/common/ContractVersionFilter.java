package br.com.flexclock.common;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Enforces the contract version handshake on the versioned API.
 *
 * <p>The REST, MCP and event contracts are versioned together, so a caller states the contract it was
 * built against in the {@value ContractVersion#VERSION_HEADER} header. A divergent major version is
 * always rejected, because inside a major version evolution is additive only and a client of another
 * major cannot be served safely. A write without the header is rejected, because a mutating call that
 * cannot state its contract fails closed (architecture contract, section 5; requirement RF-102).
 *
 * <p>The filter runs after the idempotency filter on purpose: a write that carries neither header keeps
 * answering the more specific {@link ErrorCode#IDEMPOTENCY_KEY_MISSING} of the frozen Phase 0 contract,
 * and a write that carries the idempotency key but not the contract version answers
 * {@link ErrorCode#MISSING_VERSION}.
 *
 * <p>Safe methods may omit the header on purpose: the startup handshake reads
 * {@code GET /api/v1/version} and the readiness probe reads {@code GET /api/v1/health} before a caller
 * knows which contract the live process speaks, and the desktop client reads the settings and the
 * reports without a mutating call. A safe method that does present the header is still validated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
public class ContractVersionFilter extends OncePerRequestFilter {

	private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	private final ErrorResponseWriter errorResponseWriter;

	public ContractVersionFilter(ErrorResponseWriter errorResponseWriter) {
		this.errorResponseWriter = errorResponseWriter;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(ContractVersion.API_BASE_PATH);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String presented = request.getHeader(ContractVersion.VERSION_HEADER);
		boolean write = WRITE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT));

		if (presented == null || presented.isBlank()) {
			if (write) {
				errorResponseWriter.write(response, ErrorCode.MISSING_VERSION, ContractVersion.VERSION_HEADER,
						"Every write must carry the contract version it was built against.");
				return;
			}
			filterChain.doFilter(request, response);
			return;
		}

		if (!ContractVersion.isCompatible(presented)) {
			errorResponseWriter.write(response, ErrorCode.VERSION_MISMATCH, ContractVersion.VERSION_HEADER,
					"The contract major version of the request is not supported by the live process.");
			return;
		}

		filterChain.doFilter(request, response);
	}
}
