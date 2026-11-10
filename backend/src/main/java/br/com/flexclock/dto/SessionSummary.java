package br.com.flexclock.dto;

/**
 * One main OpenCode session offered to the session selector.
 *
 * <p>Architecture contract, section 6.2.
 *
 * @param sessionId the OpenCode session identifier
 * @param title the session title reported by the OpenCode server
 * @param directory the working directory of the session
 */
public record SessionSummary(String sessionId, String title, String directory) {
}
