package br.com.flexclock.pause;

import br.com.flexclock.enums.PauseReason;
import jakarta.validation.constraints.NotNull;

/**
 * Request that ends a pause without resuming the procedure.
 *
 * <p>The reason is one of the three closed values of the contract. User cancel and session gone also
 * end the procedure as aborted; a pause failure keeps the procedure active (decision 0045; requirement
 * RF-038).
 *
 * @param reason the reason of the abort, required
 */
public record PauseAbortRequest(@NotNull PauseReason reason) {
}
