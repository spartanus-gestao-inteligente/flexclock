package br.com.flexclock.pause;

import java.time.Instant;
import java.util.Map;

/**
 * Timeout notification of an expired pause.
 *
 * <p>The backend computes the 60 minute limit locally, marks the pause expired and publishes this
 * descriptor; it never resumes the procedure and never sends a turn to the agent (decision 0044;
 * requirement RF-037). The notification channel is the desktop product: the clock window shows the
 * system notification, repeats it every fifteen minutes while the pause stays expired and unresolved,
 * and focuses the dashboard when the notification is clicked.
 *
 * <p>The message is the English canonical text; the interface localizes from {@code code} and
 * {@code parameters}, so the backend never depends on a localized phrase.
 *
 * @param pauseId the pause identifier
 * @param procedureId the procedure identifier
 * @param sessionId the OpenCode session identifier
 * @param procedureTitle the cached procedure title, used by the notification text
 * @param expiredAt the instant the 60 minute limit was reached
 * @param nextDueAt the next instant the notification is due, fifteen minutes apart
 * @param repeatIntervalMinutes the repeat interval in minutes while the pause stays unresolved
 * @param code the stable notification code
 * @param title the English canonical title of the system notification
 * @param message the English canonical message
 * @param parameters the parameters the interface interpolates after localizing
 */
public record PauseNotification(Long pauseId, Long procedureId, String sessionId, String procedureTitle,
		Instant expiredAt, Instant nextDueAt, long repeatIntervalMinutes, String code, String title, String message,
		Map<String, Object> parameters) {

	/** Stable code of the pause timeout notification. */
	public static final String CODE_PAUSE_EXPIRED = "PAUSE_EXPIRED";

	/** English canonical title of the system notification, fixed by decision 0044. */
	public static final String TITLE_PAUSE_EXPIRED = "Flex Clock - Pause expired";
}
