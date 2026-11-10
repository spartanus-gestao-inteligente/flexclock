package br.com.flexclock.opencode;

/**
 * One OpenCode session, reduced to the closed fields the product stores.
 *
 * <p>The response of the OpenCode server is untrusted data: it is converted into this closed type and
 * every text field is bounded before it can be stored, so a hostile or broken payload can never grow
 * a column past its declared length (requirement RF-152).
 *
 * @param sessionId the session identifier, bounded to the width of the anchor column
 * @param title the session title, bounded to the width of the anchor column
 * @param directory the working directory, bounded to the width of the anchor column
 * @param parentId the parent session identifier, null or absent for a main session
 */
public record OpenCodeSession(String sessionId, String title, String directory, String parentId) {

	/** Maximum stored length of a session identifier, matching {@code agent_anchor.session_id}. */
	public static final int MAX_SESSION_ID_LENGTH = 128;

	/** Maximum stored length of a session title, matching {@code agent_anchor.title}. */
	public static final int MAX_TITLE_LENGTH = 500;

	/** Maximum stored length of a working directory, matching {@code agent_anchor.directory}. */
	public static final int MAX_DIRECTORY_LENGTH = 1000;

	/**
	 * Reports whether this is a main session of the product.
	 *
	 * <p>Only a session whose parent is null or absent can be anchored; a subagent session is never
	 * listed and never anchored (decision 0043; requirement RF-012).
	 *
	 * @return true when the session has no parent
	 */
	public boolean isMain() {
		return parentId == null || parentId.isBlank();
	}

	/**
	 * Bounds a text field to the stored width, keeping the head of the value.
	 *
	 * @param value the untrusted value
	 * @param maxLength the maximum stored length
	 * @return the bounded value, never null
	 */
	static String bound(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}
}
