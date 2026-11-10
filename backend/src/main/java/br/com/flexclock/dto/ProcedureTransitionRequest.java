package br.com.flexclock.dto;

import br.com.flexclock.enums.ProcedureStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request that applies a final status to a procedure.
 *
 * <p>Only {@code completed} and {@code aborted} can be applied; {@code active} is the creation status
 * and is rejected with {@code INVALID_PROCEDURE_TRANSITION}. The event service uses this operation to
 * forward a transition it orchestrated, and the backend remains the single writer (decision 0042;
 * requirement RF-029; architecture contract, section 8.2).
 *
 * <p>Architecture contract, section 6.2.
 *
 * @param status the final status to apply
 */
public record ProcedureTransitionRequest(@NotNull ProcedureStatus status) {
}
