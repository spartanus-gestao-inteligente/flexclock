package br.com.flexclock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import br.com.flexclock.common.InstanceDescriptor;
import br.com.flexclock.platform.PlatformService;
import br.com.flexclock.support.SqliteIntegrationTest;

/**
 * Proves that the application context starts against a real SQLite file and that the readiness probe
 * reaches the database.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlexClockApplicationTests extends SqliteIntegrationTest {

	@Autowired
	private PlatformService platformService;

	@Autowired
	private InstanceDescriptor instanceDescriptor;

	@Test
	void contextLoads() {
		assertThat(platformService).isNotNull();
	}

	@Test
	void theLiveProcessIsReadyAndHasAnInstanceIdentifier() {
		assertThat(platformService.isReady()).isTrue();
		assertThat(instanceDescriptor.instanceId()).isNotBlank();
		assertThat(instanceDescriptor.pid()).isEqualTo(ProcessHandle.current().pid());
	}
}
