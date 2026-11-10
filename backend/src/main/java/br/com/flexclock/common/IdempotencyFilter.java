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
 * Enforces the mandatory Idempotency-Key header on every write of the versioned API.
 *
 * <p>POST, PUT, PATCH and DELETE under the API base path must carry the header. A write without it is
 * rejected with {@link ErrorCode#IDEMPOTENCY_KEY_MISSING} before any controller runs. The key is
 * published as a request attribute so the write path can hand it to the
 * {@link IdempotencyStore} and return the original result for a repeated key instead of duplicating
 * the effect (architecture contract, section 5; decision 0057 AB-009; requirement RF-174).
 *
 * <p>Phase 0 delivers the enforcement and the store contract; no write endpoint exists yet.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IdempotencyFilter extends OncePerRequestFilter {

	/** Request attribute that carries the validated Idempotency-Key. */
	public static final String IDEMPOTENCY_KEY_ATTRIBUTE = "flexclock.idempotencyKey";

	/** Contract header name of the idempotency key. */
	public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	private final ErrorResponseWriter errorResponseWriter;

	public IdempotencyFilter(ErrorResponseWriter errorResponseWriter) {
		this.errorResponseWriter = errorResponseWriter;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!request.getRequestURI().startsWith(ContractVersion.API_BASE_PATH)) {
			return true;
		}
		return !WRITE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			errorResponseWriter.write(response, ErrorCode.IDEMPOTENCY_KEY_MISSING, IDEMPOTENCY_KEY_HEADER,
					"Every write must carry the Idempotency-Key header.");
			return;
		}

		request.setAttribute(IDEMPOTENCY_KEY_ATTRIBUTE, idempotencyKey);
		filterChain.doFilter(request, response);
	}
}
