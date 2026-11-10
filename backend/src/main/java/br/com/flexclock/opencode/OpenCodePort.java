package br.com.flexclock.opencode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port of the local OpenCode server, owned by the backend.
 *
 * <p>The Spring Boot backend is the only component that talks to the OpenCode API. The Flutter client,
 * the MCP sidecar, the event service and the agent never call the server, and no component accepts a
 * session identifier from a client as authority: the identifier is always resolved from the anchor
 * before an operation runs (decision 0043; requirements RF-012, RF-153).
 *
 * <p>This is the single transport of the product. Every operation resolves the endpoint from the
 * settings table on every call, so a change of the setting takes effect without a restart and no
 * second client can target a different server (decision 0046, requirement RF-009).
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
	 * Applies a pause: health check, abort and direction turn.
	 *
	 * <p>It never produces a phantom pause. The outcome is {@link OpenCodePauseOutcome#PAUSED} only when
	 * the server confirmed the abort and accepted the direction turn; when the abort was applied but
	 * the direction turn failed, the outcome is {@link OpenCodePauseOutcome#PAUSE_FAILED} with
	 * {@code abortApplied} true, so the caller can apply decision 0045 with the facts instead of
	 * guessing. It writes nothing: persisting the pause belongs to the pause workstream (decision 0034
	 * and 0046; requirement RF-155).
	 *
	 * <p>The direction turn is admitted asynchronously, because the synchronous message endpoint
	 * answers only when the agent turn ends and the turn ends only when the question is answered; the
	 * pause endpoint must never block on the open question (decision 0034).
	 *
	 * @param sessionId the session identifier resolved from the anchor
	 * @param directionTurnText the instruction to deliver after the abort
	 * @return the facts of the attempt
	 */
	OpenCodePauseResult pause(String sessionId, String directionTurnText);

	/**
	 * Looks for the continuation answer of a paused session.
	 *
	 * <p>This is the polling fallback allowed by decision 0034: the messages of the session are read and
	 * a user message created after the pause start, other than the direction turn itself, is treated as
	 * the answer that resumes the procedure. The detector is a heuristic over the documented message
	 * shape; it never sends anything and it never resumes by itself, it only reports the instant it saw.
	 * A server that cannot be read yields an empty answer instead of an error, because the detector
	 * runs on a timer and a pause stays resolvable by a later poll.
	 *
	 * @param sessionId the session identifier resolved from the anchor
	 * @param since the pause start instant
	 * @param directionTurnText the instruction the pause sent, excluded from the answers
	 * @return the instant of the continuation answer, empty when there is none or the server is down
	 */
	Optional<Instant> findContinuationAnswer(String sessionId, Instant since, String directionTurnText);
}
