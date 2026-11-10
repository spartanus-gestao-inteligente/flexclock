package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.SettingOwner;
import br.com.flexclock.enums.SettingValueType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Closed key configuration with type, value, range and owner.
 *
 * <p>Stores the software language, timezone, default mode and target, orchestration model, report
 * defaults, OpenCode endpoint and region clock source. Mutable with audit: every change records who
 * changed it and when (architecture contract, section 4.1; decisions 0019, 0026, 0057).
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
public class Setting {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "key", nullable = false, unique = true, length = 128)
	private String key;

	@Column(name = "value_type", nullable = false, length = 32)
	private SettingValueType valueType;

	@Column(name = "value", nullable = false, length = 2000)
	private String value;

	@Column(name = "min_value", length = 255)
	private String minValue;

	@Column(name = "max_value", length = 255)
	private String maxValue;

	@Column(name = "owner", nullable = false, length = 32)
	private SettingOwner owner;

	@Column(name = "updated_by", nullable = false, length = 128)
	private String updatedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Setting() {
		// Required by JPA.
	}

	/**
	 * Creates a setting row.
	 *
	 * <p>Used when the catalogue has to materialize a key that the migration did not seed, so the
	 * backend can still answer the complete settings surface (requirement RF-008).
	 *
	 * @param key the closed key
	 * @param valueType the declared type of the value
	 * @param value the canonical value
	 * @param minValue the declared lower bound, may be null
	 * @param maxValue the declared upper bound, may be null
	 * @param owner the principal allowed to change the key
	 * @param updatedBy the principal that wrote the value
	 * @param updatedAt the instant of the write
	 */
	public Setting(String key, SettingValueType valueType, String value, String minValue, String maxValue,
			SettingOwner owner, String updatedBy, Instant updatedAt) {
		this.key = key;
		this.valueType = valueType;
		this.value = value;
		this.minValue = minValue;
		this.maxValue = maxValue;
		this.owner = owner;
		this.updatedBy = updatedBy;
		this.updatedAt = updatedAt;
	}
}
