package br.com.flexclock.common;

/**
 * Result of a write that went through the idempotency guard.
 *
 * <p>It carries the status of the original response, so a replay of the same key answers exactly what
 * the first call answered instead of inventing a new status (architecture contract, section 5;
 * requirement RF-174).
 *
 * @param status the HTTP status of the original response
 * @param body the body of the original response
 * @param <T> the type of the body
 */
public record IdempotentResponse<T>(int status, T body) {
}
