package br.com.flexclock.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import br.com.flexclock.common.InstanceDescriptor;
import br.com.flexclock.platform.SchemaVersionProvider;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes and removes the instance descriptor file.
 *
 * <p>The descriptor is the discovery document of the live backend: it publishes the process id, the
 * effective listen address and port, the start instant and the per run instance identifier that the
 * client and the MCP sidecar must present. It is written as soon as the web server publishes its
 * effective port, with owner only permissions, and removed on a graceful shutdown so a stale file can
 * never point a client at a dead process.
 *
 * <p>Architecture contract, sections 3.2 and 4.1; decision 0050; requirement RF-003.
 */
@Component
public class InstanceDescriptorWriter implements ApplicationListener<WebServerInitializedEvent> {

	private static final Logger log = LoggerFactory.getLogger(InstanceDescriptorWriter.class);

	private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");

	private final InstanceDescriptor instanceDescriptor;
	private final SchemaVersionProvider schemaVersionProvider;
	private final ObjectMapper objectMapper;
	private final Path descriptorPath;

	public InstanceDescriptorWriter(InstanceDescriptor instanceDescriptor,
			SchemaVersionProvider schemaVersionProvider, ObjectMapper objectMapper,
			@Value("${flexclock.instance.descriptor-path:data/flexclock.instance.json}") String descriptorPath) {
		this.instanceDescriptor = instanceDescriptor;
		this.schemaVersionProvider = schemaVersionProvider;
		this.objectMapper = objectMapper;
		this.descriptorPath = Path.of(descriptorPath).toAbsolutePath().normalize();
	}

	@Override
	public void onApplicationEvent(WebServerInitializedEvent event) {
		instanceDescriptor.bind(instanceDescriptor.address(), event.getWebServer().getPort());
		writeDescriptor();
	}

	/**
	 * Returns the path of the descriptor file.
	 *
	 * @return the descriptor path
	 */
	public Path descriptorPath() {
		return descriptorPath;
	}

	private void writeDescriptor() {
		DescriptorFile content = new DescriptorFile(instanceDescriptor.instanceId(), instanceDescriptor.pid(),
				instanceDescriptor.address(), instanceDescriptor.port(), instanceDescriptor.startedAt(),
				instanceDescriptor.contractVersion(), schemaVersionProvider.current());
		try {
			Path directory = descriptorPath.getParent();
			if (directory != null) {
				Files.createDirectories(directory);
				applyPermissions(directory, OWNER_ONLY_DIRECTORY);
			}
			Path temporary = descriptorPath.resolveSibling(descriptorPath.getFileName() + ".tmp");
			objectMapper.writeValue(temporary.toFile(), content);
			applyPermissions(temporary, OWNER_ONLY_FILE);
			Files.move(temporary, descriptorPath, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			applyPermissions(descriptorPath, OWNER_ONLY_FILE);
			log.info("Instance descriptor written to {}", descriptorPath);
		}
		catch (IOException exception) {
			throw new IllegalStateException("The instance descriptor could not be written at " + descriptorPath,
					exception);
		}
	}

	@PreDestroy
	void removeDescriptor() {
		try {
			boolean removed = Files.deleteIfExists(descriptorPath);
			if (removed) {
				log.info("Instance descriptor removed from {}", descriptorPath);
			}
		}
		catch (IOException exception) {
			log.warn("The instance descriptor could not be removed from {}", descriptorPath, exception);
		}
	}

	private static void applyPermissions(Path path, Set<PosixFilePermission> permissions) {
		try {
			Files.setPosixFilePermissions(path, permissions);
		}
		catch (UnsupportedOperationException exception) {
			log.debug("File permissions are not POSIX on this platform, skipping {}", path);
		}
		catch (IOException exception) {
			log.warn("Could not restrict the permissions of {}", path, exception);
		}
	}

	/**
	 * Content of the instance descriptor file.
	 *
	 * @param instanceId the per run instance identifier
	 * @param pid the process id
	 * @param address the effective listen address
	 * @param port the effective listen port
	 * @param startedAt the start instant of the process
	 * @param contractVersion the contract version the live process speaks
	 * @param schemaVersion the database schema version the process migrated to
	 */
	public record DescriptorFile(String instanceId, long pid, String address, int port, Instant startedAt,
			String contractVersion, String schemaVersion) {
	}
}
