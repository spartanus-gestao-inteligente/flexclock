package br.com.flexclock.pause;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;

/**
 * Reads the anchor and the procedure facts the pause flow needs.
 *
 * <p>The anchor is the only authority for which OpenCode session may be paused: the client never
 * chooses a session identifier for a pause (decision 0043; requirement RF-041). The lookup also reads
 * the cached procedure title for the timeout notification text.
 */
@Component
public class PauseLookup {

	private static final String ANCHOR_SQL = """
			select a.id as anchor_id, a.session_id, a.title, a.status, p.id as procedure_id
			from agent_anchor a
			left join procedure p on p.session_id = a.session_id
			where a.id = ?
			""";

	private static final String PROCEDURE_TITLE_SQL = "select title from procedure where id = ?";

	private final JdbcTemplate jdbcTemplate;

	public PauseLookup(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Reads an anchor and refuses a pause when the anchor does not exist or is stale.
	 *
	 * @param anchorId the anchor identifier
	 * @return the anchor snapshot
	 * @throws ApiException with {@link ErrorCode#ANCHOR_NOT_FOUND} or {@link ErrorCode#ANCHOR_STALE}
	 */
	public AnchorSnapshot requirePausableAnchor(Long anchorId) {
		List<AnchorSnapshot> rows = jdbcTemplate.query(ANCHOR_SQL, (resultSet, rowNumber) -> {
			long procedureId = resultSet.getLong("procedure_id");
			Long linkedProcedure = resultSet.wasNull() ? null : procedureId;
			return new AnchorSnapshot(resultSet.getLong("anchor_id"), resultSet.getString("session_id"),
					resultSet.getString("title"), resultSet.getString("status"), linkedProcedure);
		}, anchorId);
		if (rows.isEmpty()) {
			throw new ApiException(ErrorCode.ANCHOR_NOT_FOUND, "anchorId", "The anchor does not exist.");
		}
		AnchorSnapshot anchor = rows.getFirst();
		if (!"active".equals(anchor.status())) {
			throw new ApiException(ErrorCode.ANCHOR_STALE, "anchorId",
					"The anchor is stale and the pause is disabled.");
		}
		return anchor;
	}

	/**
	 * Reads the cached procedure title.
	 *
	 * @param procedureId the procedure identifier
	 * @return the title, or an empty string when the procedure no longer exists
	 */
	public String procedureTitle(Long procedureId) {
		List<String> titles = jdbcTemplate.queryForList(PROCEDURE_TITLE_SQL, String.class, procedureId);
		return titles.isEmpty() ? "" : titles.getFirst();
	}

	/**
	 * Snapshot of an anchor and its linked procedure.
	 *
	 * @param anchorId the anchor identifier
	 * @param sessionId the OpenCode session identifier
	 * @param title the cached session title
	 * @param status the anchor status wire token
	 * @param procedureId the procedure identifier, null when the session is not linked yet
	 */
	public record AnchorSnapshot(Long anchorId, String sessionId, String title, String status, Long procedureId) {
	}
}
