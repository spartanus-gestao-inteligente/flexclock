package br.com.flexclock.dto;

import java.time.Instant;

/**
 * Product metadata of the live backend.
 *
 * <p>Publishes the product version, the API version, the database schema version, the effective
 * language, timezone and region clock source, the effective OpenCode endpoint and the identity of the
 * process that answers (requirement RF-007).
 *
 * @param productName the product name
 * @param productVersion the product version
 * @param apiVersion the API version
 * @param contractVersion the contract version shared with the MCP and event contracts
 * @param schemaVersion the database schema version
 * @param locale the effective software language
 * @param timezone the effective timezone
 * @param regionClockSource the source of the region clock
 * @param opencodeUrl the effective OpenCode endpoint
 * @param instanceId the per run instance identifier
 * @param pid the process id
 * @param address the effective listen address
 * @param port the effective listen port
 * @param startedAt the start instant of the process
 */
public record MetadataResponse(String productName, String productVersion, String apiVersion, String contractVersion,
		String schemaVersion, String locale, String timezone, String regionClockSource, String opencodeUrl,
		String instanceId, long pid, String address, int port, Instant startedAt) {
}
