package br.com.flexclock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of a language change.
 *
 * @param language the language tag, one of the thirty commercial languages
 */
public record LanguageUpdateRequest(@NotBlank @Size(max = 35) String language) {
}
