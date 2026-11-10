package br.com.flexclock.sessions;

import java.util.Collection;
import java.util.List;

import org.springframework.data.repository.Repository;

import br.com.flexclock.model.AgentPause;

/**
 * Read only projection of the pause record used by the anchor list.
 *
 * <p>The anchor list reports the current pause state and the elapsed pause time of each window, as the
 * tray menu and the dashboard need it (requirement RF-019). The pause write path, the state machine
 * and the expiry timer belong to the time workstream, so this interface deliberately exposes no save
 * and no delete: the sessions workstream can only read the rows that workstream writes.
 */
public interface PauseLookupRepository extends Repository<AgentPause, Long> {

	/**
	 * Lists the pauses of several procedures, most recent first.
	 *
	 * @param procedureIds the procedure identifiers
	 * @return the pauses, possibly empty
	 */
	List<AgentPause> findByProcedureIdInOrderByPauseStartDesc(Collection<Long> procedureIds);
}
