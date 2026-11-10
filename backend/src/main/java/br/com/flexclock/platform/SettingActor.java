package br.com.flexclock.platform;

import java.util.Locale;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.enums.SettingOwner;

/**
 * Principal that performs a write on the settings table.
 *
 * <p>The MVP has no user authentication (decision 0004), so the caller declares which principal it is
 * through the {@value #ACTOR_HEADER} header. The declaration is a rule of the product, not a security
 * boundary: the trust boundary is the operating system account and the loopback listen address
 * (decision 0050). It exists because a key owned by the user must never be changed by the agent, even
 * on the user's request (requirement RF-162, rule RN-051).
 *
 * <p>A request without the header is treated as the agent, the least privileged principal, so a client
 * that forgets the header can never escalate to a user owned key.
 */
public enum SettingActor {

	/** The human owner of the machine, through the desktop interface or the installer. */
	USER("user"),

	/** The agent, the MCP sidecar or any automation acting on the user's request. */
	AGENT("agent");

	/** Header that declares the principal performing the write. */
	public static final String ACTOR_HEADER = "X-FlexClock-Actor";

	private final String wireValue;

	SettingActor(String wireValue) {
		this.wireValue = wireValue;
	}

	/**
	 * Resolves the actor of a request.
	 *
	 * @param presented the header value, may be null
	 * @return the declared actor, or the agent when the header is absent
	 * @throws ApiException with {@link ErrorCode#INVALID_ENUM_VALUE} when the value is not in the set
	 */
	public static SettingActor fromHeader(String presented) {
		if (presented == null || presented.isBlank()) {
			return AGENT;
		}
		String candidate = presented.trim().toLowerCase(Locale.ROOT);
		for (SettingActor actor : values()) {
			if (actor.wireValue.equals(candidate)) {
				return actor;
			}
		}
		throw new ApiException(ErrorCode.INVALID_ENUM_VALUE, ACTOR_HEADER,
				"The actor must be user or agent.");
	}

	/**
	 * Returns the value published by the API and stored in the audit column.
	 *
	 * @return the wire value
	 */
	public String wireValue() {
		return wireValue;
	}

	/**
	 * Reports whether this actor may change a key with the given owner.
	 *
	 * @param owner the owner of the key
	 * @return true when the actor is the user or the key belongs to the agent
	 */
	public boolean mayWrite(SettingOwner owner) {
		return owner != SettingOwner.USER || this == USER;
	}
}
