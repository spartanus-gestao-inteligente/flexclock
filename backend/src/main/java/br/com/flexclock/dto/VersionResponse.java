package br.com.flexclock.dto;

/**
 * Contract version handshake.
 *
 * <p>The MCP sidecar and the event service read this endpoint at startup and on each mutating call.
 * A divergent major version blocks the mutating tools and a missing version blocks every call
 * (requirement RF-102; architecture contract, section 5).
 *
 * @param contractVersion the semantic contract version
 * @param contractMajor the major component of the contract version
 * @param apiVersion the API version
 * @param productVersion the product version
 */
public record VersionResponse(String contractVersion, int contractMajor, String apiVersion, String productVersion) {
}
