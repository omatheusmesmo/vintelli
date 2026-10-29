package dev.omatheusmesmo.vintelli.tui.web;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * Registry of the live browser terminals. The WebSocket layer asks for one
 * session per connection; every session keeps its own runner, backend and
 * worker thread and none of them are reachable from anywhere else, so the
 * lifecycle is exactly the connection lifecycle: created in the open callback
 * with the owner hook, torn down from the close callback or when the shell
 * ends the session itself. Callbacks for connections that have no session
 * anymore are ignored, so cleanup is order safe.
 *
 * <p>The number of concurrently live sessions is capped by
 * {@code vintelli.web.max-sessions} (default 4) and each session is closed
 * after {@code vintelli.web.idle-timeout} (default {@code PT5M}) without
 * client input. The cap protects the process from unbounded threads and
 * screen buffers; the idle timeout reclaims sessions whose client went away
 * without closing the socket.
 */
@Singleton
public final class WebTerminalSessions {

    private static final Logger LOG = Logger.getLogger(WebTerminalSessions.class);
    private final Provider<VintelliShell> shellProvider;
    private final Map<String, WebTerminalSession> sessions = new ConcurrentHashMap<>();
    private final int maxSessions;
    private final Duration idleTimeout;
    private final AtomicInteger reserved = new AtomicInteger();

    WebTerminalSessions(Provider<VintelliShell> shellProvider) {
        this.shellProvider = shellProvider;
        Config config = ConfigProvider.getConfig();
        this.maxSessions = config.getOptionalValue("vintelli.web.max-sessions", Integer.class).orElse(4);
        this.idleTimeout = config.getOptionalValue("vintelli.web.idle-timeout", String.class)
                .map(Duration::parse).orElse(Duration.ofMinutes(5));
    }

    /**
     * Starts the terminal of one connection. Returns whether a session could
     * be created and started; the caller closes the connection on failure.
     */
    public synchronized boolean start(String connectionId, WebTerminalOutput output, Runnable sessionEnded) {
        if (sessions.size() >= maxSessions) {
            return false;
        }
        WebTerminalSession session = sessions.computeIfAbsent(connectionId, key -> create(key, output, sessionEnded));
        if (session == null) {
            return false;
        }
        session.start();
        return true;
    }

    /**
     * Builds the session of one connection, or returns null when the TamboUI
     * runner could not start.
     */
    private WebTerminalSession create(String connectionId, WebTerminalOutput output, Runnable sessionEnded) {
        try {
            return new WebTerminalSession(shellProvider.get(), connectionId, output, sessionEnded, idleTimeout);
        } catch (RuntimeException e) {
            LOG.warn("browser terminal session could not be created", e);
            return null;
        }
    }

    public void stop(String connectionId) {
        WebTerminalSession session = sessions.get(connectionId);
        if (session != null) {
            session.stop();
        }
        remove(connectionId);
    }

    public void input(String connectionId, byte[] bytes) {
        WebTerminalSession session = sessions.get(connectionId);
        if (session != null) {
            session.acceptInput(bytes);
        }
    }

    public void resize(String connectionId, int columns, int rows) {
        WebTerminalSession session = sessions.get(connectionId);
        if (session != null) {
            session.resize(columns, rows);
        }
    }

    /**
     * Drops the registry entry of one connection and releases its reserved
     * slot, at most once per connection.
     */
    public void remove(String connectionId) {
        if (sessions.remove(connectionId) != null) {
            release();
        }
    }

    /**
     * Reserves one concurrent-session slot if the cap has not been reached.
     * Used by the WebSocket capacity check, which runs after authentication,
     * so a failed authentication never consumes a slot.
     */
    public boolean tryReserve() {
        while (true) {
            int current = reserved.get();
            if (current >= maxSessions) {
                return false;
            }
            if (reserved.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Releases one previously reserved slot, once the session ends or fails
     * to start.
     */
    public void release() {
        reserved.decrementAndGet();
    }

}
