package br.com.flexclock.opencode;

/**
 * Health of the local OpenCode server, as published by {@code GET /global/health}.
 *
 * @param healthy true when the server reports itself healthy
 * @param version the server version reported by the health payload, null when it is absent
 */
public record OpenCodeHealth(boolean healthy, String version) {

	/** Health reported when the server could not be reached at all. */
	public static final OpenCodeHealth UNREACHABLE = new OpenCodeHealth(false, null);
}
