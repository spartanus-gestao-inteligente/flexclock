package br.com.flexclock.reports;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Repository;

import br.com.flexclock.model.DownloadEvent;
import br.com.flexclock.model.ErrorDetail;
import br.com.flexclock.model.FailureEvent;
import br.com.flexclock.model.GraphUsageEvent;
import br.com.flexclock.model.HallucinationEvent;
import br.com.flexclock.model.Procedure;
import br.com.flexclock.model.QualityEvaluation;
import br.com.flexclock.model.SourceEvent;
import br.com.flexclock.model.TokenEconomyEvent;
import br.com.flexclock.model.UploadEvent;
import br.com.flexclock.model.WorkSession;
import jakarta.persistence.EntityManager;

/**
 * Read only access to the nine report tables and to the work sessions.
 *
 * <p>Every method is a SELECT: the repository exposes no update and no delete, which is the first of
 * the two append only defences. The second one is the SQLite trigger that aborts an update or a
 * delete inside the database (decision 0049; requirement RF-048).
 *
 * <p>Rows of a window are selected by the half open interval {@code [start, end)} and aggregated in
 * the service. The aggregation runs in Java on purpose: the time report has to split a session at
 * midnight of the configured timezone and to count overlapping sessions once, which SQL cannot do on
 * a timestamp stored in UTC without reimplementing the zone rules in every query.
 *
 * <p>Requirements RF-054 to RF-064, RF-065.
 */
@Repository
public class ReportQueryRepository {

	private final EntityManager entityManager;

	public ReportQueryRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/**
	 * Reads the work sessions that overlap the window.
	 *
	 * @param window the resolved window
	 * @param now the instant used as the end of an open session
	 * @return the sessions, ordered by start
	 */
	public List<WorkSession> workSessions(ReportPeriodWindow window, Instant now) {
		return entityManager.createQuery("""
				select s from WorkSession s
				where s.startedAt < :end
				and coalesce(s.endedAt, :now) > :start
				and (:procedureId is null or s.procedureId = :procedureId)
				order by s.startedAt
				""", WorkSession.class)
				.setParameter("start", window.start())
				.setParameter("end", window.end())
				.setParameter("now", now)
				.setParameter("procedureId", window.procedureId())
				.getResultList();
	}

	/**
	 * Reads the procedures of a set of identifiers, so a work session row can carry the procedure title
	 * and the OpenCode session it belongs to.
	 *
	 * @param procedureIds the identifiers of the procedures
	 * @return the procedures, ordered by identifier
	 */
	public List<Procedure> procedures(Collection<Long> procedureIds) {
		if (procedureIds.isEmpty()) {
			return List.of();
		}
		return entityManager.createQuery("""
				select p from Procedure p where p.id in :ids order by p.id
				""", Procedure.class)
				.setParameter("ids", procedureIds)
				.getResultList();
	}

	/**
	 * Reads the failure records of the window.
	 *
	 * @param window the resolved window
	 * @return the records, ordered by occurrence
	 */
	public List<FailureEvent> failures(ReportPeriodWindow window) {
		return windowed("FailureEvent", FailureEvent.class, window);
	}

	/**
	 * Reads the error details of a set of failure records.
	 *
	 * @param failureEventIds the identifiers of the failure records
	 * @return the details, ordered by identifier
	 */
	public List<ErrorDetail> errorDetails(List<Long> failureEventIds) {
		if (failureEventIds.isEmpty()) {
			return List.of();
		}
		return entityManager.createQuery("""
				select d from ErrorDetail d where d.failureEventId in :ids order by d.id
				""", ErrorDetail.class)
				.setParameter("ids", failureEventIds)
				.getResultList();
	}

	/**
	 * Reads the confirmed hallucination events of the window.
	 *
	 * @param window the resolved window
	 * @return the events, ordered by occurrence
	 */
	public List<HallucinationEvent> hallucinations(ReportPeriodWindow window) {
		return windowed("HallucinationEvent", HallucinationEvent.class, window);
	}

	/**
	 * Reads the quality evaluations of the window.
	 *
	 * @param window the resolved window
	 * @return the evaluations, ordered by occurrence
	 */
	public List<QualityEvaluation> qualityEvaluations(ReportPeriodWindow window) {
		return windowed("QualityEvaluation", QualityEvaluation.class, window);
	}

	/**
	 * Reads the graph usage records of the window.
	 *
	 * @param window the resolved window
	 * @return the records, ordered by occurrence
	 */
	public List<GraphUsageEvent> graphUsages(ReportPeriodWindow window) {
		return windowed("GraphUsageEvent", GraphUsageEvent.class, window);
	}

	/**
	 * Reads the token economy records of the window.
	 *
	 * @param window the resolved window
	 * @return the records, ordered by occurrence
	 */
	public List<TokenEconomyEvent> tokenEconomies(ReportPeriodWindow window) {
		return windowed("TokenEconomyEvent", TokenEconomyEvent.class, window);
	}

	/**
	 * Reads the source accesses of the window.
	 *
	 * @param window the resolved window
	 * @return the accesses, ordered by occurrence
	 */
	public List<SourceEvent> sources(ReportPeriodWindow window) {
		return windowed("SourceEvent", SourceEvent.class, window);
	}

	/**
	 * Reads the downloads of the window.
	 *
	 * @param window the resolved window
	 * @return the downloads, ordered by occurrence
	 */
	public List<DownloadEvent> downloads(ReportPeriodWindow window) {
		return windowed("DownloadEvent", DownloadEvent.class, window);
	}

	/**
	 * Reads the uploads of the window.
	 *
	 * @param window the resolved window
	 * @return the uploads, ordered by occurrence
	 */
	public List<UploadEvent> uploads(ReportPeriodWindow window) {
		return windowed("UploadEvent", UploadEvent.class, window);
	}

	private <T> List<T> windowed(String entityName, Class<T> type, ReportPeriodWindow window) {
		String query = "select e from " + entityName + " e where e.occurredAt >= :start and e.occurredAt < :end"
				+ " and (:procedureId is null or e.procedureId = :procedureId) order by e.occurredAt, e.id";
		return entityManager.createQuery(query, type)
				.setParameter("start", window.start())
				.setParameter("end", window.end())
				.setParameter("procedureId", window.procedureId())
				.getResultList();
	}
}
