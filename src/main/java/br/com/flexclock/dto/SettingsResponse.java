package br.com.flexclock.dto;

import java.util.List;

/**
 * The complete settings surface.
 *
 * <p>The list is bounded by construction: it holds exactly the closed catalogue, so it is never
 * paginated and never grows with the data of the product (requirement RF-164 applied to a closed
 * configuration surface).
 *
 * @param settings the settings in catalogue order
 */
public record SettingsResponse(List<SettingResponse> settings) {
}
