package br.com.flexclock.dto;

import java.time.Instant;

import br.com.flexclock.enums.PauseStatus;

/**
 * Response of an applied pause transition.
 *
 * <p>Shape frozen by the architecture contract, section 6.2.
 *
 * @param status the resulting pause status
 * @param pauseStart the instant the pause started, in UTC
 */
public record PauseResponse(PauseStatus status, Instant pauseStart) {
}
