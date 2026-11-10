package br.com.flexclock.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.InstanceDescriptor;
import br.com.flexclock.config.OpenCodeClientConfig;
import br.com.flexclock.dto.HealthResponse;
import br.com.flexclock.dto.MetadataResponse;
import br.com.flexclock.dto.VersionResponse;
import br.com.flexclock.opencode.OpenCodeEndpoint;

/**
 * Lifecycle of the backend: readiness, product metadata and the contract version handshake.
 *
 * <p>Readiness is true only after the database answers a probe query, so a process that cannot reach
 * SQLite never reports itself ready (requirement RF-003). The effective language, timezone, region
 * clock source and OpenCode endpoint come from the settings table, not from a fixed property, so a
 * change of language or timezone is visible on the next call without a restart (requirements RF-007
 * and RF-009).
 */
@Service
public class PlatformService {

	private static final Logger log = LoggerFactory.getLogger(PlatformService.class);

	private static final String PROBE_QUERY = "SELECT 1";

	private final JdbcTemplate jdbcTemplate;
	private final InstanceDescriptor instanceDescriptor;
	private final OpenCodeClientConfig openCodeClientConfig;
	private final SettingsService settingsService;
	private final SchemaVersionProvider schemaVersionProvider;
	private final String productName;
	private final String productVersion;
	private final String configuredLocale;

	public PlatformService(JdbcTemplate jdbcTemplate, InstanceDescriptor instanceDescriptor,
			OpenCodeClientConfig openCodeClientConfig, SettingsService settingsService,
			SchemaVersionProvider schemaVersionProvider,
			@Value("${spring.application.name:FlexClock}") String productName,
			@Value("${flexclock.product.version:0.0.1-SNAPSHOT}") String productVersion,
			@Value("${flexclock.locale.default:en}") String configuredLocale) {
		this.jdbcTemplate = jdbcTemplate;
		this.instanceDescriptor = instanceDescriptor;
		this.openCodeClientConfig = openCodeClientConfig;
		this.settingsService = settingsService;
		this.schemaVersionProvider = schemaVersionProvider;
		this.productName = productName;
		this.productVersion = productVersion;
		this.configuredLocale = configuredLocale;
	}

	/**
	 * Reports readiness and the effective runtime metadata.
	 *
	 * @return the health payload, with status UP or DOWN
	 */
	public HealthResponse health() {
		boolean ready = isReady();
		String language = ready ? language(settingsService.effective(SettingKey.LANGUAGE)) : configuredLocale;
		String endpoint = ready ? openCodeEndpoint() : openCodeClientConfig.baseUrl();
		return new HealthResponse(ready ? HealthResponse.STATUS_UP : HealthResponse.STATUS_DOWN,
				schemaVersionProvider.current(), language, endpoint);
	}

	/**
	 * Answers a probe query against the database.
	 *
	 * @return true when the database answers
	 */
	public boolean isReady() {
		try {
			Integer probe = jdbcTemplate.queryForObject(PROBE_QUERY, Integer.class);
			return probe != null && probe == 1;
		}
		catch (DataAccessException exception) {
			log.warn("Readiness probe failed", exception);
			return false;
		}
	}

	/**
	 * Reports the product metadata of the live backend.
	 *
	 * @return the metadata payload
	 */
	public MetadataResponse metadata() {
		return new MetadataResponse(productName, productVersion, ContractVersion.VERSION, ContractVersion.VERSION,
				schemaVersionProvider.current(), language(settingsService.effective(SettingKey.LANGUAGE)),
				settingsService.effective(SettingKey.TIMEZONE),
				settingsService.effective(SettingKey.REGION_CLOCK_SOURCE), openCodeEndpoint(),
				instanceDescriptor.instanceId(), instanceDescriptor.pid(), instanceDescriptor.address(),
				instanceDescriptor.port(), instanceDescriptor.startedAt());
	}

	/**
	 * Reports the contract version used by the compatibility handshake.
	 *
	 * @return the version payload
	 */
	public VersionResponse version() {
		return new VersionResponse(ContractVersion.VERSION, ContractVersion.MAJOR, ContractVersion.VERSION,
				productVersion);
	}

	private String openCodeEndpoint() {
		String stored = settingsService.effective(SettingKey.OPENCODE_ENDPOINT);
		return OpenCodeEndpoint.isAllowed(stored) ? stored : openCodeClientConfig.baseUrl();
	}

	private String language(String stored) {
		return LanguageCatalogue.fallback(stored);
	}
}
