package dev.omatheusmesmo.vintelli.api.idea;

/**
 * User-facing control surface for the optional IntelliJ IDEA MCP connection.
 *
 * <p>The TUI and CLI depend on this port rather than on the IDEA transport
 * implementation, so the client can keep its protocol and timeout details in
 * the IDEA adapter module.
 */
public interface IdeaMcpConnection {

    /**
     * @return the latest connection state without starting a handshake
     */
    Snapshot connectionStatus();

    /**
     * Drops any previous session and attempts a fresh connection.
     *
     * @return the classified result of the connection attempt
     */
    Snapshot reconnectFromUserAction();

    /** Plain connection state that does not expose transport credentials. */
    record Snapshot(String state, String detail) {
        public Snapshot {
            if (state == null || state.isBlank()) {
                throw new IllegalArgumentException("A connection snapshot needs a state");
            }
            detail = detail == null ? "" : detail;
        }
    }
}
