package br.com.flexclock.opencode;

import java.util.List;

/**
 * Port of the local OpenCode server, owned by the backend.
 *
 * <p>The Spring Boot backend is the only component that talks to the OpenCode API. The Flutter client,
 * the MCP sidecar, the event service and the agent never call the server, and no component accepts a
 * session identifier from a client as authority: the identifier is always resolved from the anchor
 * before an operation runs (decision 0043; requirements RF-012, RF-153).
 *
 * <p>Guarantees of every implementation:
 *
 * <ul>
 * <li>loopback only, http only and without user information, validated when the endpoint is written
 * and again on every use, so the client can never be redirected outside the machine (requirement
 * RF-150);</li>
 * <li>explicit connect and read timeouts on every call, so no operation can hang the caller
 * (requirement RF-164);</li>
 * <li>bounded retry of three attempts with an exponential backoff at one, two and four seconds for
 * transient failures only (decision 0046);</li>
 * <li>no arbitrary process control: the client never starts, signals or kills the OpenCode process,
 * never runs a shell and never reads a file, it only speaks the documented HTTP API (requirement
 * RF-142);</li>
 * <li>no credentials, cookies or authorization header by default (requirement RF-151).</li>
 * </ul>
 *
 * <p>Failures are raised as {@link br.com.flexclock.common.ApiException} with a stable code:
 * {@link br.com.flexclock.common.ErrorCode#OPENCODE_UNREACHABLE} when the server refused the
 * connection or did not answer, {@link br.com.flexclock.common.ErrorCode#PORT_BUSY} when another
 * process answered on the port or the server reported itself unhealthy,
 * {@link br.com.flexclock.common.ErrorCode#SESSION_GONE} when the session no longer exists and
 * {@link br.com.flexclock.common.ErrorCode#PAUSE_FAILED} when a pause operation failed after the
 * retries.
 */
public interface OpenCodePort {

	/**
	 * Probes the server health.
	 *
	 * <p>The probe is a single attempt: its purpose is detection before a pause, and repeating it
	 * against a server that is down would only delay the message without adding information (decision
	 * 0046).
	 *
	 * @return the health payload the server published
	 * @throws br.com.flexclock.common.ApiException with {@code OPENCODE_UNREACHABLE} or
	 * {@code PORT_BUSY}
	 */
	OpenCodeHealth health();

	/**
	 * Lists the main sessions, which are the sessions whose parent is null or absent.
	 *
	 * <p>A subagent session is never listed and never anchored (decision 0043, requirement RF-012). The
	 * result is bounded, so a large session list can never produce an unbounded response (requirement
	 * RF-164).
	 *
	 * @return the main sessions, possibly empty, never null
	 */
	List<OpenCodeSession> listMainSessions();

	/**
	 * Reads one session, which is how the anchor obtains the title and the directory of a selection.
	 *
	 * @param sessionId the session identifier resolved from the anchor
	 * @return the session
	 * @throws br.com.flexclock.common.ApiException with {@code SESSION_GONE} when it no longer exists
	 */
	OpenCodeSession getSession(String sessionId);

	/**
	 * Aborts a running session.
	 *
	 * <p>The abort is the pause signal of decision 0034. A confirmation from the server is required: an
	 * implementation never reports success on a refusal.
	 *
	 * @param sessionId the session identifier resolved from the anchor
	 */
	void abort(String sessionId);

	/**
	 * Sends one direction turn to a session.
	 *
	 * <p>The text is supplied by the pause workstream, because it is the instruction that makes the
	 * agent ask the user. The client only guarantees the transport, the bound on the text and the
	 * retry.
	 *
	 * @param sessionId the session identifier resolved from the anchor
	 * @param directionTurnText the instruction to deliver
	 */
	void sendDirectionTurn(String sessionId, String directionTurnText);

	/**
	 * Applies a pause: health check, abort and direction turn.
	 *
	 * <p>It never produces a phantom pause. The outcome is {@link OpenCodePauseOutcome#PAUSED} only when
	 * the server confirmed the abort and accepted the direction turn; when the abort was applied but
	 * the direction turn failed, the outcome is {@link OpenCodePauseOutcome#PAUSE_FAILED} with
	 * {@code abortApplied} true, so the caller can apply decision 0045 with the facts instead of
	 * guessing. It writes nothing: persisting the pause belongs to the pause workstream (decision 0034
	 * and 0046; requirement RF-155).
	 *
	 * @param sessionId the session identifier resolved from the anchor
	 * @param directionTurnText the instruction to deliver after the abort
	 * @return the facts of the attempt
	 */
	OpenCodePauseResult pause(String sessionId, String directionTurnText);
}
