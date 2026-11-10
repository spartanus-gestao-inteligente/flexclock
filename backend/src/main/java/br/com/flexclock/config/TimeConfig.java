package br.com.flexclock.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time source of the backend.
 *
 * <p>Every instant written by the time and pause paths comes from this bean, never from
 * {@code Instant.now()}. Tests replace it with a controllable clock, so the midnight split, the timer
 * completion and the 60 minute pause expiry are proven without sleeping (requirements RF-023, RF-024,
 * RF-037).
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

	/**
	 * System clock in UTC. Instants are always stored in UTC; the user timezone is a column of the work
	 * session and never changes the stored instant (architecture contract, section 4.5).
	 *
	 * @return the clock used by the time and pause services
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
