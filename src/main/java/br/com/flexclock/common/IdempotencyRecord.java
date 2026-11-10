package br.com.flexclock.common;

import java.time.Instant;

/**
 * Stored result of a write identified by an idempotency key.
 *
 * <p>Repeating the same key with the same payload returns the original result and does not duplicate
 * the effect. Repeating the key with a different payload is rejected with
 * {@link ErrorCode#IDEMPOTENCY_CONFLICT}. The fingerprint is the digest of the request payload, used
 * only to tell the two cases apart (architecture contract, section 5; requirement RF-174).
 *
 * @param idempotencyKey the key presented by the client
 * @param requestFingerprint digest of the request payload
 * @param responseStatus the HTTP status of the original response
 * @param responseBody the original response body
 * @param createdAt the instant when the original write was recorded
 */
public record IdempotencyRecord(String idempotencyKey, String requestFingerprint, int responseStatus,
		String responseBody, Instant createdAt) {
}
