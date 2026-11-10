package br.com.flexclock.dto;

import br.com.flexclock.enums.TimeMode;
import jakarta.validation.constraints.NotNull;

/**
 * Request that starts a work session.
 *
 * <p>The shape starts from the contract record and adds two optional fields, which is an additive
 * change: a work session may reference a procedure (requirement RF-030) and may carry an explicit
 * timezone. Without a timezone the configured region timezone is used, and the instant is always
 * stored in UTC.
 *
 * @param mode the time mode, required
 * @param targetSeconds the countdown target in seconds, required only for a project countdown
 * @param procedureId the procedure that owns the session, null for a session without a procedure
 * @param timezone the IANA timezone used by the midnight split, null to use the configured region
 */
public record WorkSessionRequest(@NotNull TimeMode mode, Integer targetSeconds, Long procedureId, String timezone) {
}
