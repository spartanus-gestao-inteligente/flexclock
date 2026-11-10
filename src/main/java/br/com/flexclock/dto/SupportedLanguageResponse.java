package br.com.flexclock.dto;

/**
 * One language the product ships.
 *
 * @param tag the BCP 47 tag stored in the settings table
 * @param tier the commercial tier of decision 0019
 * @param rightToLeft true when the language is written right to left
 */
public record SupportedLanguageResponse(String tag, int tier, boolean rightToLeft) {
}
