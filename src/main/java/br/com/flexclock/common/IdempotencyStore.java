package br.com.flexclock.common;

import java.util.Optional;

/**
 * Contract of the idempotency store used by every write path.
 *
 * <p>The store answers two questions: has this key already produced a result, and was the payload the
 * same. The write path depends on this interface only, so the storage decision stays in one place.
 *
 * <p><strong>Storage decision for the MVP (WS-B1):</strong> the store stays in memory
 * ({@link InMemoryIdempotencyStore}). The reasons, in order:
 *
 * <ol>
 * <li>the architecture contract freezes fourteen tables and none of them is an idempotency table;
 * adding a fifteenth table would change the frozen contract of the product;</li>
 * <li>the backend is a single process with a single writer connection, so a process local store is
 * enough to deduplicate every concurrent caller inside one run;</li>
 * <li>every write of the MVP is either naturally repeatable (a settings value, a report record whose
 * key is unique in its own table) or protected by a unique column of its own table, so a restart
 * cannot duplicate a row;</li>
 * <li>the store is bounded and expires old keys, so a long desktop session cannot grow without limit
 * (requirement RNF-009).</li>
 * </ol>
 *
 * <p>The cost is explicit: after a restart, replaying an old key re-executes the write instead of
 * returning the stored response. For the settings endpoint that is harmless, because writing the same
 * value twice is the same state. If a later workstream needs cross restart replay, this interface is
 * the single point that has to change.
 */
public interface IdempotencyStore {

	/**
	 * Finds the record of a key.
	 *
	 * @param idempotencyKey the key presented by the client
	 * @return the stored record when the key was already used
	 */
	Optional<IdempotencyRecord> find(String idempotencyKey);

	/**
	 * Stores the result of a write.
	 *
	 * @param record the record to store
	 */
	void save(IdempotencyRecord record);
}
