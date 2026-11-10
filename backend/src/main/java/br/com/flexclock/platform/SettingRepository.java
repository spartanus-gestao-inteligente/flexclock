package br.com.flexclock.platform;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.flexclock.model.Setting;

/**
 * Repository of the settings table.
 *
 * <p>The table is mutable with audit, so unlike the report tables it exposes the update path of the
 * base repository. Every change still goes through {@link SettingsService}, which validates the key and
 * the value and records who changed the row and when (architecture contract, section 4.1; requirement
 * RF-008).
 */
public interface SettingRepository extends JpaRepository<Setting, Long> {

	/**
	 * Finds a row by its closed key.
	 *
	 * @param key the wire key
	 * @return the row when the key exists
	 */
	Optional<Setting> findByKey(String key);
}
