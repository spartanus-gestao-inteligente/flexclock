package br.com.flexclock.opencode;

import java.util.List;

import org.springframework.stereotype.Service;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.dto.SessionSummary;

/**
 * Session selector of the Clock window.
 *
 * <p>Lists only the main sessions of the OpenCode server: a session with a parent is a subagent and
 * never enters the selector, so it can never be anchored (decision 0041; requirements RF-012 and
 * RN-007). The most recent session is never auto-selected here; choosing is a user decision.
 */
@Service
public class OpenCodeSessionService {

	private final OpenCodeSessionGateway gateway;

	public OpenCodeSessionService(OpenCodeSessionGateway gateway) {
		this.gateway = gateway;
	}

	/**
	 * Lists the main sessions available to anchor.
	 *
	 * @return the main sessions, possibly empty, never null
	 * @throws ApiException with {@link ErrorCode#OPENCODE_UNREACHABLE} when the server cannot answer
	 */
	public List<SessionSummary> listMainSessions() {
		try {
			return gateway.listSessions().stream()
					.filter(OpenCodeSession::isMain)
					.map(session -> new SessionSummary(session.sessionId(), session.title(), session.directory()))
					.toList();
		}
		catch (OpenCodeUnavailableException exception) {
			throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, "opencode",
					"The OpenCode server at the configured endpoint is unreachable.");
		}
	}
}
