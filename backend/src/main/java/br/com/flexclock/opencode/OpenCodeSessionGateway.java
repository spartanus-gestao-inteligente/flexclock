package br.com.flexclock.opencode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;

/**
 * Single entry point the backend uses to reach the OpenCode sessions.
 *
 * <p>It adapts the platform port {@link OpenCodePort} to the vocabulary of the session and anchor
 * layer: the port publishes contract error codes on purpose, while the anchor lifecycle has to tell a
 * missing session from an unreachable server, because a missing session marks the anchor stale and an
 * unreachable server keeps every anchor untouched (decision 0046). There is exactly one OpenCode
 * client bean in the application, the platform {@code OpenCodeClient}; this class adds the translation
 * and never a second transport.
 *
 * <p>When the port is absent from the classpath the product still starts and every call fails closed
 * with {@link OpenCodeUnavailableException}, which the callers publish as
 * {@link ErrorCode#OPENCODE_UNREACHABLE}: a build without the client reports the server as unreachable
 * instead of pretending that there are no sessions (fail closed rule of architecture contract section
 * 5).
 *
 * <p>The provider is resolved on every call, so the client is used as soon as it is registered and a
 * test can substitute it without touching the services.
 */
@Component
public class OpenCodeSessionGateway {

	private static final Logger log = LoggerFactory.getLogger(OpenCodeSessionGateway.class);

	private static final String ADAPTER_MISSING = "The OpenCode client is not available on this build.";

	private static final String UNREACHABLE = "The OpenCode server at the configured endpoint is unreachable.";

	private final ObjectProvider<OpenCodePort> portProvider;

	private final AtomicBoolean adapterMissingLogged = new AtomicBoolean();

	public OpenCodeSessionGateway(ObjectProvider<OpenCodePort> portProvider) {
		this.portProvider = portProvider;
	}

	/**
	 * Lists the sessions of the OpenCode server that the product may anchor.
	 *
	 * @return the sessions, possibly empty, never null
	 * @throws OpenCodeUnavailableException when the port is absent or the server cannot answer
	 */
	public List<OpenCodeSession> listSessions() {
		try {
			return require().listMainSessions();
		}
		catch (ApiException exception) {
			throw translate(exception);
		}
	}

	/**
	 * Resolves one session by its OpenCode identifier.
	 *
	 * @param sessionId the OpenCode session identifier
	 * @return the session, or an empty optional when the server reports it as missing
	 * @throws OpenCodeUnavailableException when the port is absent or the server cannot answer
	 */
	public Optional<OpenCodeSession> findSession(String sessionId) {
		try {
			return Optional.ofNullable(require().getSession(sessionId));
		}
		catch (ApiException exception) {
			if (exception.errorCode() == ErrorCode.SESSION_GONE) {
				return Optional.empty();
			}
			throw translate(exception);
		}
	}

	private OpenCodePort require() {
		OpenCodePort port = portProvider.getIfAvailable();
		if (port == null) {
			if (adapterMissingLogged.compareAndSet(false, true)) {
				log.warn(ADAPTER_MISSING);
			}
			throw new OpenCodeUnavailableException(ADAPTER_MISSING);
		}
		return port;
	}

	/**
	 * Maps a transport failure of the port to the one exception the anchor layer handles.
	 *
	 * <p>Only the connectivity failures are translated. A pause failure or a validation failure keeps
	 * its own code, because the caller has to publish it unchanged. A missing session never reaches
	 * this method: {@link #findSession(String)} answers with an empty optional instead.
	 *
	 * @param exception the failure raised by the port
	 * @return the exception the caller should propagate
	 */
	private RuntimeException translate(ApiException exception) {
		ErrorCode code = exception.errorCode();
		if (code == ErrorCode.OPENCODE_UNREACHABLE || code == ErrorCode.PORT_BUSY) {
			return new OpenCodeUnavailableException(
					code == ErrorCode.PORT_BUSY ? "The OpenCode port is held by another process." : UNREACHABLE,
					exception);
		}
		return exception;
	}
}
