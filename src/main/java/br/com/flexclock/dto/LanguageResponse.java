package br.com.flexclock.dto;

import java.time.Instant;
import java.util.List;

/**
 * Language of the product and the closed list of the languages it ships.
 *
 * <p>It is the answer of the dedicated endpoint of decision 0019, which the agent may call to change
 * the default language when the user asks for it, and which the interface polls to apply a change
 * without a restart (requirements RF-130, RF-131, RF-132).
 *
 * @param language the effective language tag
 * @param rightToLeft true when the effective language is written right to left
 * @param supported the closed list of the thirty commercial languages
 * @param updatedAt the instant of the last change, null when the row is absent
 */
public record LanguageResponse(String language, boolean rightToLeft, List<SupportedLanguageResponse> supported,
		Instant updatedAt) {
}
