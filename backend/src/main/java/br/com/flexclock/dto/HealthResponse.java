package br.com.flexclock.dto;

/**
 * Readiness and metadata of the backend.
 *
 * <p>Shape frozen by the architecture contract, section 6.2.
 *
 * @param status UP when the database answers a probe query, DOWN otherwise
 * @param schemaVersion the schema version published by the migration tool
 * @param locale the effective software language
 * @param opencodeUrl the effective OpenCode endpoint
 */
public record HealthResponse(String status, String schemaVersion, String locale, String opencodeUrl) {

	/** Status reported when the database answers the probe query. */
	public static final String STATUS_UP = "UP";

	/** Status reported when the database does not answer the probe query. */
	public static final String STATUS_DOWN = "DOWN";
}
