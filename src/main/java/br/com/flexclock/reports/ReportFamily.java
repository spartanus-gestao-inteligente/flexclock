package br.com.flexclock.reports;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The nine report families of the product, with the wire token used by the REST path and the base
 * name of the versioned JRXML template of each family.
 *
 * <p>The wire tokens are part of the frozen contract: the dashboard, the MCP sidecar and the report
 * templates all refer to a family by one of them, so a token never changes inside a major contract
 * version.
 *
 * <p>Decisions 0013, 0033, 0052; architecture contract, sections 4.2 and 6.1.
 */
public enum ReportFamily {

	/** Coding time derived from the work sessions, with the midnight split and pauses subtracted. */
	TIME("time", "time_report"),

	/** Failures and errors: the failures report is the error report. */
	FAILURES("failures", "failures_report"),

	/** Confirmed hallucination events and their rate when a denominator exists. */
	HALLUCINATION("hallucination", "hallucination_report"),

	/** Session quality scores and the per criterion breakdown. */
	QUALITY("quality", "quality_report"),

	/** Usage of the Spartangraphify knowledge graph. */
	GRAPH_USAGE("graph_usage", "graph_usage_report"),

	/** Tokens spent and the estimated saving, with the explicit not measurable state. */
	TOKEN_ECONOMY("token_economy", "token_economy_report"),

	/** Sources consulted by an agent, by origin and by agent. */
	SOURCES("sources", "sources_report"),

	/** Downloads performed by an agent. */
	DOWNLOADS("downloads", "downloads_report"),

	/** Uploads performed by an agent. */
	UPLOADS("uploads", "uploads_report");

	private final String wireName;
	private final String templateName;

	ReportFamily(String wireName, String templateName) {
		this.wireName = wireName;
		this.templateName = templateName;
	}

	/**
	 * Returns the token used in the REST path and in the dashboard.
	 *
	 * @return the wire token, never null
	 */
	public String wireName() {
		return wireName;
	}

	/**
	 * Returns the token used in the REST path and in the dashboard, also used as the JSON value.
	 *
	 * @return the wire token, never null
	 */
	@JsonValue
	public String wireValue() {
		return wireName;
	}

	/**
	 * Resolves the family of a wire token for a JSON payload.
	 *
	 * @param value the token
	 * @return the matching family
	 */
	@JsonCreator
	public static ReportFamily fromWireValue(String value) {
		return fromWireName(value);
	}

	/**
	 * Returns the base name of the JRXML template of this family, without directory or extension.
	 *
	 * @return the template base name, never null
	 */
	public String templateName() {
		return templateName;
	}

	/**
	 * Resolves the family of a wire token.
	 *
	 * @param wireName the token of the path, case insensitive
	 * @return the matching family
	 * @throws IllegalArgumentException when the token is not one of the nine families
	 */
	public static ReportFamily fromWireName(String wireName) {
		if (wireName != null) {
			String normalized = wireName.trim().toLowerCase(java.util.Locale.ROOT);
			for (ReportFamily family : values()) {
				if (family.wireName.equals(normalized)) {
					return family;
				}
			}
		}
		throw new IllegalArgumentException("Unknown report family: " + wireName);
	}

	/**
	 * Reports whether the family is fed by an append only table, so a record can be ingested through
	 * the report write path.
	 *
	 * <p>The time family is derived from the work sessions owned by the clock, never appended through
	 * a report write.
	 *
	 * @return true when the family accepts an appended record
	 */
	public boolean acceptsRecords() {
		return this != TIME;
	}
}
