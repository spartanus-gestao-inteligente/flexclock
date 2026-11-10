package br.com.flexclock.common;

/**
 * Contract version shared by the REST API, the MCP tool contract and the event contract.
 *
 * <p>The three contracts are versioned together and the semantic version appears in the path
 * ({@code /api/v1/...}). Within the same major version the evolution is additive only: renaming,
 * removing or changing the meaning of a field requires a major version increment. A divergent major
 * version must block the MCP mutating tools and a missing version must block every call.
 *
 * <p>Architecture contract, section 5.
 */
public final class ContractVersion {

	/** Major version of the contract. A change here breaks clients. */
	public static final int MAJOR = 1;

	/** Minor version of the contract. Additive evolution only. */
	public static final int MINOR = 0;

	/** Patch version of the contract. Fixes that do not change the contract surface. */
	public static final int PATCH = 0;

	/** Semantic version of the contract as published to the clients. */
	public static final String VERSION = MAJOR + "." + MINOR + "." + PATCH;

	/** Base path of the versioned REST API. */
	public static final String API_BASE_PATH = "/api/v1";

	/**
	 * Request header that carries the contract version on the compatibility handshake. The name is
	 * frozen here so the backend, the MCP sidecar and the event service agree on it.
	 */
	public static final String VERSION_HEADER = "X-FlexClock-Contract-Version";

	private ContractVersion() {
		// Constants only.
	}

	/**
	 * Reads the major component of a presented contract version.
	 *
	 * <p>It accepts a semantic version ({@code 1.0.0}), or the major alone ({@code 1}), because a
	 * client may present the value it was built with and the handshake only compares the major
	 * (architecture contract, section 5; requirement RF-102).
	 *
	 * @param presented the presented value, may be null
	 * @return the major component, or minus one when the value cannot be read as a version
	 */
	public static int majorOf(String presented) {
		if (presented == null) {
			return -1;
		}
		String candidate = presented.trim();
		if (candidate.isEmpty()) {
			return -1;
		}
		String majorPart = candidate.indexOf('.') >= 0 ? candidate.substring(0, candidate.indexOf('.')) : candidate;
		if (majorPart.isEmpty() || majorPart.length() > 4) {
			return -1;
		}
		for (int index = 0; index < majorPart.length(); index++) {
			if (!Character.isDigit(majorPart.charAt(index))) {
				return -1;
			}
		}
		return Integer.parseInt(majorPart);
	}

	/**
	 * Reports whether a presented version is compatible with this contract.
	 *
	 * @param presented the presented value, may be null
	 * @return true when the major component matches the live contract
	 */
	public static boolean isCompatible(String presented) {
		return majorOf(presented) == MAJOR;
	}
}
