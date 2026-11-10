package br.com.flexclock.model;

import java.io.Serializable;
import java.time.Instant;

import br.com.flexclock.enums.Owner;
import br.com.flexclock.enums.SettingValueType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Setting implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	private String key;
	
	@Enumerated(EnumType.STRING)
	private SettingValueType valueType;
	
	private String value;
	
	@Column(nullable = false)
	private String minValue;
	
	@Column(nullable = false)
	private String maxValue;
	
	@Enumerated(EnumType.STRING)
	private Owner owner; 
	
	private String updatedBy;
	
	private Instant updatedAt;
	

}
