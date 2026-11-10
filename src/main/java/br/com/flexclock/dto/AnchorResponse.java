package br.com.flexclock.dto;

import br.com.flexclock.time.ClockState;

/**
 * Result of anchoring a session: the anchor, the procedure it is linked to and the clock state of the
 * window.
 *
 * <p>Anchoring creates the anchor and creates or links the procedure of the session in the same
 * transaction, so the window receives both identifiers at once (decision 0042; requirements RF-014
 * and RF-028).
 *
 * <p>Architecture contract, section 6.2.
 *
 * @param anchorId the anchor identifier
 * @param procedureId the procedure linked to the session
 * @param sessionId the OpenCode session identifier
 * @param title the cached session title
 * @param clockState the current time state of the window clock
 */
public record AnchorResponse(Long anchorId, Long procedureId, String sessionId, String title, ClockState clockState) {
}
