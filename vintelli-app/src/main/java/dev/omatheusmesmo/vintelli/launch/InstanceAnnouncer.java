package dev.omatheusmesmo.vintelli.launch;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omatheusmesmo.vintelli.launch.InstanceRegistry.Instance;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.vertx.http.HttpServer;
import io.quarkus.vertx.http.HttpServerStart;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Publishes this process in the {@link InstanceRegistry} once the HTTP server
 * listens on its real port, which matters when the port is 0, and removes the
 * record on exit. A failure to write the record is logged and never stops
 * Vintelli from starting.
 */
@Singleton
public final class InstanceAnnouncer {

    static final String HOST_PROPERTY = "quarkus.http.host";
    static final String PROJECT_ROOT_PROPERTY = "vintelli.local.project-root";

    private static final Logger LOG = Logger.getLogger(InstanceAnnouncer.class);

    private final InstanceRegistry registry;
    private final HttpServer httpServer;
    private final String host;
    private final String projectRoot;

    @Inject
    InstanceAnnouncer(ObjectMapper mapper, HttpServer httpServer,
            @ConfigProperty(name = HOST_PROPERTY, defaultValue = InstanceRegistry.LOOPBACK) String host,
            @ConfigProperty(name = PROJECT_ROOT_PROPERTY, defaultValue = ".") String projectRoot) {
        this.registry = InstanceRegistry.forCurrentUser(mapper);
        this.httpServer = httpServer;
        this.host = host;
        this.projectRoot = projectRoot;
    }

    void announce(@ObservesAsync HttpServerStart started) {
        int port = httpServer.getPort();
        if (port <= 0) {
            return;
        }
        long pid = ProcessHandle.current().pid();
        Instance instance = new Instance(pid, port, InstanceRegistry.reachableHost(host),
                InstanceRegistry.canonical(Path.of(projectRoot)));
        try {
            registry.publish(instance);
            LOG.debugf("Vintelli listens on %s (HTTP port %d), instance record published for %s",
                    instance.mcpUrl(), port, instance.projectRoot());
        } catch (IOException | RuntimeException unwritable) {
            LOG.warnf("Vintelli cannot publish its instance record: %s", unwritable.getMessage());
        }
    }

    void retire(@Observes ShutdownEvent shutdown) {
        try {
            registry.retire(ProcessHandle.current().pid());
        } catch (IOException | RuntimeException unremovable) {
            LOG.debugf("Vintelli cannot remove its instance record: %s", unremovable.getMessage());
        }
    }
}
