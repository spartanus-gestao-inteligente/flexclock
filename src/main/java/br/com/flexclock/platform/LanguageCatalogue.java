package br.com.flexclock.platform;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;

/**
 * Closed list of the thirty commercial languages of the product.
 *
 * <p>English is the canonical source and the fallback: a translation that is missing or that fails the
 * placeholder check falls back to English and never to the raw key (decision 0019; requirement
 * RF-134, rule RN-052). Arabic and Hebrew are right to left, so the interface mirrors the layout for
 * them (requirement RF-133).
 *
 * <p>The list is served by {@code GET /api/v1/language} so the interface and the agent share exactly
 * one source of truth, and the language setting accepts no tag outside it.
 */
public final class LanguageCatalogue {

	/** Language that is always present, canonical for the source strings and the fallback. */
	public static final String CANONICAL_FALLBACK = "en";

	/** Maximum accepted length of a language tag, matching the widest BCP 47 tag of the list. */
	public static final int MAX_TAG_LENGTH = 35;

	/**
	 * One supported language.
	 *
	 * @param tag the BCP 47 tag published by the API and stored in the settings table
	 * @param tier the commercial tier of decision 0019
	 * @param rightToLeft true when the language is written right to left
	 */
	public record SupportedLanguage(String tag, int tier, boolean rightToLeft) {
	}

	private static final List<SupportedLanguage> LANGUAGES = List.of(
			// Tier 1.
			new SupportedLanguage("en", 1, false),
			new SupportedLanguage("es", 1, false),
			new SupportedLanguage("pt-BR", 1, false),
			new SupportedLanguage("fr", 1, false),
			new SupportedLanguage("de", 1, false),
			new SupportedLanguage("it", 1, false),
			new SupportedLanguage("ja", 1, false),
			new SupportedLanguage("zh-Hans", 1, false),
			new SupportedLanguage("ko", 1, false),
			new SupportedLanguage("ru", 1, false),
			// Tier 2.
			new SupportedLanguage("sv", 2, false),
			new SupportedLanguage("no", 2, false),
			new SupportedLanguage("da", 2, false),
			new SupportedLanguage("nl", 2, false),
			new SupportedLanguage("pl", 2, false),
			new SupportedLanguage("pt-PT", 2, false),
			new SupportedLanguage("tr", 2, false),
			new SupportedLanguage("ar", 2, true),
			new SupportedLanguage("he", 2, true),
			new SupportedLanguage("zh-Hant", 2, false),
			// Tier 3.
			new SupportedLanguage("fi", 3, false),
			new SupportedLanguage("id", 3, false),
			new SupportedLanguage("vi", 3, false),
			new SupportedLanguage("th", 3, false),
			new SupportedLanguage("hi", 3, false),
			new SupportedLanguage("cs", 3, false),
			new SupportedLanguage("uk", 3, false),
			new SupportedLanguage("ro", 3, false),
			new SupportedLanguage("el", 3, false),
			new SupportedLanguage("hu", 3, false));

	private LanguageCatalogue() {
		// Constants only.
	}

	/**
	 * Returns the thirty supported languages in catalogue order.
	 *
	 * @return the immutable list of supported languages
	 */
	public static List<SupportedLanguage> all() {
		return LANGUAGES;
	}

	/**
	 * Finds a language by tag, ignoring the case of the tag.
	 *
	 * @param tag the presented tag
	 * @return the supported language when the tag belongs to the list
	 */
	public static Optional<SupportedLanguage> find(String tag) {
		if (tag == null) {
			return Optional.empty();
		}
		String candidate = tag.trim();
		return LANGUAGES.stream().filter(language -> language.tag().equalsIgnoreCase(candidate)).findFirst();
	}

	/**
	 * Validates a language tag and returns its canonical spelling.
	 *
	 * @param tag the presented tag
	 * @return the canonical tag, for example {@code pt-BR} for {@code pt-br}
	 * @throws ApiException with {@link ErrorCode#INVALID_ENUM_VALUE} when the tag is not supported
	 */
	public static String requireSupported(String tag) {
		if (tag != null && tag.trim().length() > MAX_TAG_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_ENUM_VALUE, "language",
					"The language tag is longer than " + MAX_TAG_LENGTH + " characters.");
		}
		return find(tag).map(SupportedLanguage::tag)
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_ENUM_VALUE, "language",
						"The language tag is not part of the closed list of " + LANGUAGES.size()
								+ " commercial languages."));
	}

	/**
	 * Reports whether a language is written right to left.
	 *
	 * @param tag the language tag
	 * @return true for Arabic and Hebrew, false otherwise
	 */
	public static boolean isRightToLeft(String tag) {
		return find(tag).map(SupportedLanguage::rightToLeft).orElse(false);
	}

	/**
	 * Falls back to the canonical language when a stored tag is not supported.
	 *
	 * @param tag the stored tag
	 * @return the canonical tag, or {@value #CANONICAL_FALLBACK} when the tag cannot be used
	 */
	public static String fallback(String tag) {
		return find(tag).map(SupportedLanguage::tag).orElse(CANONICAL_FALLBACK);
	}

	/**
	 * Reports whether the tag is the canonical fallback language.
	 *
	 * @param tag the language tag
	 * @return true when the tag is English
	 */
	public static boolean isCanonical(String tag) {
		return tag != null && CANONICAL_FALLBACK.equals(tag.toLowerCase(Locale.ROOT));
	}
}
