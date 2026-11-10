package br.com.flexclock.platform;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.flexclock.common.ApiException;
import br.com.flexclock.common.ErrorCode;
import br.com.flexclock.common.IdempotencyService;
import br.com.flexclock.common.IdempotentResponse;
import br.com.flexclock.dto.LanguageResponse;
import br.com.flexclock.dto.SettingResponse;
import br.com.flexclock.dto.SettingsResponse;
import br.com.flexclock.dto.SupportedLanguageResponse;
import br.com.flexclock.model.Setting;

/**
 * Settings of the product: the closed key catalogue, its values and its audit.
 *
 * <p>Every write validates the key against {@link SettingKey} and the value against the type and the
 * domain of the key, refuses a key owned by the user when the caller is the agent, and records which
 * principal changed the row and when (requirement RF-008 and RF-162, rule RN-051). The write is
 * idempotent: the result of the first call is replayed for the same key and payload, and a different
 * payload with the same key is rejected (requirement RF-174).
 *
 * <p>Reads are never cached: a language or timezone change takes effect on the next read, so the next
 * report query and the next metadata call use it without a restart, while stored instants stay
 * untouched in UTC (requirement RF-009).
 *
 * <p>The transaction is opened through a {@link TransactionTemplate} on purpose. The idempotency guard
 * has to run outside the transaction of the write, so the record is stored only after the commit
 * (requirement RF-155).
 */
@Service
public class SettingsService {

	private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

	private final SettingRepository settingRepository;
	private final IdempotencyService idempotencyService;
	private final TransactionTemplate readTransaction;
	private final TransactionTemplate writeTransaction;

	public SettingsService(SettingRepository settingRepository, IdempotencyService idempotencyService,
			PlatformTransactionManager transactionManager) {
		this.settingRepository = settingRepository;
		this.idempotencyService = idempotencyService;
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
		this.writeTransaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Reads the complete settings surface.
	 *
	 * @return every key of the closed catalogue with its current value
	 */
	public SettingsResponse readAll() {
		Map<String, Setting> stored = inReadTransaction(() -> {
			Map<String, Setting> rows = new LinkedHashMap<>();
			settingRepository.findAll().forEach(setting -> rows.put(setting.getKey(), setting));
			return rows;
		});
		List<SettingResponse> settings = Arrays.stream(SettingKey.values())
				.map(key -> toResponse(key, stored.get(key.wireKey())))
				.toList();
		return new SettingsResponse(settings);
	}

	/**
	 * Reads one setting, falling back to the seeded default when the row is absent.
	 *
	 * @param key the catalogue entry
	 * @return the canonical value
	 */
	public String effective(SettingKey key) {
		return inReadTransaction(() -> settingRepository.findByKey(key.wireKey())
				.map(Setting::getValue)
				.map(value -> fallbackFor(key, value))
				.orElse(key.defaultValue()));
	}

	/**
	 * Reads the effective language and the closed list of supported languages.
	 *
	 * @return the language payload
	 */
	public LanguageResponse language() {
		Setting setting = inReadTransaction(
				() -> settingRepository.findByKey(SettingKey.LANGUAGE.wireKey()).orElse(null));
		if (setting == null) {
			return new LanguageResponse(LanguageCatalogue.CANONICAL_FALLBACK, false, supportedLanguages(), null);
		}
		String language = LanguageCatalogue.fallback(setting.getValue());
		return new LanguageResponse(language, LanguageCatalogue.isRightToLeft(language), supportedLanguages(),
				setting.getUpdatedAt());
	}

	/**
	 * Changes one setting.
	 *
	 * @param presentedKey the key of the request
	 * @param presentedValue the new value
	 * @param actor the principal performing the write
	 * @param idempotencyKey the key of the write
	 * @return the stored setting, replayed when the key repeats with the same payload
	 */
	public IdempotentResponse<SettingResponse> update(String presentedKey, String presentedValue, SettingActor actor,
			String idempotencyKey) {
		SettingKey key = SettingKey.fromWireKey(presentedKey);
		SettingWrite write = new SettingWrite(key.wireKey(), presentedValue, actor.wireValue());
		return idempotencyService.execute(idempotencyKey, "settings.update", write, HttpStatus.OK.value(),
				SettingResponse.class, () -> applyUpdate(key, presentedValue, actor));
	}

	/**
	 * Changes the default language, the operation decision 0019 reserves for the agent.
	 *
	 * @param presentedLanguage the new language tag
	 * @param actor the principal performing the write
	 * @param idempotencyKey the key of the write
	 * @return the language payload, replayed when the key repeats with the same payload
	 */
	public IdempotentResponse<LanguageResponse> updateLanguage(String presentedLanguage, SettingActor actor,
			String idempotencyKey) {
		SettingWrite write = new SettingWrite(SettingKey.LANGUAGE.wireKey(), presentedLanguage, actor.wireValue());
		return idempotencyService.execute(idempotencyKey, "settings.language", write, HttpStatus.OK.value(),
				LanguageResponse.class,
				() -> toLanguageResponse(applyUpdate(SettingKey.LANGUAGE, presentedLanguage, actor)));
	}

	private SettingResponse applyUpdate(SettingKey key, String presentedValue, SettingActor actor) {
		if (!key.isWritableBy(actor)) {
			throw new ApiException(ErrorCode.SETTING_NOT_WRITABLE, key.wireKey(),
					"The key is owned by the user and cannot be changed by the agent.");
		}
		String normalized = key.normalize(presentedValue);
		return inWriteTransaction(() -> {
			Setting setting = settingRepository.findByKey(key.wireKey()).orElseGet(() -> newSetting(key));
			setting.setValueType(key.valueType());
			setting.setValue(normalized);
			setting.setMinValue(key.minValue());
			setting.setMaxValue(key.maxValue());
			setting.setOwner(key.owner());
			setting.setUpdatedBy(actor.wireValue());
			setting.setUpdatedAt(Instant.now());
			Setting saved = settingRepository.save(setting);
			log.info("Setting {} changed by {}", key.wireKey(), actor.wireValue());
			return toResponse(key, saved);
		});
	}

	private Setting newSetting(SettingKey key) {
		return new Setting(key.wireKey(), key.valueType(), key.defaultValue(), key.minValue(), key.maxValue(),
				key.owner(), "installer", Instant.now());
	}

	private SettingResponse toResponse(SettingKey key, Setting setting) {
		if (setting == null) {
			return new SettingResponse(key.wireKey(), key.defaultValue(), key.valueType(), key.owner(),
					key.minValue(), key.maxValue(), "installer", null);
		}
		return new SettingResponse(key.wireKey(), setting.getValue(), key.valueType(), key.owner(), key.minValue(),
				key.maxValue(), setting.getUpdatedBy(), setting.getUpdatedAt());
	}

	private LanguageResponse toLanguageResponse(SettingResponse setting) {
		return new LanguageResponse(setting.value(), LanguageCatalogue.isRightToLeft(setting.value()),
				supportedLanguages(), setting.updatedAt());
	}

	private String fallbackFor(SettingKey key, String storedValue) {
		if (key == SettingKey.LANGUAGE) {
			return LanguageCatalogue.fallback(storedValue);
		}
		return storedValue;
	}

	private List<SupportedLanguageResponse> supportedLanguages() {
		return LanguageCatalogue.all().stream()
				.map(language -> new SupportedLanguageResponse(language.tag(), language.tier(),
						language.rightToLeft()))
				.toList();
	}

	private <T> T inReadTransaction(Supplier<T> action) {
		return readTransaction.execute(status -> action.get());
	}

	private <T> T inWriteTransaction(Supplier<T> action) {
		return writeTransaction.execute(status -> action.get());
	}

	/**
	 * Payload of a setting write, the value the idempotency fingerprint covers.
	 *
	 * @param key the canonical key of the catalogue
	 * @param value the presented value, may be null
	 * @param actor the principal performing the write
	 */
	private record SettingWrite(String key, String value, String actor) {
	}
}
