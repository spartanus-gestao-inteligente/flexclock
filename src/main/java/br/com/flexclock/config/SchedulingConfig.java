package br.com.flexclock.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the local timers of the backend: the anchor maintenance of the sessions workstream and the
 * two background checks of the pause flow, the 60 minute expiry job and the continuation poller.
 *
 * <p>The anchor maintenance never depends on the OpenCode server being reachable: a heartbeat that
 * stops is the signal of a crashed window, and the cleanup has to happen even when the OpenCode server
 * is down (requirements RF-016, RF-044, RN-008 and RN-009).
 *
 * <p>The pause jobs read the local database and the local OpenCode server only. They never resume a
 * pause on their own: expiry marks the record and publishes the notification contract, and a resume is
 * always an explicit transition (decision 0035; requirement RF-037).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
