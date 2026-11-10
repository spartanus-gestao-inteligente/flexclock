package br.com.flexclock.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.opencode.OpenCodeHealth;
import br.com.flexclock.opencode.OpenCodePauseResult;
import br.com.flexclock.opencode.OpenCodePort;
import br.com.flexclock.opencode.OpenCodeSession;

/**
 * In memory stand in for {@link OpenCodePort}.
 *
 * <p>It replaces the HTTP client in the integration tests: it holds the sessions the fake server
 * knows, it can report a session as missing and it can simulate an unreachable server, which is
 * exactly the three cases the anchor lifecycle must distinguish (decision 0046). The pause operations
 * are not exercised by these tests and answer with a refusal, so an unexpected call is visible.
 */
public class StubOpenCodePort implements OpenCodePort {

	private final Map<String, OpenCodeSession> sessions = new LinkedHashMap<>();

	private volatile boolean available = true;

	/**
	 * Registers the sessions the fake server knows, replacing whatever was registered before.
	 *
	 * @param knownSessions the sessions of the fake server
	 * @return this stub, for chaining
	 */
	public synchronized StubOpenCodePort withSessions(OpenCodeSession... knownSessions) {
		sessions.clear();
		for (OpenCodeSession session : knownSessions) {
			sessions.put(session.sessionId(), session);
		}
		return this;
	}

	/**
	 * Adds one session to the fake server.
	 *
	 * @param session the session to add
	 * @return this stub, for chaining
	 */
	public synchronized StubOpenCodePort add(OpenCodeSession session) {
		sessions.put(session.sessionId(), session);
		return this;
	}

	/**
	 * Removes one session, as if the OpenCode server had deleted it.
	 *
	 * @param sessionId the session identifier
	 * @return this stub, for chaining
	 */
	public synchronized StubOpenCodePort remove(String sessionId) {
		sessions.remove(sessionId);
		return this;
	}

	/**
	 * Sets whether the fake server answers or is unreachable.
	 *
	 * @param available true to answer, false to fail with the transport error
	 */
	public void setAvailable(boolean available) {
		this.available = available;
	}

	@Override
	public OpenCodeHealth health() {
		requireAvailable();
		return new OpenCodeHealth(true, "stub");
	}

	@Override
	public synchronized List<OpenCodeSession> listMainSessions() {
		requireAvailable();
		return List.copyOf(sessions.values());
	}

	@Override
	public synchronized OpenCodeSession getSession(String sessionId) {
		requireAvailable();
		OpenCodeSession session = sessions.get(sessionId);
		if (session == null) {
			throw new ApiException(ErrorCode.SESSION_GONE, "sessionId",
					"The OpenCode session no longer exists.");
		}
		return session;
	}

	@Override
	public void abort(String sessionId) {
		requireAvailable();
	}

	@Override
	public void sendDirectionTurn(String sessionId, String directionTurnText) {
		requireAvailable();
	}

	@Override
	public OpenCodePauseResult pause(String sessionId, String directionTurnText) {
		requireAvailable();
		throw new UnsupportedOperationException("The stub does not exercise the pause transport.");
	}

	private void requireAvailable() {
		if (!available) {
			throw new ApiException(ErrorCode.OPENCODE_UNREACHABLE, "opencode",
					"The OpenCode server at the configured endpoint is unreachable.");
		}
	}
}
