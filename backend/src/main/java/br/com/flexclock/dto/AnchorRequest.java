package br.com.flexclock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request that anchors one OpenCode session to the requesting Clock window.
 *
 * <p>One session equals one anchor equals one window. Anchoring a session that is already anchored is
 * rejected with {@code ANCHOR_EXISTS} and the client loads the existing window instead of duplicating
 * the state (decision 0041; requirements RF-014, RF-015 and RN-006).
 *
 * <p>Architecture contract, section 6.2.
 *
 * @param sessionId the OpenCode session identifier chosen in the selector
 */
public record AnchorRequest(@NotBlank @Size(max = 128) String sessionId) {
}
