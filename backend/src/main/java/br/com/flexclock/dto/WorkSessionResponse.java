package br.com.flexclock.dto;

import java.time.Instant;

import br.com.flexclock.enums.TimeMode;

/**
 * Response of a started work session.
 *
 * <p>Shape frozen by the architecture contract, section 6.2.
 *
 * @param workSessionId the identifier of the created session
 * @param mode the effective time mode
 * @param startedAt the start instant, in UTC
 */
public record WorkSessionResponse(Long workSessionId, TimeMode mode, Instant startedAt) {
}
